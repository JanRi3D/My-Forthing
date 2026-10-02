# Live view (feature/live-view)

Package `me.ri3d.dashcam.live`. Builds on the connection manager (CONTRACTS §7) and `:recorder` (§6). Route `Live`,
`FeatureFlags.live = true` (Home tile "Live-Ansicht").

| File | Contents |
| --- | --- |
| `LivePlayer.kt` | `LiveStream` (URL constants), `rtspCandidates()`, `rtspStatus()`, `LivePlayer` seam + `PlayerEvent` + `StreamError`, `ExoLivePlayer` (Media3), `LiveFrameSource` (Phase 4 hook), Hilt `LiveModule` |
| `RtspSdpProxy.kt` | `RtspSdpProxy` (loopback RTSP proxy that repairs the recorder's SDP), `rewriteSdp()`, `repairSps()`, RTSP message reader, `rtspDescribe()` / `captureRtspDescribe()` (raw OPTIONS + DESCRIBE for Diagnose) |
| `LiveViewModel.kt` | `LiveViewModel` (stream lifecycle, retry, commands, late replies, screenshot), `StreamState`, `StreamError.reason`, `CommandUi`, `commandMessage()`, `replyText()` |
| `Screenshot.kt` | `saveScreenshot()` – the screenshot folder contract and the gallery copy |
| `LiveScreen.kt` | `liveGraph()`, `LiveScreen()` (slots), full screen, state views |

## Pipeline

```
RecorderConnectionManager.state is Ready  &&  screen started (ON_START … ON_STOP)
        │ yes                                          │ no → player.stop(), StreamState.Off
        ▼
capability 20481 (once per session, 3 s) → candidates: 1. rtspServer URL of the first channel
        │                                                (hardware: rtsp://192.168.42.1:554/ch1/sub)
        │                                             2. rtsp://192.168.42.1/ch1/sub/av_stream (traced)
        ▼
ExoPlayer ── RtspMediaSource(forceUseRtpTcp = true) → rtsp://127.0.0.1:<port>/<path>
        │      RtspSdpProxy (one per attempt) ── socket from Ready.network.socketFactory ── recorder :554
        │        DESCRIBE answer repaired (a=control:*, o/s/c/t, bare + cut parameter sets, video only), raw after PLAY
        │      each candidate over TCP; RTSP 461 → the same URL with forceUseRtpTcp = false (UDP), then the next
        │      (no credentials, 8 s RTSP timeout per attempt)
        ▼
MediaCodec video renderer (audio track type disabled) ── TextureView (AndroidView, letterboxed, keepScreenOn)
        │                                                     │
        │ PlayerEvent: Buffering / Playing / Size / Failed    └─ getBitmap(w, h) → screenshot, LiveFrameSource
        ▼
LiveViewModel: Loading → Playing ⇄ Buffering; all candidates failed → retry after 1.5 s → StreamState.Failed
               + "Erneut versuchen"; every attempt and every proxy step → manager.note("rtsp", …) → Diagnose
               export "notes.rtsp"; a final failure on the description → raw DESCRIBE capture once
```

## Decisions

**Start only when Ready.** The original app opens the preview after the control session; the stream starts when
the connection state is `Ready` *and* the screen is started, and stops on `ON_STOP`, on leaving the screen, and when
the session ends (disconnect, Wi-Fi loss, heartbeat loss). The rotation into full screen is not a stop
(`Activity.isChangingConfigurations`); the player lives in the ViewModel, so it survives the rotation. Coming back
from the background starts again only after the manager's ON_START reconnect reaches `Ready`. While the video plays
or buffers, the surface keeps the screen on (`TextureView.keepScreenOn`): otherwise the display timeout would stop
the activity and, through the manager's ON_STOP, the session.

**URL candidates (hardware 2026-10-02).** The physical recorder reports its own URL in capability 20481:
`rtspServer: [{chanNo 1, url "rtsp://192.168.42.1:554/ch1/sub"}]`, not the vendor app's hardcoded
`rtsp://192.168.42.1/ch1/sub/av_stream`, and live view did not work with the hardcoded URL. Each start therefore asks
the manager for 20481 (`capabilities(BASIC)`, cached per session, 3 s timeout) and tries, in order: the URL of the
first channel (lowest `chanNo`), used only when it is `rtsp://` on 192.168.42.1 without user info; then
`LiveStream.URL`. `rtsp://192.168.42.1:554/ch1/main` is deliberately never tried (unknown). The URL that played last
moves to the front of the next start (`rtspCandidates(basic, preferred)`). A failed candidate is followed at once by
the next one; only when all have failed does the error handling below apply.

**Retry.** Each start gets exactly one automatic retry of the whole candidate list after a stream error, 1.5 s later
(`RETRY_DELAY_MS`; the pending retry is cancelled by any stop or new start), then `StreamState.Failed` with
"Livebild nicht verfügbar", a German reason, a second line with the numeric code and the raw innermost cause
("Code 2000 · RtspPlaybackException: SETUP 461"; local reasons without a Media3 code show none) and "Erneut
versuchen" (a fresh start with its own retry). Having played resets the retry, so a later drop is retried once again. Reasons by
Media3 `PlaybackException.errorCode` group: 2xxx (I/O, network) "Recorder liefert kein Livebild (RTSP, Port 554)",
3xxx (parsing) "Livebild des Recorders nicht lesbar (Datenformat)", 4xxx (decoder) "Videoformat auf diesem Handy
nicht abspielbar", the end of the stream (`STATE_ENDED` → `STREAM_ENDED`; the original app stops on stream closure
and has no retry loop of its own) "Der Recorder hat das Livebild beendet", anything else "Unerwarteter Fehler beim
Abspielen". Media3's RTSP client gives up after its default 8 s timeout per attempt.

