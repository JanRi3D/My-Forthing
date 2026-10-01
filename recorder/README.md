# :recorder – Forthing U-Tour control protocol

Pure Kotlin/JVM module (`to.axolotl.cam.recorder`, no Android dependencies) implementing the recorder's TCP
control channel (192.168.42.1:7878) as documented in `docs/protocol/Forthing-U-Tour-protocol-report.md`.
Live view (RTSP 554) and media downloads (HTTP 80) are not part of this module.

```
FrameCodec      "FAAB" + u32 seq + u32 length + body; streaming Decoder (fragments, combined frames, resync)
SessionCrypto   RSA/PKCS#1 unwrap of aescode, AES-128-ECB zero padding, Base64, GB2312 decode
RecorderCommand msgIds and param shapes; SettingsPatch / WifiParam / OsdInfo / TimeLapse; RecorderValues
Replies         RecorderReply/Result/Error + typed parsers (device, storage, settings, files, capture)
Capabilities    20480..20485 parsers
Notifications   16384 normal (typed info), 16385 events, Unmatched replies
ErrorCodes      app resource table, HAT table, local codes – kept separate
RecorderClient  session state machine, heartbeat, request matching, auto-ack
Transport       RecorderTransport(+Factory), SocketTransport
RecorderSimulator (testFixtures) in-memory recorder with the real wire format and fault injection
Diagnostics     RecorderDiagnostics events + Redactor
```

## Usage

```kotlin
val client = RecorderClient(
    transportFactory = { SocketTransport { network.socketFactory.createSocket() } }, // bound to the recorder Wi-Fi
    rsaPrivateKey = BuildConfig.DASHCAM_RSA_KEY.toByteArray(),
    scope = appScope,
    clock = Clock.SYSTEM,
    diagnostics = { event -> capture(event) }, // already redacted
)
client.start()                                            // state: Connecting → TcpConnected → Negotiating → Ready | Failed
val info = client.request(RecorderCommand.GetDeviceInfo, ::parseDeviceInfo)
client.request(RecorderCommand.SetSettings(SettingsPatch(chanNo = 1, soundSwitch = RecorderValues.ON)), { })
client.request(RecorderCommand.TakePhoto(number = 5), ::parseCaptureResult, timeoutMs = 30_000) // slow command
client.notifications.collect { /* Normal, Event (already acknowledged), Unmatched */ }
client.stop()
```

Only `SessionState.Ready` means commands work. `TcpConnected` is a socket without a session (the original app
navigated on that alone). Recorder settings count as confirmed only after `rval == 0` and a readback.

## RSA private key (supplied by the app, never in this repository)

The session key arrives RSA-encrypted (`aescode`). The matching private key is embedded in the vendor APK; it
must **not** be committed, logged or copied into this module, its tests or fixtures.

- Put it into the git-ignored `local.properties` as `dashcam.rsaKey=<Base64>`; the app exposes it as
  `BuildConfig.DASHCAM_RSA_KEY` and passes its bytes to `RecorderClient`.
- Accepted: PKCS#8 or PKCS#1 (`BEGIN RSA PRIVATE KEY`), as DER bytes, plain Base64 or PEM text.
- A missing or invalid key does not crash: `start()` ends in `Failed(code = -203)` without connecting.
- Tests and `RecorderSimulator` generate their own 1024-bit key pair. A real recorder will not accept it.

## Evidence status (labels as in the protocol report)

**App path** – implemented as the original app does it:
- Framing, sequence reset (session start = seq 1), Base64 length in the header, sequence-matched replies.
- Session start `{"token":0,"msgId":1,"param":{"clientType":1}}`; reply fields tokenNum, version, productType,
  aescode, timeOut; non-empty key required.
- Timing: 3 s connect, one immediate retry; 5 s session deadline with both traced monitor effects (local result
  4096 and disconnect; the SDK's two same-deadline monitors are one timer here);
  keepalive (3) ~4 s after key initialisation then every 4 ticks; only a successful keepalive resets the counter;
  disconnect when it exceeds 10 (~11 s); a received msgId 2 disconnects first. The returned `timeOut` is parsed
  but unused, as traced.
- Commands 1–4101, 8192, 12288, 12289, 12292–12294, 20481 with the app's values (chanNo 1, driver 1, pageNum 50,
  interval 3, number 1/5, recType 1/2); no `param` where the app sends none; Wi-Fi dialog patch without chanNo.
- Settings value mappings in `RecorderValues`, including G-sensor 1 = Hoch, 2 = Mittel, 3 = Niedrig (UI labels;
  the SDK enum names are reversed).
- 16384 notifications (not acknowledged) and 16385 events with the automatic acknowledgement
  `{"rval":0,"msgId":16385,"token":…}`, fresh sequence, sent before dispatch even without listeners.
