# Live view (feature/live-view)

Package `to.axolotl.cam.live`. Builds on the connection manager (CONTRACTS §7) and `:recorder` (§6). Route `Live`,
`FeatureFlags.live = true` (Home tile "Live-Ansicht").

| File | Contents |
| --- | --- |
| `LivePlayer.kt` | `LiveStream` (URL constants), `LivePlayer` seam + `PlayerEvent`, `ExoLivePlayer` (Media3), `LiveFrameSource` (Phase 4 hook), Hilt `LiveModule` |
| `LiveViewModel.kt` | `LiveViewModel` (stream lifecycle, retry, commands, screenshot), `StreamState`, `CommandUi`, `commandMessage()` |
| `Screenshot.kt` | `saveScreenshot()` – the screenshot folder contract and the gallery copy |
| `LiveScreen.kt` | `liveGraph()`, `LiveScreen()` (slots), full screen, state views |

## Pipeline

```
RecorderConnectionManager.state == Ready  &&  screen started (ON_START … ON_STOP)
        │ yes                                          │ no → player.stop(), StreamState.Off
        ▼
ExoPlayer ── RtspMediaSource(forceUseRtpTcp, socketFactory = recorderNetwork.socketFactory)
        │      rtsp://192.168.42.1/ch1/sub/av_stream  (port 554, no credentials)
        ▼
MediaCodec video renderer (audio track type disabled) ── TextureView (AndroidView, letterboxed 16:9 box)
        │                                                     │
        │ PlayerEvent: Buffering / Playing / Size / Failed    └─ getBitmap(w, h) → screenshot, LiveFrameSource
        ▼
LiveViewModel: Loading → Playing ⇄ Buffering; Failed → one automatic retry → StreamState.Failed + "Erneut versuchen"
```

## Decisions

**Start only when Ready.** The original app opens the preview after the control session; the stream starts when
the connection state is `Ready` *and* the screen is started, and stops on `ON_STOP`, on leaving the screen, and when
the session ends (disconnect, Wi-Fi loss, heartbeat loss). The rotation into full screen is not a stop
(`Activity.isChangingConfigurations`); the player lives in the ViewModel, so it survives the rotation. Coming back
from the background starts again only after the manager's ON_START reconnect reaches `Ready`.

**Retry.** Each start gets exactly one automatic retry on a stream error (immediately, no back-off), then
`StreamState.Failed(code)` with "Livebild nicht verfügbar", the raw Media3 error code name and "Erneut versuchen"
(a fresh start with its own retry). Having played resets the retry, so a later drop is retried once again. The end
of the stream (`STATE_ENDED`, `STREAM_ENDED`) counts as an error: the original app stops on stream closure and has
no retry loop of its own.