**SDP repair (hardware 2026-10-02).** Second test: both URLs failed in Media3's description parsing,
`ERROR_CODE_IO_UNSPECIFIED (2000): IllegalArgumentException: missing attribute control`. Third test (1.0.1, mobile data
on, session Ready): the raw capture got OPTIONS 200 (`Public: OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE`) and
DESCRIBE 200 with exactly this SDP (Content-Length 174, CRLF lines):

```
v=0
m=video 0 RTP/AVP 96
a=rtpmap:96 H264/90000
a=fmtp:96 profile-level-id=4DE028;packetization-mode=1;sprop-parameter-sets=AAAAAWdNAB+NjUBuH9CAAALuAACvyA8=,AAAAAWjuOIA=
```

Three defects for Media3 1.11: no `a=control` (`RtspMediaTrack`'s constructor: "missing attribute control"; no
Media3 option, `SessionDescriptionParser` matches names exactly); `sprop-parameter-sets` carry Annex-B start codes
(`00 00 00 01 67 …`, `00 00 00 01 68 …`), which Media3 decodes as part of the NAL unit and prefixes once more; and the
SPS itself (`67 4D 00 1F …`, Main, level 3.1, 880x496, VUI with timing 90000/1500, `pic_struct_present_flag`) sets
`bitstream_restriction_flag` but ends two bits later, so Media3's `NalUnitUtil.parseSpsNalUnit` reads past its end
(`ArrayIndexOutOfBoundsException` – thrown on Media3's playback thread outside its error handling, so the app would
end; `RecorderSdpMedia3Test`). `o=`, `s=`, `t=` and `c=` are missing too (RFC 4566 requires them; Media3 1.11 does not
check). The player therefore talks to `RtspSdpProxy`, a small RTSP proxy on `127.0.0.1:<ephemeral>` (one per attempt):
- **Loopback (the 1.0.1/1.0.2 ECONNREFUSED).** The proxy bound `InetAddress.getLoopbackAddress()`, which is `::1` on
  Android (libcore returns the IPv6 loopback first; `127.0.0.1` on a desktop JVM, so the unit tests passed), while
  Media3 dialled `127.0.0.1`: `ErrnoException: connect failed: ECONNREFUSED` for every attempt and no proxy note at
  all. Reproduced on the emulator with 1.0.2 (`/proc/net/tcp6` listed the app's socket on `::1`). The proxy now binds
  the literal `127.0.0.1`, synchronously in its constructor before `url` exists (connections queue in the backlog
  until accepted), and notes `listening 127.0.0.1:<port>` and `accepted` (per Media3 connection), or `accept failed`.
- Media3 plays `rtsp://127.0.0.1:<port><path>` with the default socket factory; the proxy opens one socket per Media3
  connection to the recorder from `Ready.network.socketFactory` (bound to the recorder Wi-Fi, 5 s connect timeout).
- Phone → recorder: every request line `rtsp://127.0.0.1:<port>/…` becomes the recorder's URL (its exact authority),
  everything else verbatim, for the whole connection (keep-alives, TEARDOWN – noted as `TEARDOWN forwarded`).
- Recorder → phone: verbatim, except the DESCRIBE 200 answer, whose SDP goes through `rewriteSdp(sdp, host)`:
  - only the first `m=video` section is kept (sound is muted anyway; a second `*` track would SETUP the same URL
    twice); without a video section all stay;
  - a kept section without control gets `a=control:*` – Media3 then SETUPs the aggregate (session) URL, which RFC 2326
    C.1.1 prescribes when per-track control is absent; a control attribute in other letter case is written `a=control`;
  - missing session lines are added, the session part in RFC 4566 order: `o=- 0 0 IN IP4 <recorder host>`,
    `s=My Forthing`, `c=IN IP4 <recorder host>`, `t=0 0`;
  - every `sprop-parameter-sets` entry loses a leading `00 00 00 01` / `00 00 01` and is re-encoded (Base64 with
    padding); entries that are not Base64 stay as sent;
  - an SPS that sets `bitstream_restriction_flag` but ends before those fields is cut there (`repairSps`: the flag
    becomes 0, the RBSP stop bit follows, emulation prevention re-applied). For the recorder the SPS's last byte `0F`
    becomes `0A`. No values are invented; decoders assume the defaults of absent restriction fields (reordering up to
    the DPB size – watch the latency). SPS with scaling matrices or HRD parameters are not walked and stay
    (`ponytail:`);
  - `profile-level-id` (`4DE028`, as sent, although the SPS says level 3.1) and `packetization-mode` stay;
    Content-Length is recomputed; an absolute `Content-Base` / `Content-Location` points to the proxy (path kept).
  After the PLAY answer, or as soon as an interleaved `$` frame appears, the recorder's side is piped raw (the in-band
  SPS/PPS before IDR frames reach the decoder unchanged).
  The recorder's text above becomes
  `v=0 / o=- 0 0 IN IP4 192.168.42.1 / s=My Forthing / c=IN IP4 192.168.42.1 / t=0 0 / m=video 0 RTP/AVP 96 /
  a=rtpmap:96 H264/90000 / a=fmtp:96 profile-level-id=4DE028;packetization-mode=1;sprop-parameter-sets=Z00AH42NQG4f0IAAAu4AAK/ICg==,aO44gA== /
  a=control:*`, and Media3 builds one H.264 track on the session URL with initialization data
  `00 00 00 01 67 4D 00 1F 8D 8D 40 6E 1F D0 80 00 02 EE 00 00 AF C8 0A` and `00 00 00 01 68 EE 38 80`, 880x496,
  codecs `avc1.4DE028` (`RtspSdpProxyTest`, `RecorderSdpMedia3Test` against Media3's own package-private classes).
- **Stop.** `ExoLivePlayer` closes the proxy with a linger (`close(2_000)`): the listening socket closes at once, open
  connections get 2 s, because Media3 stops on its playback thread and sends TEARDOWN through the proxy before closing
  its socket. When Media3 closes its side the proxy half-closes towards the recorder (FIN after the TEARDOWN) and ends
  the connection when the recorder closes or the phone's side is gone.
- Any app on the phone could reach the recorder's RTSP through the loopback port while it is open, as it can over the
  Wi-Fi itself.

**Transport.** `RtspMediaSource.Factory().setForceUseRtpTcp(true)` – TCP interleaved, as traced; RTP shares the
RTSP socket. If the server answers SETUP with **461 Unsupported Transport** (Media3: `RtspPlaybackException`
"SETUP 461" inside a source error; `rtspStatus()` reads it), the same URL is tried once more with
`setForceUseRtpTcp(false)`: Media3 then asks for RTP over UDP first (and falls back to TCP itself on a UDP 461).
**UDP only works while the recorder Wi-Fi is the phone's default network (mobile data off):** the socket factory
binds only the RTSP control socket, Media3's RTP/RTCP datagram sockets are not bound to the recorder network. A
failure of a UDP attempt adds that hint to the error state ("Über UDP kommt das Livebild nur an, wenn …").
No user/password (none in the traced setup; not a claim about the recorder's access checks).
The proxy's socket to the recorder comes from `network.socketFactory` of the `Ready` state (the network the control
socket is bound to), so RTSP goes over the recorder Wi-Fi while mobile data serves everything else; Media3 itself only
connects to the loopback proxy. `Ready.network` is null only in debug simulator mode; then the candidates come from
the simulator's 20481 on the simulator host (`rtspCandidates(…, simulator = true)`: `rtsp://10.0.2.2:7554/ch1/sub`,
then `LiveStream.SIMULATOR_URL` = `rtsp://10.0.2.2:7554/ch1/sub/av_stream`) with the default socket factory;
`:recorder:runSimulator` serves them with `SimulatorRtspServer` (see below). Ready without a network outside
simulator mode starts nothing (never an unbound socket) and shows a connection problem, "Keine Verbindung über das
Dashcam-WLAN – bitte neu verbinden", with "Zur Verbindung". The traced URL is defined once (`LiveStream.URL`).

