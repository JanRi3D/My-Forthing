# Live view (feature/live-view)

Package `to.axolotl.cam.live`. Builds on the connection manager (CONTRACTS §7) and `:recorder` (§6). Route `Live`,
`FeatureFlags.live = true` (Home tile "Live-Ansicht").

| File | Contents |
| --- | --- |
| `LivePlayer.kt` | `LiveStream` (URL constants), `LivePlayer` seam + `PlayerEvent` + `StreamError`, `ExoLivePlayer` (Media3), `LiveFrameSource` (Phase 4 hook), Hilt `LiveModule` |
| `LiveViewModel.kt` | `LiveViewModel` (stream lifecycle, retry, commands, late replies, screenshot), `StreamState`, `StreamError.reason`, `CommandUi`, `commandMessage()`, `replyText()` |
| `Screenshot.kt` | `saveScreenshot()` – the screenshot folder contract and the gallery copy |
| `LiveScreen.kt` | `liveGraph()`, `LiveScreen()` (slots), full screen, state views |

## Pipeline

```
RecorderConnectionManager.state is Ready  &&  screen started (ON_START … ON_STOP)
        │ yes                                          │ no → player.stop(), StreamState.Off
        ▼
ExoPlayer ── RtspMediaSource(forceUseRtpTcp, socketFactory = Ready.network.socketFactory)
        │      rtsp://192.168.42.1/ch1/sub/av_stream  (port 554, no credentials, 8 s RTSP timeout)
        ▼
MediaCodec video renderer (audio track type disabled) ── TextureView (AndroidView, letterboxed, keepScreenOn)
        │                                                     │
        │ PlayerEvent: Buffering / Playing / Size / Failed    └─ getBitmap(w, h) → screenshot, LiveFrameSource
        ▼
LiveViewModel: Loading → Playing ⇄ Buffering; Failed → retry after 1.5 s → StreamState.Failed + "Erneut versuchen"
```

## Decisions

**Start only when Ready.** The original app opens the preview after the control session; the stream starts when
the connection state is `Ready` *and* the screen is started, and stops on `ON_STOP`, on leaving the screen, and when
the session ends (disconnect, Wi-Fi loss, heartbeat loss). The rotation into full screen is not a stop
(`Activity.isChangingConfigurations`); the player lives in the ViewModel, so it survives the rotation. Coming back
from the background starts again only after the manager's ON_START reconnect reaches `Ready`. While the video plays
or buffers, the surface keeps the screen on (`TextureView.keepScreenOn`): otherwise the display timeout would stop
the activity and, through the manager's ON_STOP, the session.

**Retry.** Each start gets exactly one automatic retry on a stream error, 1.5 s later (`RETRY_DELAY_MS`; the
pending retry is cancelled by any stop or new start), then `StreamState.Failed` with "Livebild nicht verfügbar", a
German reason, the raw code as a second line ("Code: ERROR_CODE_IO_UNSPECIFIED (2000)") and "Erneut versuchen" (a
fresh start with its own retry). Having played resets the retry, so a later drop is retried once again. Reasons by
Media3 `PlaybackException.errorCode` group: 2xxx (I/O, network) "Recorder liefert kein Livebild (RTSP, Port 554)",
3xxx (parsing) "Livebild des Recorders nicht lesbar (Datenformat)", 4xxx (decoder) "Videoformat auf diesem Handy
nicht abspielbar", the end of the stream (`STATE_ENDED` → `STREAM_ENDED`; the original app stops on stream closure
and has no retry loop of its own) "Der Recorder hat das Livebild beendet", anything else "Unerwarteter Fehler beim
Abspielen". Media3's RTSP client gives up after its default 8 s timeout per attempt.

**Transport.** `RtspMediaSource.Factory().setForceUseRtpTcp(true)` – TCP interleaved, as traced; RTP shares the
RTSP socket. No user/password (none in the traced setup; not a claim about the recorder's access checks).
`setSocketFactory(network.socketFactory)` with the network of the `Ready` state (the one the control socket is bound
to), so RTSP goes over the recorder Wi-Fi while mobile data serves everything else. `Ready.network` is null only in
debug simulator mode; then `LiveStream.SIMULATOR_URL` = `rtsp://10.0.2.2/ch1/sub/av_stream` with the default socket
factory (`:recorder:runSimulator` has no RTSP server, so that attempt always fails). Ready without a network outside
simulator mode starts nothing (never an unbound socket) and shows a connection problem, "Keine Verbindung über das
Dashcam-WLAN – bitte neu verbinden", with "Zur Verbindung". The URL is defined once (`LiveStream.URL`).

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
    fun frames(targetFps: Int /* 1..30 */, wanted: () -> Boolean = { true }): Flow<Frame>   // to.axolotl.cam.plates.Frame
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
wanted; `targetFps` outside 1..30 rejected.

Emulator walkthrough (API 36, `emulator-5556`) [SIM], before the review fixes: not connected → "Erst mit dem
Recorder verbinden / Nicht verbunden / Zur Verbindung"; simulator session Ready → RTSP to 10.0.2.2:554 fails twice
(`ERROR_CODE_IO_UNSPECIFIED`, initial + one retry) → error state with "Erneut versuchen" → two more attempts →
error again; Foto / 5er-Serie / Aufnahme reached the simulator with the exact bodies and showed "… Recorder meldet:
OK ohne Dateipfad (rval 0)" (the simulator's replies carry no `param`; the record countdown ended at once because
the reply came at once); full screen in landscape with hidden bars, left with Escape and with Back, no stream
restart from the rotation; background → session stop, foreground → reconnect → stream attempts again. After the
fixes: see the branch report. **Not exercised:** playback, keep-screen-on, the "Live" badge/phone clock over video,
buffering, screenshots from the surface and `frames()` from a real surface – all need an RTSP stream.

Hardware checklist (owner):
1. RTSP before vs. after the control session: does `ch1/sub` answer without a session (the app never tries)?
2. Concurrent clients: a second RTSP client (or the vendor app) while this one streams; does the recorder refuse,
   share or drop the first?
3. Stream errors: which Media3 error codes appear when the hotspot drops, the recorder restarts, or the session
   ends while streaming; does the recorder close the stream (`STREAM_ENDED`) on its own after some time; is the 8 s
   RTSP timeout and the 1.5 s retry delay right for the recorder?
4. Codec and resolution of `ch1/sub` (and frame rate, audio codec): log `PlayerEvent.Size`; check that hardware
   decoding renders correctly (else software-only selector); measure latency with the 500 ms start buffer.
5. Socket binding: stream works with mobile data on (sockets via `network.socketFactory`).
6. Photo / burst / record replies: `filePath`/`thmPath`/`fileTime` values and their time zone, how many 12292
   replies a burst produces and when (the timeout is 30 s), whether 12293 answers at start or at completion (a
   second 12293 reply would show as "weitere Antwort"), and the real manual clip length versus the 10 s countdown.
7. Whether photo/record commands interrupt or affect the live stream ("Nicht im Vorschaumodus", rval 304).
8. Screenshot from the real stream: size equals the stream size, colours correct, file + gallery copy.
9. A long live session: the screen stays on, the session survives (no display-timeout ON_STOP).