**Transport.** `RtspMediaSource.Factory().setForceUseRtpTcp(true)` – TCP interleaved, as traced; RTP shares the
RTSP socket. No user/password (none in the traced setup; not a claim about the recorder's access checks).
`setSocketFactory(network.socketFactory)` with `RecorderConnectionManager.recorderNetwork`, so RTSP goes over the
recorder Wi-Fi while mobile data serves everything else. Without a bound network (outside simulator mode) nothing
is started: `Failed("RECORDER_WIFI_NOT_BOUND")`, never an unbound socket. The URL is defined once
(`LiveStream.URL`); debug simulator mode uses `LiveStream.SIMULATOR_URL` = `rtsp://10.0.2.2/ch1/sub/av_stream` with
the default socket factory (`:recorder:runSimulator` has no RTSP server, so that attempt always fails).

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

**Screen.** 16:9 black box (rounded), video letterboxed by the stream's display aspect (pixel aspect applied),
"Live" badge (dot + text) and "Handyzeit <localized date/time with seconds>" while playing or buffering – the
phone's clock, labelled as such; the recorder's time is not known here. State views inside the box: not connected
/ no Wi-Fi / wrong Wi-Fi / error → "Erst mit dem Recorder verbinden" + reason + "Zur Verbindung" (→ `Connection`);
Connecting / TCP / Negotiating with the dashcam's state texts and a spinner; opening, buffering, failed. The box
grows if a state message does not fit at large font sizes. Full screen (top-bar button, only when Ready): landscape
(`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`), system bars hidden (swipe shows them briefly); Back, Escape and the exit
button return; orientation `UNSPECIFIED` and the bars are restored only when full screen ends, not on the rotation
itself. On large screens Android may ignore the orientation request; the layout still fills the window.

**Recorder commands** ("Auf der SD-Karte der Dashcam", enabled only when Ready): Foto = 12292
`{"chanNo":1,"interval":3,"number":1}`, 5er-Serie = one 12292 with `number = 5` (a visible second button rather
than a hidden long-press), Aufnahme = 12293 `{"chanNo":1,"recType":1}`. Recording starts the traced 10 s
**UI countdown** ("Aufnahme · 7 s" + "Countdown der App … die Clip-Länge bestimmt der Recorder"); it sends no stop
command; its end or any reply (success, as traced; a failure too) ends the UI state. Timeouts: 10 s (client
default) for photo and record, 30 s for the burst (`ponytail:`, reply timing unverified). Replies are shown as
reported: "Foto – Recorder meldet: <filePath>" plus "Zeit laut Recorder: <fileTime>" (raw string), or "OK ohne
Dateipfad (rval 0)" when the reply carries no path. 12292 replies without a waiting request
(`RecorderNotification.Unmatched`, e.g. further burst replies or a reply after the timeout) are counted since the
last photo command: "2 weitere Antworten des Recorders (zuletzt: <path>)". Errors: recorder rvals with the
app-table meaning and the raw code ("5er-Serie – Recorder meldet Fehler: Foto fehlgeschlagen (Code 303)"); no
answer (timeout, connection lost) → "Ergebnis unbekannt – neu verbinden und prüfen (…)"; other local errors with
their local meaning and code. Timelapse is out of scope.

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

## Phase 4 hooks

```kotlin
// Singleton, inject anywhere
class LiveFrameSource @Inject constructor() {
    val videoSize: StateFlow<IntSize?>              // stream display size while a stream plays
    fun frames(targetFps: Int): Flow<Frame>         // to.axolotl.cam.plates.Frame(bitmap, timestampMs)
}

fun NavGraphBuilder.liveGraph(
    navController: NavController,
    extraControls: @Composable RowScope.() -> Unit = {},
    overlay: @Composable BoxScope.(videoRect: Rect) -> Unit = {},
    renderEffect: @Composable () -> RenderEffect? = { null },   // android.graphics.RenderEffect
)

@Composable fun LiveScreen(onBack, onConnect, extraControls = {}, overlay = {}, renderEffect: RenderEffect? = null, viewModel = hiltViewModel())
```

- `frames()` grabs only while collected and only while the stream plays (`Playing`; stays during `Buffering`);
  each `Frame` is a fresh software ARGB_8888 bitmap at most 1280 px wide (stream size if smaller), owned by the
  collector, `timestampMs` = phone wall clock. Grabbing runs on the main thread (`TextureView.getBitmap`); a slow
  collector delays the next grab (no buffering). Feed `LivePlateProcessor.submit()`.
- `overlay` is drawn over the 16:9 video box in normal and full-screen mode; `videoRect` is the letterboxed video
  in the overlay's coordinates (px). A detection box from a frame of width `w` maps with `videoRect.width / w`.
- `extraControls` sit to the right of the Screenshot button (one row, 32 dp gaps); the artboard's Plates (left)
  and Upscale (right) placement is not possible with one slot.
- `renderEffect` is applied to the video surface (`View.setRenderEffect`, API 31+); `LiveSharpen.effect(scale)`
  needs `scale = videoRect.width / videoSize.width`. The lambda form lets the owner compute it from its own state.
- `liveGraph` currently registers no slots (`AxoNavHost`: `liveGraph(navController)`); plates-ui / enhance-ui add
  their arguments there.

## Simulated vs needs the real recorder

Verified [SIM] / offline (`LiveViewModelTest`, 12 Robolectric tests with a fake player and `RecorderSimulator`):
start only when Ready and started; nothing in the background; stop on background and disconnect, restart on
foreground; one automatic retry then `Failed`, manual retry, retry again after a successful play; exact 12292/12293
bodies; reply text with path/time and without path; countdown 10 → 7 → end without any 12294, timeout → "Ergebnis
unbekannt", immediate reply ends it; two unmatched burst replies counted and reset; rval 303/311 with app meaning
and raw code; screenshot files, JSON and EXIF; `NoFrame` / saved events; frames only while playing.

Emulator walkthrough (API 36, `emulator-5556`) [SIM]: not connected → "Erst mit dem Recorder verbinden / Nicht
verbunden / Zur Verbindung"; simulator session Ready → RTSP to 10.0.2.2:554 fails twice
(`ERROR_CODE_IO_UNSPECIFIED`, initial + one retry) → error state with "Erneut versuchen" → two more attempts →
error again; Foto / 5er-Serie / Aufnahme reached the simulator with the exact bodies and showed "… Recorder meldet:
OK ohne Dateipfad (rval 0)" (the simulator's replies carry no `param`; the record countdown ended at once because
the reply came at once); full screen in landscape with hidden bars, left with Escape and with Back, no stream
restart from the rotation; background → session stop, foreground → reconnect → stream attempts again.
**Not exercised:** playback, the "Live" badge/phone clock over video, buffering, screenshots from the surface and
`frames()` from a real surface – all need an RTSP stream.

Hardware checklist (owner):
1. RTSP before vs. after the control session: does `ch1/sub` answer without a session (the app never tries)?
2. Concurrent clients: a second RTSP client (or the vendor app) while this one streams; does the recorder refuse,
   share or drop the first?
3. Stream errors: which Media3 error codes appear when the hotspot drops, the recorder restarts, or the session
   ends while streaming; does the recorder close the stream (`STREAM_ENDED`) on its own after some time?
4. Codec and resolution of `ch1/sub` (and frame rate, audio codec): log `PlayerEvent.Size`; check that hardware
   decoding renders correctly (else software-only selector); measure latency with the 500 ms start buffer.
5. Socket binding: stream works with mobile data on (sockets via `network.socketFactory`).
6. Photo / burst / record replies: `filePath`/`thmPath`/`fileTime` values and their time zone, how many 12292
   replies a burst produces and when (the timeout is 30 s), whether 12293 answers at start or at completion, and
   the real manual clip length versus the 10 s UI countdown.
7. Whether photo/record commands interrupt or affect the live stream ("Nicht im Vorschaumodus", rval 304).
8. Screenshot from the real stream: size equals the stream size, colours correct, file + gallery copy.