**Diagnose.** Every start and attempt goes to the manager's notes (`note("rtsp", …)`, last 50, redacted), which the
Diagnose export carries under `notes.rtsp`: `start: <candidates>`, `<url> tcp|udp: playing`, `<url> tcp: video
1280x720`, `<url> tcp: ERROR_CODE_IO_UNSPECIFIED (2000), RTSP 461: RtspPlaybackException: SETUP 461`
(`StreamError.describe()`: Media3 error name and code, RTSP status if any, raw innermost cause). The proxy adds, per
attempt: `proxy <url>: OPTIONS 200`, `proxy <url>: DESCRIBE 200: kept m=video 0 RTP/AVP 96, a=control:* added to 1,
dropped m=audio …[, H264 without sprop-parameter-sets][, Content-Base …]`, `proxy <url>: SETUP 200 (Transport: …)`,
`proxy <url>: PLAY 200`, `proxy <url>: TEARDOWN forwarded`, `proxy <url>: stream connection ended after N bytes`, or
`proxy <url>: connect failed: …`; before them `proxy <url>: listening 127.0.0.1:<port>` and `proxy <url>: accepted`
(none of these lines at all before a Media3 error means Media3 never reached the proxy). Since `fix/real-recorder-3`
the DESCRIBE line also says `added o= s= c= t=`, `start codes removed from 2 sprop-parameter-sets` and `SPS cut before
its missing bitstream_restriction fields` when it did that.
**Raw description:** Diagnose → "Diagnose erfassen" while `Ready` on the recorder Wi-Fi also runs OPTIONS and
DESCRIBE (`Accept: application/sdp`, 5 s timeouts) for the capability URL, then the traced one if that fails, on a
socket bound to the recorder network; the full exchange (headers and SDP, only `Authorization` masked; `> ` sent,
`< ` received, `! ` error) goes to `rtsp.describe` of the export and, one note per URL starting with `describe`, to
`notes.rtsp`. The same capture runs once per live screen by itself when the stream finally fails on the description
(`StreamError.sdpProblem`: a 3xxx code or an innermost `IllegalArgumentException` / `ParserException`), so the next
export contains the SDP even without a live capture in Diagnose. Simulator mode skips the raw capture.

