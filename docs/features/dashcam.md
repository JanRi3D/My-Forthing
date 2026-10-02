# Dashcam connection (feature/recorder-connection)

Package `me.ri3d.dashcam.dashcam`. Builds on `:recorder` (CONTRACTS §6) and implements CONTRACTS §7.

| File | Contents |
| --- | --- |
| `RecorderConnectionManager.kt` | `RecorderConnectionState`, `RecorderConnectionManager`, `RecorderConnectionManagerImpl`, `RecorderNotBoundException`, `boundSocketTransport` |
| `RecorderFacts.kt` | `RecorderFact` (Room, table `recorder_fact`), `RecorderFactDao`, `CachedFacts`, `Cached<T>` |
| `RecorderWifi.kt` | Wi-Fi seam (`requestNetwork`, SSID, mobile data), `LatestNetworkCallback`, runtime permission list, FORTHING hint |
| `DashcamModule.kt` | Hilt singleton + `ProcessLifecycleOwner` observer |
| `DashcamText.kt` | Error / SD status / recStatus presentation (raw code always shown), outcome-unknown rule |
| `ConnectionScreen.kt` | Connection screen, Home card, `ConnectionViewModel` |
| `SdCardScreen.kt` | SD card screen (4099, sdStatus, format) |
| `RecorderSettingsSection.kt` | `DashcamSettingsSection` for core Settings (slot), change → readback logic, Wi-Fi password dialog |
| `DiagnosticsScreen.kt` | Diagnostics capture + share (`DiagnosticsFileProvider`) |
| `DashcamGraph.kt` | Routes `Connection`, `SdCard`, `Diagnostics` |

## Decisions

**One client, one state.** The manager owns a single `RecorderClient` for the app's lifetime. Its published
state is recomputed from an overlay (the phases before a session: `Disconnected`, `Connecting` while waiting for
Wi-Fi, `NoWifi`, `WrongWifi`, configuration `Error`) and the client's `SessionState`, so `TcpConnected`,
`Negotiating` and `Ready` stay distinct exactly as the client reports them. `Ready` is published as soon as the
session is up; 4098 is queried right after and the state is republished with `info`, then 4099 once per session
(`storage`, shared by the Home card and the Connection screen).

**Contract additions** (§7 shapes unchanged otherwise): state `Disconnected` (idle: before the first connect and
after `disconnect()`); interface members `sdStatus`, `recStatus`, `storage`, `ssid`, `refreshSsid()`,
`mobileDataEnabled()`, `diagnosticLog()`, `capabilities(group)` (20480–20485 reply of the current session, 3 s
timeout, cached until the session ends, failures not cached), `note(topic, message)` / `notes()` (app lines for the
Diagnose export, last 50 per topic, redacted), debug-only `simulator: StateFlow<Boolean>` + `setSimulator()`;
`connect(ignoreSsid = false)`; `request(…, timeoutMs)` passes the client's per-request timeout through (formatting
needs more than 10 s); `httpClient()` throws `RecorderNotBoundException` (an `IOException`) while no recorder Wi-Fi
is bound.

**Wi-Fi binding.** `ConnectivityManager.requestNetwork(TRANSPORT_WIFI, without NET_CAPABILITY_INTERNET)` — the
hotspot has no internet. The callback (`LatestNetworkCallback`) emits the newest network and null only when that
one is lost, so a make-before-break switch (Android 12+: `onAvailable(new)` before `onLost(old)`) is not a loss.
The connect attempt hands the network it checked to the transport factory; every control socket is created
unconnected and bound with `network.bindSocket(socket)` before `connect`; if binding throws, the socket is closed.
Without a network (outside simulator mode) the attempt fails like an unreachable recorder (-101): nothing ever
connects unbound, so mobile data is never used to reach the recorder. OkHttp uses `network.socketFactory` and
resolves via `network.getAllByName`; `httpClient()` throws `RecorderNotBoundException` while unbound.
`bindProcessToNetwork` is never called, so mobile data keeps serving everything else. The request is kept until
`disconnect()` / app stop, and waits 5 s for a network before reporting `NoWifi`.

