# Dashcam connection (feature/recorder-connection)

Package `me.ri3d.cam.dashcam`. Builds on `:recorder` (CONTRACTS §6) and implements CONTRACTS §7.

| File | Contents |
| --- | --- |
| `RecorderConnectionManager.kt` | `RecorderConnectionState`, `RecorderConnectionManager`, `RecorderConnectionManagerImpl`, `RecorderNotBoundException`, `boundSocketTransport` |
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
`mobileDataEnabled()`, `diagnosticLog()`, debug-only `simulator: StateFlow<Boolean>` + `setSimulator()`;
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
resubmitted without `chanNo`, `ssid`, `mode` and `frequency` untouched. Password rule "8–16 Zeichen, Buchstaben und
Ziffern": 8–16 printable ASCII characters 0x21–0x7E with at least one A–Z/a–z and one 0–9, typed twice, with a
show-password toggle; an invalid password is never sent. Unless the recorder clearly refused it, the app asks to
rejoin the Wi-Fi.

A change, format or reset that ends without a recorder answer (-205 timeout, -202/-201 connection lost) shows
"Ergebnis unbekannt – neu verbinden und prüfen" instead of "nicht übernommen". Factory reset (12289) sits behind
the danger confirmation and shows "vom Recorder angenommen (rval 0)" (there is no readback). Readback fields not
shown are listed raw under "Weitere Werte (unbestätigt)" (secrets masked). No "Neustart". Everything is disabled
unless `Ready`.

**SD card.** 4099 values are shown raw with "laut Recorder" (units unknown). The latest `sdStatus` notification
maps 0–8 to the report's labels, other values stay raw. Formatting (12288, 60 s timeout, danger confirmation)
only when `Ready`; rval 0 reads "vom Recorder angenommen". The "Dateien auf Karte" categories of the artboard are
**omitted**: counts would need a file listing (feature/media); the route `SdFiles` stays for that feature.

**Home card.** State line, `recStatus` as text only (raw, the SDK reading marked "Deutung unbestätigt", only if one
arrived; no recording dot), SSID ("Simulator" from the manager's flag), SD free as "<available> frei laut
Recorder" from the session's 4099. Tap → Connection. The Connection screen adds the mobile-data hint on `NoWifi` /
-101 when mobile data is on.

**Diagnostics.** One tap, read-only: Wi-Fi facts (SSID, bound network, mobile data, simulator), connection state
(and its error), session fields and the session reply (kept even after the frame log rolled over), raw replies of
4098, 4097, 4099, 20481 and best effort 20480/20482–20485 (5 s each; errors and -205 timeouts kept), then the last
400 frame-log events. Every JSON text passes the core `redact()` (token, tokenNum, aescode, passwd, password, key, …)
on top of the module's own redaction; a test checks the export against the real secrets of a simulated session.
Shared as `cacheDir/diagnostics/myforthing-diagnose-<time>.json` through `DiagnosticsFileProvider` (own `FileProvider`
subclass with the paths in its manifest meta-data, authority `${applicationId}.dashcam.files`, so other features'
providers do not clash in the manifest).

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
  unknown (8192 timeout); Wi-Fi password-only resubmission with ssid / mode 1 / frequency 1 kept and no chanNo,
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

Needs the physical recorder (owner checklist; the Diagnose export captures most of it read-only):
1. Wi-Fi request without INTERNET capability matches the FORTHING hotspot; socket binding works with mobile data on.
2. Session handshake with the vendor key (aescode unwrap), token, AES traffic, heartbeat timing.
3. Real `version`, `productType`, `timeOut` of the session reply.
4. 4098 field values (productModel, productSN, fwVersion, hwVersion, mcuFwVersion).
5. 4097 shape and values, including fields the screen does not show; whether `osdContent` reads as an array.
6. 4099 values and their units (totalSpace, available, residualLife, healthStatus).
7. Each 8192 change: rval, whether the 4097 readback reflects it, and how long the recorder needs.
8. Wi-Fi password change: whether the recorder accepts the resubmitted ssid/mode/frequency, which password lengths
   and characters the firmware takes, when it restarts its hotspot, and the rejoin flow afterwards.
9. Format (12288): duration (timeout 60 s), rval, readback of 4099 afterwards.
10. Factory reset (12289): what is reset (Wi-Fi?), whether the connection drops.
11. Whether `sdStatus` / `recStatus` notifications arrive at all, their values, and the meaning of recStatus.
12. Capability replies 20480–20485 (SDK-only queries) and whether the recorder answers them.
13. ON_STOP/ON_START disconnect/reconnect with a real hotspot; a Wi-Fi roam/switch during a session.
14. Error codes the recorder really sends and whether the app-table meanings fit.