**Sound.** Live sound is off, as traced (the original sets preview sound to false): the audio track type is
disabled in the track selection, so audio is neither decoded nor played. The screen makes no statement about
audio in recordings (the microphone setting is separate).

**Decoding (chosen).** Hardware MediaCodec decoders first, with Media3's decoder fallback
(`DefaultRenderersFactory.setEnableDecoderFallback(true)`): if a decoder fails to initialise, the next one for the
format is tried, normally the platform software decoder (`c2.android.*`). The original app decodes in software
(PlayM4, hardware decoding off); if a hardware decoder initialises but renders garbage on the real stream, switch
to a software-only `MediaCodecSelector` – that needs the real recorder to decide.

**Latency.** `DefaultLoadControl` with 500 ms before playback, 1 s after a rebuffer, 1–3 s buffer (`ponytail:`
knob, tune on the recorder). Media3 has no live-offset control for RTSP; smaller buffers are the only lever.

**Screen.** 16:9 black box (rounded), video letterboxed by the stream's display aspect (pixel aspect applied), the
surface described as "Livebild der Dashcam" for screen readers, "Live" badge (dot + text) and "Handyzeit <localized
date/time with seconds>" while playing or buffering – the phone's clock, labelled as such; the recorder's time is
not known here. State views inside the box: not connected / no Wi-Fi / wrong Wi-Fi / error → "Erst mit dem Recorder
verbinden" + reason + "Zur Verbindung" (→ `Connection`); Connecting / TCP / Negotiating with the dashcam's state
texts and a spinner; opening, buffering, failed. The box grows if a state message does not fit at large font sizes.
Full screen (top-bar button, only when Ready): landscape (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`), system bars hidden
(swipe shows them briefly), badges inside the safe drawing area (cutouts), exit button at the bottom end so it does
not cover "Handyzeit"; Back, Escape and the exit button return; orientation `UNSPECIFIED` and the bars are restored
only when full screen ends, not on the rotation itself. On large screens Android may ignore the orientation request;
the layout still fills the window.

**Recorder commands** ("Auf der SD-Karte der Dashcam", enabled only when Ready): Foto = 12292
`{"chanNo":1,"interval":3,"number":1}`, 5er-Serie = one 12292 with `number = 5` (a visible second button rather
than a hidden long-press), Aufnahme = 12293 `{"chanNo":1,"recType":1}`. Recording starts the traced 10 s
**UI countdown** ("Aufnahme · 7 s" + "Countdown der App … die Clip-Länge bestimmt der Recorder"); it sends no stop
command; its end or any reply (success, as traced; a failure too) ends the UI state. Timeouts: 10 s (client
default) for photo and record, 30 s for the burst (`ponytail:`, reply timing unverified). Replies are shown as
reported: "Foto – Recorder meldet: <filePath>" plus "Zeit laut Recorder: <fileTime>" (raw string), or "OK ohne
Dateipfad (rval 0)" when the reply carries no path. Errors: recorder rvals with the app-table meaning and the raw
code ("5er-Serie – Recorder meldet Fehler: Foto fehlgeschlagen (Code 303)"); no answer (timeout, connection lost) →
"Ergebnis unbekannt – neu verbinden und prüfen (…)"; other local errors with their local meaning and code.

**Late and further replies.** A 12292/12293 reply without a waiting request (`RecorderNotification.Unmatched`)
first resolves the last command of that kind if it ended without an answer: "Aufnahme, verspätete Antwort – Recorder
meldet: <path>" replaces "Ergebnis unbekannt". Otherwise it is counted per kind since the last command of that kind,
with the latest reply in short – path, "OK ohne Dateipfad (rval 0)" or meaning + code for a non-zero rval:
"5er-Serie: 2 weitere Antworten des Recorders (zuletzt: Foto fehlgeschlagen (Code 303))". Timelapse is out of scope.

## Screenshot folder contract (consumed by feature/media)

```
filesDir/screenshots/<uuid>.jpg     JPEG (quality 92), current frame at the stream's display size
filesDir/screenshots/<uuid>.json    {"id":"<uuid>","capturedAt":"2026-10-01T17:42:08+02:00","source":"live","width":1280,"height":720}
```

- `<uuid>` = random UUID v4, identical in both names and in `id`; media imports it as a `SCREENSHOT` item.
- `capturedAt`: phone time, ISO-8601 with offset, whole seconds. `width`/`height`: pixels of the JPEG.
- EXIF in the JPEG: `DateTimeOriginal` (`yyyy:MM:dd HH:mm:ss`, same instant) and `OffsetTimeOriginal` (`+02:00`).
- Both files are written as `*.tmp` and appear by rename, the JPEG first, the JSON last: **a JSON means its JPEG is
  complete**. Leftover `*.tmp` files are never valid items.
- The gallery copy (`MediaStore`, `Pictures/<app_name>`, display name `Live_yyyyMMdd_HHmmss.jpg`, `DATE_TAKEN` set)
  is independent: deleting one never touches the other. Android 8–9 get no gallery copy (would need
  `WRITE_EXTERNAL_STORAGE`; `ponytail:`), the snackbar then says "nur in der App". Snackbar on success:
  "Screenshot gespeichert".
- **Product decision:** the `Pictures/` copy on Android 10+ is intended (the artboard's "Screenshot saved to
  Photos"). Whether Google Photos (or another gallery) backs it up is the user's own Photos setting; it does not
  touch the app's promise that *app data* stays on the phone (`filesDir`, no Android backup).

## Phase 4 hooks

```kotlin
// Singleton, inject anywhere
class LiveFrameSource @Inject constructor() {
    val videoSize: StateFlow<IntSize?>                                        // stream display size, only while it plays
    fun frames(targetFps: Int /* 1..30 */, wanted: () -> Boolean = { true }): Flow<Frame>   // me.ri3d.dashcam.plates.Frame
}

fun NavGraphBuilder.liveGraph(
    navController: NavController,
    leadingControls: @Composable RowScope.() -> Unit = {},               // left of Screenshot: Plates (plates-ui)
    trailingControls: @Composable RowScope.() -> Unit = {},              // right of Screenshot: Upscale (enhance-ui)
    belowControls: @Composable ColumnScope.() -> Unit = {},              // recognised plates + "Verlauf", battery note
    overlay: @Composable BoxScope.(videoRect: Rect) -> Unit = {},
    renderEffect: @Composable (videoRect: Rect) -> RenderEffect? = { null },   // android.graphics.RenderEffect
)

@Composable fun LiveScreen(onBack, onConnect, leadingControls = {}, trailingControls = {}, belowControls = {},
                           overlay = {}, renderEffect = { null }, viewModel = hiltViewModel())
```

- `frames()` suspends while no stream plays and grabs only while collected and the stream plays (`Playing`; stays
  during `Buffering`); each `Frame` is a fresh software ARGB_8888 bitmap at most 1280 px wide (stream size if
  smaller), owned by the collector, `timestampMs` = phone wall clock. Every grab is a GPU readback on the main thread
  (`TextureView.getBitmap`): pass `wanted = processor::wantsFrame` (`LivePlateProcessor`) so frames the processor
  would drop are never grabbed; a slow collector delays the next grab (no buffering). Feed
  `LivePlateProcessor.submit()`.
- `overlay` is drawn over the video box in normal and full-screen mode; `videoRect` is the letterboxed video in the
  overlay's coordinates (px). A detection box from a frame of width `w` maps with `videoRect.width / w`.
- `leadingControls` / `trailingControls` sit left / right of the Screenshot button in one centred row (32 dp gaps),
  as in the artboard. `belowControls` follows that row, before the recorder commands; each of its children is a child
  of the screen's column (24 dp spacing), so wrap several items in one `Column` for tighter spacing.
- `renderEffect` is evaluated inside the video box with the displayed `videoRect` (normal and full screen) and
  applied to the surface (`View.setRenderEffect`, API 31+): enhance-ui computes
  `LiveSharpen.effect(scale = videoRect.width / videoSize.width)` there.
- `liveGraph` currently registers no slots (`AxoNavHost`: `liveGraph(navController)`); plates-ui / enhance-ui add
  their arguments there.

## Simulated vs needs the real recorder

Verified [SIM] / offline (`LiveViewModelTest`, 16 Robolectric tests with a fake player and `RecorderSimulator`):
start only when Ready and started; RTSP gets the Ready network's `socketFactory` outside simulator mode (Robolectric
`ShadowNetwork`) and the default one with `SIMULATOR_URL` in simulator mode; nothing in the background; stop on
background and disconnect, restart on foreground; `videoSize` published only once playing; one retry after exactly
1.5 s then `Failed`, manual retry, retry again after a successful play; a stop cancels the pending retry; German
reasons by error code group; exact 12292/12293 bodies; reply text with path/time and without path; countdown
10 → 7 → end, timeout → "Ergebnis unbekannt", immediate reply ends it, never a 12294; a late 12293 resolves the
unknown outcome; unmatched burst replies counted (path and rval 303 meaning) and reset; rval 303/311 with app
meaning and raw code; screenshot files, JSON and EXIF; `NoFrame` / saved events; frames only while playing and
wanted; `targetFps` outside 1..30 rejected. Since `fix/real-recorder-1` (`RecorderSimulator` answers 20481 in the
hardware shape): the recorder's URL first, the traced one at once after it, one retry of both, 20481 queried once
per session; the URL that played first after a drop; a 461 retries the same URL over UDP and a UDP failure carries
the hint; candidate rules (first channel, recorder host only, no credentials, no duplicates, preferred first);
`rtspStatus()`; the notes for Diagnose.

Verified on hardware 2026-10-02 (owner's Diagnose export; AE-DC2013-LQ2, fw SX5G-3776510A_A, phone API 37,
`FORTHING-A267451`, mobile data off): the session, capability 20481 with
`rtspServer: [{chanNo 1, url "rtsp://192.168.42.1:554/ch1/sub"}]` (no `auth` field) and `downloadPath
"http://192.168.42.1:80"`. **Live view did not work** with the then hardcoded `/ch1/sub/av_stream` URL over TCP; the
cause was not captured (that build logged no RTSP attempts). Second test the same day (build from
`fix/real-recorder-1`, session `Ready` throughout): **both** URLs reach the RTSP server and get a DESCRIBE answer,
which Media3 rejects with `ERROR_CODE_IO_UNSPECIFIED (2000): IllegalArgumentException: missing attribute control` –
see "SDP repair". Third test (app 1.0.1 from `fix/real-recorder-2`, mobile data **on**, Wi-Fi bound, session
Ready): every Media3 attempt (both URLs, initial and retry) ended with `ERROR_CODE_IO_UNSPECIFIED (2000):
ErrnoException: connect failed: ECONNREFUSED` and no proxy note – the `::1` loopback bug (see "SDP repair"); the raw
capture over a bound socket got OPTIONS 200 and DESCRIBE 200 with the SDP quoted there (the recorder's `ch1/sub`
answers RTSP on port 554 with mobile data on). Fixed on `fix/real-recorder-3` (1.0.3); the stream itself is still
unverified.

Since `fix/real-recorder-2` [SIM, fake RTSP server] (`RtspSdpProxyTest`, `RecorderSdpMedia3Test`): the proxied
DESCRIBE carries `a=control:*`, only the video section and an exact Content-Length; request lines reach the
recorder's URL (also GET_PARAMETER after PLAY); interleaved data passes byte for byte; an absolute Content-Base points
to the proxy and an existing control (`a=Control:trackID=0`) is kept as `a=control`; without a video section every
section stays; the raw DESCRIBE transcript holds headers and SDP and an error line for an unreachable URL;
`Authorization` is masked; Media3's track building fails on the recorder shape and accepts the rewrite.

Since `fix/real-recorder-3` [SIM] (`SimulatorRtspServerTest`, `RtspSdpProxyTest`, `RecorderSdpMedia3Test`): the
recorder's exact SDP is rewritten as above and parsed by Media3 into one H.264 track with the bare, cut SPS and the
PPS (880x496); its uncut SPS makes Media3 throw `ArrayIndexOutOfBoundsException`; `repairSps` keeps complete SPS,
cuts the recorder's and the simulator's, keeps emulation prevention; the proxy notes `listening`/`accepted`, the
repairs and `TEARDOWN forwarded`, and a TEARDOWN sent after `close(linger)` still reaches the recorder.
`SimulatorRtspServer` (`:recorder` test fixtures, started by `:recorder:runSimulator` on port **7554** – 8554 is the
running Android emulator's gRPC port – and advertised by the simulator's 20481 as `rtsp://10.0.2.2:7554/ch1/sub`)
mimics the recorder: same `Public` line, the SDP in the recorder's shape (no `o=`/`s=`/`t=`/`c=`/`a=control`,
start-coded parameter sets, an SPS cut two bits after its `bitstream_restriction_flag`) with the parameter sets of
`sim/clip.h264` (the 25 frames of `sim/clip.mp4`, 320x176 Baseline, one IDR, Annex B), SETUP only over TCP on the
aggregate URL (`Transport: RTP/AVP/TCP;unicast;interleaved=0-1`; per-track URLs 404 unless `trackUrls = true`, UDP
461, PLAY before SETUP 455), then RTP on channel 0 (single NAL units up to 1400 bytes, FU-A above, 90 kHz, marker on
the last packet of a frame, SPS + PPS before every IDR, 25 frames/s, looped); OPTIONS / GET_PARAMETER keep it,
TEARDOWN ends the connection.