**SSID hint.** Read with `WifiManager.getConnectionInfo()` (deprecated since API 31 but still reports the SSID to
apps with precise location; verified on the API 36 emulator: `AndroidWifi`). Like the original helper only a
quoted SSID counts; `<unknown ssid>` → unknown. A name not starting with FORTHING (case-insensitive) gives
`WrongWifi` with "Trotzdem verbinden"; an unknown name (permission denied, location off) connects without asking.

**Lifecycle (chosen).** The session lives while the app is in the foreground. `ProcessLifecycleOwner` ON_STOP
(Android dispatches it ~700 ms after the last activity stops, so rotation does not disconnect) stops the session
and releases the Wi-Fi request; ON_START reconnects only after a session was Ready and the user did not disconnect.
Connect starts, stops (user, ON_STOP) and network loss run under one mutex, so a stop in flight cannot tear down a
newer attempt; a second `connect()` joins the running attempt. Network loss stops the session (`NoWifi`); heartbeat
loss / socket close become `Error(retry = true)`. No automatic reconnect loop (as in the original app); the
Connection screen tries again on resume, like the original's `onResume`, unless the user pressed "Trennen". A
connect attempt runs in the manager's scope, so leaving a screen does not cancel it; `disconnect()` cancels a pending
attempt. Stops clear sdStatus, recStatus and storage.

**Missing key.** Blank `BuildConfig.DASHCAM_RSA_KEY` → `Error(-203, retry = false)` with "Konfiguration fehlt …"
before any Wi-Fi or socket work (the Wi-Fi and TCP steps stay "ausstehend"); an unreadable key → "Konfiguration
fehlerhaft …"; a key that does not unwrap the recorder's `aescode` → "Sitzungsschlüssel ungültig …" (no retry).
The manager's key check at construction is the runtime check of the configured key; tests use generated keys.