- App error resource meanings (`ErrorCodes.APP_MEANINGS`).

**SDK-only** – defined in the library, no app call found; implemented for completeness, unverified:
- StopSession (2) as a command (the app just disconnects; `stop()` does the same), capabilities 20480, 20482–20485,
  extra settings fields (recordSwitch, manualVideoTime, distCorr, privateInfo, picCycle, faceDetect,
  timeLapseVideo, activeUploadEnabled, sdDriverId, frameRate), HAT error names.
- Not implemented: time setting 12295, firmware upgrade 8193, timelapse DTOs 8194/39317/39318, UDP discovery
  7879, private video port 9800+. Add when a feature needs them.

**Offline test** – SDK defects reproduced by the report; deliberately not reproduced here (regression-tested):
- Read-all Wi-Fi mode inversion: `WifiParam.mode` is kept verbatim and resubmitted unchanged.
- OSD `int[]` serialisation (`"[I@…"`): `osdContent` is a real JSON array; a malformed echo reads as empty.
- Network capability flag overwrite: `wifiPwdSetting` / `wifiSsidSetting` parsed independently.
- Image capability frame rates read into the aspect-ratio list: `frameRate` parsed as frame rates.

**Needs recorder verification** – nothing here proves the physical recorder accepts any of this:
- Whether the recorder accepts the session protocol at all; actual token/key; AES path against firmware;
  non-ASCII text (outgoing UTF-8, incoming GB2312) and payloads over the vendor's 1024-byte native limit.
- Sequence numbers of unsolicited messages; order of notifications vs replies; acknowledgement requirements.
- Burst photo reply multiplicity (extra replies surface as `RecorderNotification.Unmatched`), interval unit,
  manual clip duration, meaning of the 12293 reply (start or completion).
- Real status/error values, `recStatus` meaning (raw int kept), pathType/updateDir/upgrade status values.
- File-list ordering, cursor inclusivity, duplicates, page size limits, size/space units, timezone of times.
- Firmware reaction to the corrected Wi-Fi mode and OSD array; G-sensor physical direction; capability replies.
- Behaviour with a second client, after Wi-Fi password changes, and whether RTSP needs a control session.

## Deliberate differences from the SDK

- Plaintext vs ciphertext is detected by the body (`{` cannot occur in Base64) instead of "sequence == 1".
- Every `start()` gets the two connect attempts (the SDK never reset its counter after a success).
- Replies are matched once; later replies with the same sequence become `Unmatched` instead of being dropped.
- Per-request timeout, default 10 s (`timeoutMs` on `send`/`request`, local code -205); none is traced. Without
  it a command the recorder ignores would wait forever, because successful keepalives keep the session alive.
  A late reply then arrives as `Unmatched`.
- `send(StopSession)` closes the session afterwards even if the recorder never answers (the heartbeat is
  already stopped at that point, as traced).
- Cancelling the client's `scope` closes the socket and fails pending requests like `stop()`.
- Anything after the last `}` of a decrypted body (non-NUL padding) is ignored instead of dropping the frame.
- Local codes added: -201 heartbeat lost, -202 disconnected, -203 session key invalid, -204 unparsable reply,
  -205 request timeout.
- `DeleteFiles` rejects an empty list (the SDK would send a body without token).

## Redaction

`Redactor.redact` masks `token`, `tokenNum`, `aescode`, `passwd`, `key`, `access_token`, `refresh_token`,
`idToken`. Diagnostic events, `toString()` of replies, results, errors, `SessionState.Ready`, `WifiParam`,
`RecorderSettings` and `GlobalSettings`, and all exception messages are redacted. The module itself never logs or persists anything. `rawJson` of a normal
reply stays unredacted for parsing (e.g. the read-all reply contains the Wi-Fi password): log it only through
`Redactor`. The plaintext session reply never leaves the client except redacted.

## Tests

`./gradlew :recorder:test` – JUnit 4, kotlinx-coroutines-test (virtual time), Truth. Fixtures in
`src/test/resources/fixtures/` are the report's illustrative JSON with fictional values. Client tests run
against `RecorderSimulator` and are simulated results ([SIM]), not recorder observations.

`RecorderSimulator` is a Gradle test fixture (`src/testFixtures/kotlin`, `java-test-fixtures` plugin), so it is
not in the production artifact. Other modules use it with
`testImplementation(testFixtures(project(":recorder")))`:

```kotlin
val sim = RecorderSimulator()                       // own RSA key pair, session key, token 123
val client = RecorderClient({ sim }, sim.privateKeyPkcs8, backgroundScope, { testScheduler.currentTime })
sim.replies[4098] = """{"rval":0,"msgId":4098,"param":{"productModel":"EXAMPLE"}}"""
sim.silentMsgIds += 3                               // heartbeat loss; also rvalOverrides, fragmentSize, delays, failWrites
```