Emulator, `fix/real-recorder-3` (API 36, `emulator-5556`, debug build against `:recorder:runSimulator`) [SIM]: 1.0.2
first reproduced the hardware failure exactly ("Code 2000 · ErrnoException: connect failed: ECONNREFUSED", no proxy
note; the app's listening socket on `::1` in `/proc/net/tcp6`). With the fix the simulated stream **plays**: notes
`listening` → `accepted` → `OPTIONS 200` → `DESCRIBE 200: kept m=video 0 RTP/AVP 96, a=control:* added to 1, added
o= s= c= t=, start codes removed from 2 sprop-parameter-sets, SPS cut before its missing bitstream_restriction
fields` → `SETUP 200 (Transport: RTP/AVP/TCP;unicast;interleaved=0-1)` → `PLAY 200` → `video 320x176` → `playing`
(1.2–1.8 s from `start:`, decoder `c2.goldfish.h264.decoder`); "Live" badge and phone clock over the moving
picture; keep-alive OPTIONS every 30 s; Screenshot saved a 320x176 JPEG + JSON ("Screenshot gespeichert"); the plate
toggle collected frames ("Läuft auf diesem Handy · 4,2 Bilder/s"); full screen in landscape and back; leaving the
screen sent TEARDOWN to the server (`TEARDOWN forwarded`, server log `TEARDOWN … -> 200`, then `stream connection
ended after 119–132 KB` for ≈ 10 s); three opens in a row each played and tore down; no crash.

Emulator walkthrough (API 36, `emulator-5556`) [SIM], before the review fixes of `feature/live-view`: not connected → "Erst mit dem
Recorder verbinden / Nicht verbunden / Zur Verbindung"; simulator session Ready → RTSP to 10.0.2.2:554 fails twice
(`ERROR_CODE_IO_UNSPECIFIED`, initial + one retry) → error state with "Erneut versuchen" → two more attempts →
error again; Foto / 5er-Serie / Aufnahme reached the simulator with the exact bodies and showed "… Recorder meldet:
OK ohne Dateipfad (rval 0)" (the simulator's replies carry no `param`; the record countdown ended at once because
the reply came at once); full screen in landscape with hidden bars, left with Escape and with Back, no stream
restart from the rotation; background → session stop, foreground → reconnect → stream attempts again. After the
fixes: see the branch report. **Not exercised** (also not with the simulator): buffering, a long session with the
screen kept on, a real camera picture, UDP.