**Errors.** Recorder rvals use only `ErrorCodes.APP_MEANINGS` (the original app's resources), local codes the
local list; the SDK's AE enum is never used. The raw code is always shown: "Keine SD-Karte (Code 201)".

**Settings.** Exactly the report's table: videoResolution (0/1), normalVideoTime (1/3/5), soundSwitch, wdrSwitch,
gSensorSensitivity (1 Hoch / 2 Mittel / 3 Niedrig, raw ints), parkMonitor, eventRecCycle, osd.enableOSD,
poweroffDelay (0/10/60), Wi-Fi password. A change sends 8192 with only that field plus `chanNo = 1`; on
`rval == 0` the app re-reads **4097** (the reply shape of 4096 is not established) and shows "bestätigt" only if
the readback equals the sent value, otherwise "gesendet: X, zurückgelesen: Y"; a failed readback after rval 0
shows "angenommen, aber nicht zurückgelesen". The overlay switch resubmits `osdContent` exactly as read; if it was
not read as a number array (e.g. the SDK's `"[I@…"`), the switch is disabled instead of sending something else.
Switch rows whose readback is neither 1 nor 0 are shown raw and disabled.

The Wi-Fi dialog changes only the password, like the vendor dialog: SSID read-only, the whole object read back is
resubmitted without `chanNo`, `ssid` and `frequency` untouched. **`mode`** (hardware 2026-10-02: 4097 reads
`wifi.mode = 1` while capability 20483 lists `wifi.mode = [0]`, so resending the read value might switch the hotspot
to a mode the recorder does not support): when 20483 lists exactly one mode, that one is sent, otherwise the mode as
read (`wifiToSend(read, password, supportedModes)`; 20483 is loaded through `capabilities(NETWORK)` before the
settings appear, so the dialog and the request agree). The dialog shows "Gesendet werden … Modus 0 und Band 0",
"Modus 0 ist laut Recorder der einzige unterstützte (gelesen: 1)" when it differs, and the warning "Nur
ausprobieren, wenn du an die Reset-Taste der Dashcam kommst – die Hersteller-App sendet den Modus hier vertauscht"
(the vendor app resubmits the inverted value; neither has been tried on the recorder). Password rule "8–16 Zeichen, Buchstaben und
Ziffern": 8–16 printable ASCII characters 0x21–0x7E with at least one A–Z/a–z and one 0–9, typed twice, with a
show-password toggle; an invalid password is never sent. Unless the recorder clearly refused it, the app asks to
rejoin the Wi-Fi.

A change, format or reset that ends without a recorder answer (-205 timeout, -202/-201 connection lost) shows
"Ergebnis unbekannt – neu verbinden und prüfen" instead of "nicht übernommen". Factory reset (12289) sits behind
the danger confirmation and shows "vom Recorder angenommen (rval 0)" (there is no readback). Readback fields not
shown are listed raw under "Weitere Werte (unbestätigt)" (secrets masked). No "Neustart". Everything is disabled
unless `Ready`.

**SD card.** 4099 values are shown raw with "laut Recorder". **Units:** when `totalSpace` is 1,000–2,000,000 (a
1 GB–2 TB card in MB) `totalSpace` and `available` are read as MB and shown as "≈ 116 GB (Recorder meldet 119255,
als MB gedeutet)" (1 GB = 1024 MB, whole GB from 10 GB, one decimal below: "≈ 0,7 GB …"), the Home card as "≈ 0,7 GB
frei laut Recorder"; otherwise raw only (`storageInMb`, `storageValue`, `gigabytes` in `DashcamText.kt`). Hardware
2026-10-02: `totalSpace 119255`, `available 693` then 285 with a 128 GB card – consistent with MB.
`residualLife` / `healthStatus` (hardware: the string "unknow") always stay raw. The latest `sdStatus` notification
maps 0–8 to the report's labels, other values stay raw. Formatting (12288, 60 s timeout, danger confirmation)
only when `Ready`; rval 0 reads "vom Recorder angenommen". The "Dateien auf Karte" categories of the artboard are
**omitted**: counts would need a file listing (feature/media); the route `SdFiles` stays for that feature.

**Home card.** State line, `recStatus` as text only (raw, the SDK reading marked "Deutung unbestätigt", only if one
arrived; no recording dot), SSID ("Simulator" from the manager's flag), SD free as "<available> frei laut
Recorder" from the session's 4099. Tap → Connection. The Connection screen adds the mobile-data hint on `NoWifi` /
-101 when mobile data is on.

**Diagnostics.** One tap, read-only: Wi-Fi facts (SSID, bound network, mobile data, simulator), connection state
(and its error), session fields and the session reply (kept even after the frame log rolled over), raw replies of
4098, 4097, 4099, 20481 and best effort 20480/20482–20485 (5 s each; errors and -205 timeouts kept), the app's
`notes` (`rtsp`: every live-view attempt with URL, transport and result, the RTSP proxy's steps and the raw DESCRIBE
captures; `http`: every recorder HTTP request with method, path, Range, status, Content-Type, Content-Length or the
exception, stalls, finished and failed downloads – last 50 each), `rtsp.describe` (while Ready on the recorder Wi-Fi:
the raw OPTIONS/DESCRIBE exchange of the live view, `docs/features/live.md`), then the last 400 frame-log events.
The recorder HTTP client (`httpClient()`) serves one request at a time with a 90 s read timeout
(`docs/features/media.md`). Every JSON text passes the core `redact()` (token, tokenNum, aescode, passwd, password, key, …)
on top of the module's own redaction; a test checks the export against the real secrets of a simulated session.
Shared as `cacheDir/diagnostics/myforthing-diagnose-<time>.json` through `DiagnosticsFileProvider` (own `FileProvider`
subclass with the paths in its manifest meta-data, authority `${applicationId}.dashcam.files`, so other features'
providers do not clash in the manifest).

**Cached facts (feature/cache).** Every successful reply of 4098, 4097, 4099 and the capabilities 20481 / 20483 / 20484
that goes through `request()` (also the manager's own 4098/4099 and `capabilities()`) is stored in `recorder_fact`
(DB 4) under the session's `productSN` (`""` if 4098 names none; replies before that 4098 wait for it) with its read
time, as the whole reply text through `redact()` – the 4097 Wi-Fi password is never stored. `cachedFacts` delivers the
parsed values of the recorder read most recently (same parsers as live replies). The Home card (device + SD free, also
without a session), the Verbindung device line, the SD-Karte values and the Einstellungen section (readback values,
device group) show them with "zuletzt gelesen <Zeit>" until this session's live value arrives. Settings stay editable
only with a session **and** this session's 4097 readback; the cached ones are disabled ("Zuletzt gelesene Werte (…).
Ändern geht erst, wenn …").

**Cleartext.** `res/xml/network_security_config.xml`: cleartext only for `192.168.42.1`; the base config forbids it.

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Sockets, network callbacks, mobile-data state |
| `CHANGE_NETWORK_STATE` | `requestNetwork` for the recorder Wi-Fi |
| `ACCESS_WIFI_STATE` | `WifiManager.getConnectionInfo()` |
| `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` (runtime) | Android reveals the SSID only with precise location |

The Connection screen asks with a German rationale; when denied it shows "unbekanntes WLAN (Berechtigung fehlt)",
links to the app settings and still connects. `NEARBY_WIFI_DEVICES` is not used: it does not reveal the SSID.

## RSA key

`local.properties` (git-ignored) holds `dashcam.rsaKey=<single-line Base64 PKCS#8>`, taken from the vendor
evidence (`StartSessionBO`'s `RSAUtils.decryptText` literal, `\n` escapes removed). The key is never committed,
logged or printed, and no test loads it: the manager validates it at runtime (configuration error state).

## Debug simulator

1. `./gradlew :recorder:runSimulator` — listens on `127.0.0.1:7878`; the emulator reaches it as `10.0.2.2:7878`.
   It reads `dashcam.rsaKey` from `local.properties` itself and derives the public key, so the debug app works
   unchanged. Without a key it generates a throwaway one and prints how to put it into `local.properties`
   (remove it again before using a real recorder).
2. Debug app → Home card → Verbindung → "Entwickler" → **Simulator (10.0.2.2:7878)**. Skips the Wi-Fi check and
   socket binding (the only unbound connection the app makes); connects at once.
3. The simulator answers with fictional data, applies 8192 changes to its 4097 state (so readback confirms),
   frees the card on 12288, restores defaults on 12289, sends sdStatus 2, recStatus 1 and one manual-record event
   after the session starts, and never answers 20485 (exercises -205 in Diagnose). Requests are printed redacted.

## Simulated vs needs the real recorder

Verified only against `RecorderSimulator` / the TCP simulator [SIM] or the emulator:
- State transitions Connecting → TcpConnected → Negotiating → Ready, session timeout 4096, heartbeat loss, network
  loss, make-before-break switch, duplicate connect joining, unbound HTTP refused, missing/invalid key, disconnect
  during a pending attempt, diagnostics export without token/aescode/passwd/session key (unit tests).
- Settings change → 4097 readback → confirmed / mismatch / rejected (rval 208) / unconfirmed (readback timeout) /
  unknown (8192 timeout); Wi-Fi password-only resubmission with ssid / mode 1 / frequency 1 kept and no chanNo
  (mode 0 instead when 20483 lists only `[0]`),
  password mismatch, invalid passwords never sent; OSD resubmission; reset accepted / refused (unit tests and
  emulator walkthrough).
- SD status mapping incl. unknown values; error presentation; 4099 load, format 12288 ok / rval 209 (unit tests).
- Real Wi-Fi path on the emulator (API 36): network request and binding, "unbekanntes WLAN (Berechtigung fehlt)"
  before the permission, SSID "AndroidWifi" after it → WrongWifi with "Trotzdem verbinden"; the TCP connect to
  192.168.42.1 fails with -101 after the traced two attempts (no recorder).
- Emulator walkthrough against `:recorder:runSimulator` [SIM]: Connecting → Ready with "Gerät: SIMULATOR",
  Home card (simulator, recStatus 1, "12034 frei laut Recorder"), SD card (raw 4099 values, sdStatus "Normal
  (Status 2)"), loop length 3 → 5 "bestätigt", sound toggle "Aus · bestätigt", Wi-Fi dialog → rejoin prompt,
  "Weitere Werte (unbestätigt)", Diagnose capture (passwd / token / aescode masked, 20485 kept as -205) and the
  share sheet with the JSON file. App backgrounded by another emulator user → ON_STOP disconnect observed in the
  simulator log.

**Verified on hardware 2026-10-02** (owner's Diagnose export; recorder `AE-DC2013-LQ2`, fw `SX5G-3776510A_A` built
2024-03-21; phone API 37 on `FORTHING-A267451`, network bound, mobile data off):
- Wi-Fi request and socket binding to the FORTHING hotspot (with mobile data off).
- FAAB framing; session handshake with the vendor key (RSA unwrap of `aescode`), session reply `version 1.1.3`,
  `productType 0`, `timeOut 10`; AES-128-ECB traffic in both directions; keepalives answered.
- 4098, 4097, 4099 and all capability queries 20480–20485 answered (none timed out).
- Unsolicited notifications arrive with frame sequence **0xFFFFFFFF** (read as -1) and are dispatched:
  `sdStatus {driver 1, status 2}`, `recStatus {chanNo 0, status 1}`, `fileNew`, `fileDel` (regression test
  `notificationWithSequenceMinusOne_isDispatched`).
- Recorder clock: 4098 `dateTime` ≈ 2.5 min ahead of the phone (recorder times are labelled "laut Recorder").
- Values: 4097 `withChan[0].chanNo = 0`, `wifi.mode 1` (20483 `wifi.mode [0]`), `videoResolution 0`,
  `osdContent [2,3,4,5,6]` (a real array), `gSensorSensitivity 3`, `normalVideoTime 5`, `poweroffDelay 60`,
  `parkMonitor 1`, `eventRecCycle 1`, `soundSwitch 1`, `wdrSwitch 1`; 4099 `totalSpace 119255`, `available 693`
  then 285, `residualLife` / `healthStatus` "unknow".

Needs the physical recorder (owner checklist; the Diagnose export captures most of it read-only):
1. Socket binding with mobile data **on** (verified only with it off).
2. Heartbeat loss timing (≈ 11 s) on a real hotspot drop.
3. ~~Session reply~~ (verified above).
4. Remaining 4098 fields (productSN, hwVersion, mcuFwVersion) as shown on the device screen.
5. 4097 fields the screen does not show; **`chanNo`**: the recorder reads back `chanNo 0` while 8192 patches send
   `chanNo 1` as traced (`RecorderValues.CHANNEL_FRONT`) – whether a change is applied (E in the checklist) decides
   if the patch must use the channel number read back.
6. 4099 units: MB is the working reading (see SD card); confirm against the card size after a format (G2).
7. Each 8192 change: rval, whether the 4097 readback reflects it, and how long the recorder needs.
8. Wi-Fi password change: whether the recorder accepts the resubmitted ssid / mode 0 (from 20483) / frequency,
   which password lengths and characters the firmware takes, when it restarts its hotspot, and the rejoin flow.
9. Format (12288): duration (timeout 60 s), rval, readback of 4099 afterwards.
10. Factory reset (12289): what is reset (Wi-Fi?), whether the connection drops.
11. Meaning of `recStatus` 1 (assumed "normal recording") and `sdStatus` values other than 2.
12. ~~Capability replies~~ (verified above; 20485 content not yet evaluated).
13. ON_STOP/ON_START disconnect/reconnect with a real hotspot; a Wi-Fi roam/switch during a session.
14. Error codes the recorder really sends and whether the app-table meanings fit.