Hardware checklist (owner):
0. **Next test first (1.0.3):** open Live, wait for video or the error, then Diagnose (while connected). Expected in
   `notes.rtsp`, per attempt: `proxy rtsp://192.168.42.1:554/ch1/sub: listening 127.0.0.1:<port>`, `… accepted`,
   `… OPTIONS 200`, `… DESCRIBE 200: kept m=video 0 RTP/AVP 96, a=control:* added to 1, added o= s= c= t=, start codes
   removed from 2 sprop-parameter-sets, SPS cut before its missing bitstream_restriction fields`, `… SETUP 200
   (Transport: …)`, `… PLAY 200`, then `rtsp://192.168.42.1:554/ch1/sub tcp: video 880x496` and `… tcp: playing`;
   on leaving `… TEARDOWN forwarded` and `… stream connection ended after N bytes`. Reading the result: no `listening`
   line → the proxy did not start; `listening` without `accepted` → Media3 never reached it; a 4xx on SETUP → the
   recorder wants another SETUP URL than the aggregate one (per-track); PLAY 200 but no `video …` and a 4xxx code →
   the decoder rejected the stream (then the in-band SPS may be cut like the SDP's – note it); `video …` but a black
   or broken picture → hardware decoder (see Decoding). If only UDP plays, repeat with mobile data on (expected to
   fail, see Transport).
1. RTSP before vs. after the control session: does `ch1/sub` answer without a session (the app never tries)?
2. Concurrent clients: a second RTSP client (or the vendor app) while this one streams; does the recorder refuse,
   share or drop the first?
3. Stream errors: which Media3 error codes appear when the hotspot drops, the recorder restarts, or the session
   ends while streaming; does the recorder close the stream (`STREAM_ENDED`) on its own after some time; is the 8 s
   RTSP timeout and the 1.5 s retry delay right for the recorder?
4. Codec and resolution of `ch1/sub` (and frame rate, audio codec): the SDP's SPS says H.264 Main, 880x496, timing
   90000/1500 (≈ 30 frames/s), no audio section; `notes.rtsp` has the decoded size; check that hardware
   decoding renders correctly (else software-only selector); measure latency with the 500 ms start buffer.
5. Socket binding: stream works with mobile data on (sockets via `network.socketFactory`).
6. Photo / burst / record replies: `filePath`/`thmPath`/`fileTime` values and their time zone, how many 12292
   replies a burst produces and when (the timeout is 30 s), whether 12293 answers at start or at completion (a
   second 12293 reply would show as "weitere Antwort"), and the real manual clip length versus the 10 s countdown.
7. Whether photo/record commands interrupt or affect the live stream ("Nicht im Vorschaumodus", rval 304).
8. Screenshot from the real stream: size equals the stream size, colours correct, file + gallery copy.
9. A long live session: the screen stays on, the session survives (no display-timeout ON_STOP).
