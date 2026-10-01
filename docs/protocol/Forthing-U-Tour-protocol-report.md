# Forthing 4 U-Tour: How the app talks to the dashcam

Complete investigation and protocol reference

Supplied Android app 3.2.15 | 1 October 2026

**The app communicates directly with the recorder over local Wi-Fi.** Your car does not need mobile data for this identified recorder connection. Control, live video and saved files use three separate services on the same recorder address.

```text
Phone app <--- local FORTHING Wi-Fi ---> Recorder: 192.168.42.1
    |--- TCP 7878: settings and control
    |--- RTSP 554: live video
    `--- HTTP 80: saved media and thumbnails
```

| Purpose | Address used by the app |
| --- | --- |
| Settings and control | TCP 192.168.42.1:7878 |
| Live view | rtsp://192.168.42.1/ch1/sub/av_stream |
| Saved media | http://192.168.42.1 + recorder-returned file paths |

**What is complete:** the app's expected local protocol and the inspected connection, preview, settings and media call paths are mapped. Several SDK problems were reproduced offline using the original recovered code.

**What remains unknown:** the exact recorder fitted to your car, its firmware responses, access checks and the physical effects of commands. No connection to your car or an app backend was made.

One file: this Markdown report includes the full 734-entry evidence ZIP as Base64 in the [collapsed appendix](#embedded-evidence-archive). The main report consolidates the findings; the archive preserves the underlying source, detailed notes and test output.

## Reading guide and contents

This report answers how this particular app expects to operate the recorder. It does not turn generic SDK features into claims about equipment fitted to every U-Tour. It supersedes the two earlier short reports as the consolidated account of the investigation.

| Evidence label | Meaning |
| --- | --- |
| App path | A value or operation is linked to an actual app screen or presenter in the recovered code. |
| SDK-only | The library defines it, but an application call or physical support was not established. |
| Offline test | Original recovered Java classes were executed with synthetic inputs on Android 16 / API 36. |
| Needs recorder verification | The answer depends on a local connection to the physical recorder. |

- [Wi-Fi connection and phone routing](#wi-fi-connection-and-phone-routing)
- [Account, vehicle and Bluetooth boundaries](#account-vehicle-and-bluetooth-boundaries)
- [Live view and the video pipeline](#live-view-and-the-video-pipeline)
- [TCP frame format](#tcp-frame-format)
- [Session handshake and encryption](#session-handshake-and-encryption)
- [Connection lifecycle and timing](#connection-lifecycle-and-timing)
- [Command reference](#command-reference)
- [Settings and value mappings](#settings-and-value-mappings)
- [Settings caveats that matter](#settings-caveats-that-matter)
- [Photos, bursts and recording](#photos-bursts-and-recording)
- [Files, playback and downloads](#files-playback-and-downloads)
- [Normal recorder notifications](#normal-recorder-notifications)
- [Events and automatic acknowledgements](#events-and-automatic-acknowledgements)
- [Errors and status interpretation](#errors-and-status-interpretation)
- [Capabilities, vehicle data and firmware](#capabilities-vehicle-data-and-firmware)
- [Offline execution results](#offline-execution-results)
- [Remaining questions and local verification](#remaining-questions-and-local-verification)
- [Method, provenance and embedded evidence](#method-provenance-and-embedded-evidence)
- [Embedded evidence archive](#embedded-evidence-archive)

All sample tokens, paths, timestamps, sizes and session fixtures are illustrative. JSON examples show the logical body before the SDK encrypts and frames it. They are not recorded traffic or directly usable raw socket commands.

Evidence paths such as `analysis/...`, `recovered-source/...` and `decoded/...` refer to files inside the embedded ZIP, not files required beside this Markdown report.

## Wi-Fi connection and phone routing

### The connection expected by this app

The connection screen tells the driver to switch ignition/ACC on, remain near the car and join **FORTHING-xxxxxx** in Android Wi-Fi settings. It displays **12345678** as the factory hotspot password. The suffix is described as factory-assigned letters/numbers; the actual name and password on your car may differ or have been changed.

The app opens Android's Wi-Fi settings. It does not create the hotspot or submit that displayed password itself. The manufacturer's U-Tour manual, printed page 136, describes the same local hotspot workflow. [M1]

### What the screen actually checks

The confirm button accepts any non-null SSID whose uppercase form begins with **FORTHING**. It does not validate a hyphen, six-character suffix, BSSID, gateway, VIN or recorder serial. Its SSID helper returns only a quoted Android SSID with the outer quotes removed; an unquoted or unknown value becomes an empty string.

The screen's **onResume** path still tries the fixed recorder address even when this name check fails. A disabled or failing confirm path and a resume-triggered TCP attempt are therefore different behaviors.

### Why phone mobile data can matter

The app explicitly advises turning off the **phone's mobile data** and retrying if joining the recorder Wi-Fi still does not connect. This is consistent with a routing problem on the phone; it is not a requirement for a cellular connection in the car.

The control client opens an ordinary Netty TCP socket. A direct-call scan across all eleven recovered app/SDK DEX images found no calls to bindProcessToNetwork, setProcessDefaultNetwork, requestNetwork or bindSocket. Native, reflected or downloaded behavior is outside that negative result.

### Permissions and Android behavior

The app targets Android API 31 and has minimum API 24. Its manifest declares network, Wi-Fi and location permissions. The inspected recorder connection screen does not make a dedicated runtime permission request before its SSID query. The actual SSID visible on your phone therefore remains dependent on permission and Android state. Album access has separate storage/camera permission requests; this is not proof that live RTSP requires phone-camera access.

**Evidence:** TachographView; WifiUtils; DeviceCommunication; AndroidManifest.xml; network_security_config.xml. Detailed line references: analysis/further-connection-flow.md in the embedded ZIP.

## Account, vehicle and Bluetooth boundaries

### A local protocol inside a broader account-based app

The recorder's initial handshake contains clientType, a zero token and the command ID. It contains **no account name, VIN or Wi-Fi password**. However, normal navigation to the vehicle home screen checks stored login and vehicle eligibility before showing the recorder menu.

VehicleHomePresenter requires LoginManager.isLogin() and hasNetVehicle(). The latter accepts a current vehicle marked isSpecCar, or the telematics/authorization predicate below. These are record fields, not a live cellular-signal test.

```text
isSpecCar OR (isEquippedWithTelematics AND (
  (authType == "AUTH" AND internetStatus == "OPENED"
   AND internetType == "SUCCEED")
  OR "ACCREDIT".endsWith(authType)
))
```

The unusual endsWith expression is preserved from the code. Once the eligible car page is reached, the recorder menu is added without a separate model-specific endpoint choice. Its recorder route supplies no VIN or credentials; both connection methods use 192.168.42.1:7878.

### Offline startup is not fully established

Login state is persisted, vehicle lists are cached per account UID, and a cached fallback exists after prior setup. However, the current-vehicle singleton starts empty and normal startup fetches the account vehicle list. SplashActivity.onCreate remains a protected native method. These facts do not prove first-time login or every cold-start path works offline.

It is unknown whether this app's account service accepts your particular U-Tour VIN or export configuration. A menu gate can prevent entry to a locally served feature even though the car needs no mobile data for that feature. No account action, alternate component launch or gate bypass was performed; the recorder activities are non-exported.

### Other car features are separate

Sky Eye, T-box/cloud functions and VIN/account-linked Bluetooth vehicle logic are present elsewhere in the app. Bluetooth state/provisioning is queried with account and vehicle information and cached. This is distinct from the recorder's SSID and fixed-IP connection. It neither identifies digital-key hardware in your car nor provides a phone-to-CAN control protocol.

**Evidence:** VehicleHomePresenter; VehicleBean; VehicleHomeView; CarMainView/Presenter; LoginManager; VehicleUtil; MainPresenter; SplashActivity. Full traces: analysis/further-connection-flow.md.

## Live view and the video pipeline

```text
rtsp://192.168.42.1/ch1/sub/av_stream
```

The preview screen passes this exact hardcoded URL when its TextureView surface becomes available. With no explicit port, the native RTSP parser defaults to **554**. The selected defaults use **TCP-interleaved RTSP media transport**.

| Stage | App-selected behavior |
| --- | --- |
| PreviewView | Creates HikVideoModel and HikPreviewPlayer; sets the fixed URL and display surface. |
| HikPreviewPlayer | Default protocol 0 selects openNPCClient, not the private-protocol branch. |
| NPClient | Passes the NUL-terminated URL to native NPCCreate and opens it using NPC_PRO_AUTO. |
| Native libraries | libHIK_NPCClient.so / libNPClient.so recognize rtsp://, default to port 554 and TCP interleaving. |
| PlayM4 | Receives stream headers and packets, decodes and renders on Android TextureView. |

### Sound, authentication and capability responses

The app sets hardware decoding to false and live sound playback to false. The microphone recording setting is separate: muted live view does not mean the stored recordings lack audio.

No RTSP username or password is supplied by the traced preview setup. A basic-capability query reads returned RTSP URL/auth fields, but its callback only logs them; the returned URL does not replace the constant.

**Unverified:** whether the recorder accepts RTSP before a control session exists, whether it applies other access checks, and whether a second client can stream concurrently. Absence of app-supplied RTSP credentials is not a demonstrated camera authentication vulnerability.

### Other transport branches are not the active preview

The SDK contains a private video protocol on port 9800 + channel/index, initially 9801, but this app's live-preview setup does not select it. UDP discovery on 7879 exists in the SDK; the identified connection methods instead use the fixed IP directly.

Preview stops in onStop and on stream closure. No automatic preview retry loop was found in the inspected path. Recorded MP4 playback uses the app's general VideoActivity, separately from this live pipeline.

**Evidence:** PreviewView; PreviewPresenter; HikPreviewPlayer; NPClient; native offsets and call chains in analysis/runtime-camera-findings.md and historical-analysis/native-camera-findings.md. The latter is supporting native evidence, not the current authority for endpoints.

## TCP frame format

Settings and camera actions use Netty NioSocketChannel to **192.168.42.1:7878**. They are not HTTP REST requests. Each message has a binary header followed by a body.

| Offset | Bytes | Meaning |
| --- | --- | --- |
| 0 | 4 | ASCII FAAB: hex 46 41 41 42 |
| 4 | 4 | Request sequence number, 32-bit field, big-endian |
| 8 | 4 | Body length, 32-bit big-endian |
| 12 | N | Plain JSON at session start; Base64-encoded encrypted body after session setup |

### First request example

```json
{"token":0,"msgId":1,"param":{"clientType":1}}
```

This compact ASCII/UTF-8 example is 46 bytes, so its first 12 bytes are:

```text
46 41 41 42  00 00 00 01  00 00 00 2e
FAAB         sequence 1   body length 46
```

The outgoing sequence increments per request. Starting a session resets the counter to zero before the first send, which increments it to one. Once encrypted, the header length counts the **Base64 body**, not the unencoded ciphertext or original JSON length.

### Receiving and matching messages

TCP is a byte stream. The SDK accumulates fragments and recognizes FAAB markers; a socket read need not contain one complete frame. Request callbacks are correlated using the frame sequence. The SDK adds msgNo to callback JSON internally; it is not a required field in the outgoing JSON body.

A normal request body carries token and msgId and, where needed, param. Command helpers that take no parameters send token/msgId without adding param. The normal response handler treats rval=0 as success; a missing rval becomes -1 and enters the failure path.

### Misleading constant to ignore

DVR_BASE_URL_PORT contains http://192.168.42.1:7878, but it has no identified use beyond its declaration in the recovered camera source. The actual connection and framing code establish TCP control; that unused string does not establish HTTP on port 7878.

**Evidence:** DeviceCommunication; DashcamApi; SessionApi; BaseApi/BaseDTO; ParseUtil; DvrConstants. Header and payload evidence is preserved in recovered-source/ and the selected Dalvik/native evidence directories.

## Session handshake and encryption

| Step | What the original app does |
| --- | --- |
| 1. Start | Send plaintext command 1 with token 0 and param.clientType=1 (PHONE_APP). |
| 2. Parse reply | For rval=0, read param.tokenNum, version, productType, aescode and timeOut. |
| 3. Unwrap key | Base64-decode aescode and decrypt it using the APK-embedded RSA private key with RSA/NONE/PKCS1Padding. |
| 4. Store state | Treat the decrypted text as a hexadecimal AES session key; store it together with the returned token. A nonempty key is required for session success. |
| 5. Later commands | Serialize JSON with the current token; convert the hex key to bytes; encrypt natively; Base64-encode without line breaks and add the FAAB frame. |

### Cryptographic and encoding details

Native block-loop analysis supports **AES-128 ECB with zero padding**: independent 16-byte blocks rather than PKCS#7 padding. This is a source/native-analysis result; the native AES transport was not exercised against a camera. The selected SecureStrategy.NONE installs no TLS handler; payload encryption is a separate layer.

The normal outbound path uses native string/UTF handling. Decrypted incoming text is decoded as GB2312 with trailing zero removal. Non-ASCII fields therefore deserve care in an independent implementation. The native send wrapper has 1024-byte limits; no claim of unlimited payload support is made.

### What the offline RSA test proved

A synthetic response was constructed using a known test key and token 123. The original recovered StartSessionBO decoded it and recovered exactly that key text and token. The embedded RSA modulus is **1024 bits**. This validates local key unwrapping, not a real pairing, network handshake, AES exchange or camera authorization bypass.

The embedded private key is preserved in the source evidence inside the embedded evidence appendix. No actual recorder session token or camera-generated AES key was obtained. The Wi-Fi password is a different credential from this session key.

### Illustrative post-session request

```json
{"token":123,"msgId":8192,
 "param":{"chanNo":1,"soundSwitch":1}}
```

Here 123 is a sample token only. This logical body still needs the encryption and framing above; it is not a bare-JSON TCP request.

**Evidence:** StartSessionDTO/BO; RSAUtils; AESUtils; SessionApi; native AES evidence; analysis/protocol-probe-results.txt and ProtocolProbe.java.

## Connection lifecycle and timing

**TCP-connected and camera-session-ready are different states.** DashcamApi tells the UI that the TCP connection succeeded before it starts session negotiation. TachographView immediately navigates into the recorder screen on that callback, so entering that screen does not prove a usable token/key exists.

| Stage | Observed client behavior |
| --- | --- |
| TCP connect | 3-second connect timeout. |
| Fresh failure | One immediate retry, two attempts total, when the retry counter starts at zero; no backoff delay. |
| Retry nuance | Success does not reset that counter in the inspected branch. Two attempts is not a universal guarantee for later sessions. |
| Session startup | Two independent 5-second monitors run concurrently. One reports synthetic result 4096; the other disconnects. This is not a combined 10-second timeout. |
| Keepalive | Command 3 begins around four seconds after key initialization, then approximately every four one-second iterations. |
| Heartbeat replies | Only successful keepalive callbacks reset beatTime. The failure callback is empty. |
| Heartbeat loss | beatTime > 10 triggers disconnect, roughly 11 seconds depending on scheduling. |
| Returned timeOut | Parsed from the session reply; no direct call to its getter was found. Client timers above are hardcoded. |
| Stop session | Command 2 stops heartbeat; receiving command 2 causes disconnect before normal result handling. |
| Exit recorder | Back/destroy paths directly disconnect and clear outstanding request/notification state. |

### Reconnect and background behavior

The main-view disconnect callbacks only log. No general automatic reconnect loop was found in this path. Main-view completion, returning to the connection screen and its confirm button can start another attempt. The SDK ignores duplicate connect requests while connected or connecting.

Live preview separately stops on onStop or stream closure. Wi-Fi rejoining, recovery after password changes, background/resume behavior and Android routing changes remain physical-phone/recorder questions.

**Evidence:** DeviceCommunication; DashcamApi; SessionApi; TachographView; DrivingDrecorderMainView/Activity. Exact timing references: analysis/further-protocol-events.md, section 6.

## Command reference

Every post-session request includes the current token. The table describes the recovered serializer, with application usage distinguished from library-only entry points. Replies normally use rval=0 for success.

| msgId | Purpose | param / usage |
| --- | --- | --- |
| 1 | Start session | clientType:1; token:0; plaintext startup |
| 2 / 3 | Stop / keepalive | Session helpers; no param |
| 4096 | Read named setting | chanNo:1, type:"fieldName" |
| 4097 | Read all settings | No param; app settings initialization |
| 4098 | Device information | No param; app settings initialization |
| 4099 | Storage information | driver:1 |
| 4100 | List files | driver:1, type, lastFileName, pageNum:50 |
| 4101 | Delete files | fileList:[device paths] |
| 8192 | Change settings | Non-null fields; usually chanNo:1; Wi-Fi dialog omits channel |
| 12288 | Format storage | driver:1; app action |
| 12289 | Factory reset | No param; app action |
| 12292 | Single / burst photo | chanNo:1, interval:3, number:1 or 5 |
| 12293 | Manual / timelapse start | chanNo:1, recType:1 or 2 |
| 12294 | Timelapse stop | chanNo:1, recType:2 |
| 12295 | Set time | timeSetting:string; SDK-only, no direct application call found |
| 20480 | All capabilities | No param; SDK definition |
| 20481 | Basic capabilities | No param; app preview queries it |
| 20482 / 20483 | Image / network capabilities | No param; no direct application calls found |
| 20484 / 20485 | Storage / intelligent capabilities | No param; definitions; intelligent query has no direct application call found |
| 8193 | Firmware upgrade request | len, type; no direct application call found |
| 8194 | Timelapse parameters | sampleFrame, videoFrame, playFrame; unused extra DTO |
| 39317 / 39318 | Dedicated timelapse / manual | Unused extra DTOs; actual app buttons use 12293/12294 instead |
| 16384 / 16385 | Normal / event notification | Incoming messages; see notification chapters |

No destructive or state-changing operation in this table was sent to a recorder. The table is a protocol reference, not a record of tests performed on the car.

**Evidence:** GettingApi; ControlApi; SettingApi; CapabilityApi; DTO classes; app presenters; complete direct-call scan in analysis/further-protocol-xrefs.json.

## Settings and value mappings

The settings page reads current settings (4097), device information (4098) and storage information (4099) from the local recorder. Read-all expects param.withoutChan as an object and param.withChan as an array of channel objects.

Changes use 8192 and include only non-null fields. These UI controls normally include chanNo=1. The screen waits for successful responses before retaining selected/toggled state.

| UI setting | JSON field | Offered / sent values |
| --- | --- | --- |
| Resolution | videoResolution | 0 = 1080P; 1 = 720P |
| Loop clip length | normalVideoTime | 1, 3 or 5 minutes |
| Shutdown delay | poweroffDelay | 0, 10 or 60 seconds |
| WDR | wdrSwitch | 1 = on; 0 = off |
| Event overwrite | eventRecCycle | 1 = on; 0 = off |
| Parking monitoring | parkMonitor | 1 = on; 0 = off |
| Microphone recording | soundSwitch | 1 = on; 0 = off |
| G-sensor sensitivity | gSensorSensitivity | UI High sends 1; Medium 2; Low 3. SDK names disagree. |
| Driving-info overlay | osd | enableOSD 1/0 and osdContent; serializer defect described next |
| Wi-Fi settings | wifi | Nested mode, ssid, passwd, frequency; separate dialog omits chanNo |

### Read and change examples

```text
{"token":123,"msgId":4096,
 "param":{"chanNo":1,"type":"soundSwitch"}}

{"token":123,"msgId":8192,
 "param":{"chanNo":1,"normalVideoTime":3}}
```

### Readback fields and library-only settings

Storage parsing includes totalSpace, available, residualLife and healthStatus; actual values were not obtained. Global settings include Wi-Fi, loop duration, parking and shutdown delay; channel settings include image/audio/OSD properties.

Additional serializer fields include recordSwitch, manualVideoTime, distCorr, privateInfo, picCycle, faceDetect, timeLapseVideo, activeUploadEnabled, sdDriverId and frameRate. Their presence does not prove a UI path or firmware support. The SDK also names 30-second shutdown delay and G-sensor off=0, but the inspected app buttons do not offer those choices.

**Evidence:** SettingPresenter/View; SetSettingDTO; GetAllCurrentSettingsBO; AllParamWithChanBO/WithoutChanBO; setting enums. Current mappings and UI resource cross-checks: analysis/runtime-settings-findings.md.

## Settings caveats that matter

### Wi-Fi band and password submission

Frequency 0 means 2.4 GHz and 1 means 5 GHz in the SDK. Selecting a band only changes the app's in-memory WiFiParam; it does not send a setting immediately. The password dialog mutates passwd and sends the whole WiFiParam object, including the selected band. On success it asks the user to reconnect Wi-Fi.

```json
{"token":123,"msgId":8192,"param":{"wifi":{
 "mode":0,"ssid":"EXAMPLE","passwd":"Example123",
 "frequency":0}}}
```

This is an illustrative shape, not a recommended mode or known recorder configuration. This dialog omits chanNo. Validation checks at least eight characters plus a letter and digit; the displayed message says 8-16. These checks are separate from the factory password shown on the connection screen.

### Confirmed read-all Wi-Fi mode inversion

The SDK names AP=0 and STA=1. Direct WiFiParam parsing preserves both numbers, but the read-all parser turns incoming mode 0 into 1 and incoming 1 into 0. This was reproduced by executing the original recovered classes.

The app passes that exact read-all WiFiParam reference into the password dialog, then resubmits it without normalizing mode. A password change can therefore send the opposite mode number from the one read. Whether the physical recorder ignores, rejects or applies that change remains unknown.

### Confirmed overlay serializer problem

OSDInfo constructs an integer array for overlay indexes 0-7 but stores the raw Java int[] rather than the JSON array. On the tested Android runtime, it produces this form, also retained by the complete settings serializer:

```json
{"enableOSD":1,"osdContent":"[I@92e7998"}
```

The suffix is a changing object identity, not overlay contents. Actual firmware response to this malformed-looking field was not tested.

### G-sensor labels conflict

The screen's High button sends 1, which the SDK enum calls LOW; its Low button sends 3, called HIGH by the SDK. Medium sends 2 consistently. UI resources and Dalvik instructions agree on the mismatch. Neither naming convention establishes the real physical threshold direction.

**Evidence:** SettingView; SettingPasswordDialog; AllParamWithoutChanBO; WiFiParam; OSDInfo; setting layout resource; analysis/protocol-probe-results.txt.

## Photos, bursts and recording

| Action | msgId | Actual app param |
| --- | --- | --- |
| Single photo | 12292 | {"chanNo":1,"interval":3,"number":1} |
| Five-photo burst | 12292 | {"chanNo":1,"interval":3,"number":5} |
| Manual recording | 12293 | {"chanNo":1,"recType":1} |
| Start timelapse | 12293 | {"chanNo":1,"recType":2} |
| Stop timelapse | 12294 | {"chanNo":1,"recType":2} |

The selected channel is FRONT=1. ALL=0 and BACK=2 exist in the SDK but are not chosen here. RecordType names NORMAL=0, MANUAL=1 and TIME_LAPSE=2; the inspected recording buttons select 1 or 2.

| Reply | Fields parsed within param |
| --- | --- |
| Photo, 12292 | chanNo, filePath, thmPath, fileTime |
| Recording, 12293 | filePath, thmPath, fileTime |
| Stop, 12294 | chanNo |

### What the timers and callbacks do not prove

The photo interval value is exactly 3; its unit is not established. A burst is one outgoing request with number=5, not five requests. The UI decrements a completion counter on each success or failure callback, and the SDK retains the sequence callback. Actual reply multiplicity, timing and sequence reuse remain unobserved.

Manual recording starts a ten-second **UI countdown**. Its expiry updates UI state and sends no stop command. The start request contains no duration, so ten seconds is not a proven clip length. A successful reply also ends the UI state and refreshes the card list; whether that reply means start or completion depends on recorder behavior.

Timelapse uses an elapsed-time display until stopped. These phone timers are not recorder-time telemetry.

### Separate SDK-only schemas

Dedicated commands 39317 and 39318 exist but are not the selected button paths. The former serializes chanNo/cmd/recTime; the latter chanNo. Another unused DTO, 8194, serializes sampleFrame/videoFrame/playFrame. The separate timeLapseVideo settings object uses sampleInterval/playFrameRate/totalRecordTime. These names do not establish units or accepted ranges.

Time command 12295 simply wraps a timeSetting string. No direct application call was found; automatic clock synchronization and accepted time syntax/timezone are unproven.

**Evidence:** PreviewView/Presenter; TakePhotoDTO/BO; StartRecordDTO/BO; StopRecordDTO/BO; CorrTimeDTO; extra timelapse DTOs; analysis/further-protocol-events.md.

## Files, playback and downloads

### Cursor-based browsing

```json
{"token":123,"msgId":4100,"param":{
 "driver":1,"type":0,"lastFileName":"","pageNum":50}}
```

The app uses driver=1 and pageNum=50 as a batch size, not a sequential page index. A refreshed list starts with an empty lastFileName; the next request copies the final returned entry's exact fileName as its cursor. Types are 0=normal video, 1=event video and 2=user data. AP_VIDEO=3 exists only as an additional SDK enum choice in this path.

### Expected response shape

```json
{"msgId":4100,"rval":0,"param":{
 "totalFileNum":71,"totalFileSize":123456,
 "fileList":[{
   "fileName":"/example/clip.mp4",
   "fileThm":"/example/clip.jpg",
   "fileTime":"2026-10-01 12:00:00"
 }]}}
```

All data above is illustrative. totalFileNum and totalFileSize are parsed as Java integers; the size unit is unknown. Ordering, cursor inclusivity, duplicate handling and the maximum supported page size require a recorder response. An empty/null result ends this presenter's current processing with an empty UI result.

### URLs and playback

The app prefixes both fileName and fileThm with **http://192.168.42.1**, default port 80. The file-list response supplies the path; no fixed media directory or filename pattern was established. The manifest/network configuration permits cleartext traffic, consistent with this HTTP path.

Recorded MP4s open through the general VideoActivity. DownloadUtils saves a timestamped MP4 under dflq_file and adds it to the phone album on success. Live view remains the separate RTSP connection.

### Important spelling and time distinctions

File listings use fileName/fileThm/fileTime. Photo and recording replies use filePath/thmPath/fileTime. Event notifications use filePath/fileThm/time. Those similar names must not be interchanged.

The app parses fileTime using yyyy-MM-dd HH:mm:ss and groups results by date. This does not identify a timezone or the accepted format of the separate time-setting command. Deletion strips the HTTP base before sending command 4101 with fileList:[device paths]. No files were deleted.

**Evidence:** MemoryCardPresenter; GetFileListDTO/BO; FileInfoBO; DeleteFileListDTO; BigImageOrVideoAdapter/Presenter; DownloadUtils; analysis/further-protocol-events.md.

## Normal recorder notifications

The recorder can send unsolicited messages on the same control socket. **msgId=16384** uses an object param containing type and info. The inspected dispatcher parses it independently of request callbacks and sends no acknowledgement in this branch.

```json
{"msgId":16384,"param":{
 "type":"sdStatus","info":{"driver":1,"status":2}}}
```

This is a parser-compatible example, not a measured card state. The notification branch requires msgId but does not itself validate rval, token or a request correlation entry.

| param.type | Parsed info fields |
| --- | --- |
| heart_beat_start / heart_beat_stop | None |
| disconnectShutdown | None |
| gsensorErr / sensorErr | None |
| sdCap | driver, totalSpace |
| fileNew | driver, fileType, fileName, fileThm, fileTime, pathType |
| fileDel | driver, fileType, fileName, pathType |
| upgradeStatus | status (string), error (integer) |
| sdStatus | driver, status |
| recStatus | chanNo, status (raw integer) |
| updateFileList | updateDir |

### SD notification status values

| Value | Meaning | Value | Meaning |
| --- | --- | --- | --- |
| 0 | No card | 5 | Slow card |
| 1 | Card exception | 6 | Image area full |
| 2 | Normal | 7 | Unsupported capacity |
| 3 | Event-record area full | 8 | Unsupported filesystem |
| 4 | Insufficient free space | Other | Unknown maps to null enum |

This SD status enum is distinct from command failure codes. For recStatus, the parser retains an integer. A separate enum names 0=not recording, 1=normal recording and 2=event recording, but the parser does not apply it; applying those labels to real notifications remains an interpretation to verify.

Do not substitute the generic DashcamNotificationConstants list: it contains names such as newFile, recordStatus, SDInsert and wifiRestart that this active parser does not recognize. Exact pathType, updateDir and upgrade status values are not established here.

**Evidence:** DashcamApi; NormalNotificationBO and nested parsers; SDStatusType; RecordStatusType; analysis/further-protocol-events.md, section 3.

## Events and automatic acknowledgements

**msgId=16385** expects param to be an array. Each entry is parsed using type, filePath, fileThm and time. The active branch calls EventNotificationBO.parse(array), not its alternative resolve method for a different object schema.

```json
{"msgId":16385,"param":[{
 "type":6,"filePath":"/example/manual.mp4",
 "fileThm":"/example/manual.jpg",
 "time":"2026-10-01 12:00:00"}]}
```

| Event type | SDK meaning |
| --- | --- |
| 1 | Capture |
| 2 | Device wake-up |
| 3 | Parking monitoring |
| 4 | Crash recording |
| 5 | Crash image |
| 6 | Manual recording |
| Other | Unknown value maps to null enum |

### Acknowledgement behavior

Before dispatching the event, the SDK automatically sends a logical body of this form:

```json
{"rval":0,"msgId":16385,"token":123}
```

The acknowledgement uses the normal encrypted sendRequest path and a **new incremented outgoing frame sequence**. Its body does not echo the event array, param or incoming sequence. It is generated even when no application event listeners are registered.

### What the app visibly does is less certain

The complete direct-call scan found no invocation of DashcamApi.registerNotificationListener in the eleven app/SDK DEX images. Automatic SDK acknowledgement is established; visible application responses to these notifications are not. The photo/recording screen instead refreshes its card list in its own command callbacks.

No physical event was generated or captured. The frequency of notifications, requirements for acknowledgements, order relative to command replies and real values in these envelopes still depend on recorder firmware.

**Evidence:** EventInfoBO; EventNotificationBO; EventNotificationType; DashcamApi send/receive branches and their Dalvik instructions; analysis/further-protocol-xrefs.json.

## Errors and status interpretation

**rval=0 is success.** Missing rval becomes -1. The app's display resources use the mappings below; unknown codes fall back to generic connection-failed text. The resource entry at 0 is that fallback text, not a redefinition of protocol success.

| Result | App-facing meaning | Result | App-facing meaning |
| --- | --- | --- | --- |
| 1 | Unknown error | 2 | Operation failed; retry |
| 3 | Busy; retry | 4-7 | Abnormal information; retry |
| 101 | Reopen app | 102-106 | Operation failed; retry |
| 107 | No file for operation | 201 | No SD card |
| 202 | Card damaged | 203 | Card abnormal |
| 204 | Filesystem abnormal | 205-206 | Card abnormal |
| 207 | Card read-only | 208 | Insufficient space |
| 209 | Format failed | 210 | Card abnormal |
| 211 | Emergency-record storage full | 212 | Image storage full |
| 213 | Card initializing | 214 | Slow card |
| 301 | Reset failed | 302 | File-list failed |
| 303 | Capture failed | 304 | Not in preview state |
| 308 | Event recording active | 309 | Timelapse recording active |
| 310 | File deletion failed | 311 | Manual recording failed |
| 312 | Timelapse recording failed | 501-502 | Abnormal information; retry |

### Three different error sources must remain separate

The HAT constant table adds more specific names: 2 unsupported operation, 5 out of memory; 101 invalid token, 102 JSON package error, 103 sub-package timeout, 104 JSON syntax error, 105 invalid option, 106 invalid parameter and 107 invalid path. It calls 501 sensor error and 502 G-sensor error. Local -101/-102/-103 names mean connect/send/insufficient-parameter failure.

A generic AE enum conflicts with those names and the app resources: it calls 3 invalid token, 101 unknown card error, 102 no card, 201 Wi-Fi error, 301 request format error and 308 missing path. Do not silently use that enum to interpret this app's responses. Preserve the raw result and response JSON.

The five-second SessionApi startup monitor synthesizes local result **4096**. This is neither an observed camera error nor the meaning of outgoing command 4096. Likewise, SD notification status 2 means normal, while command failure 201 means no card.

The resource text for 204 advises formatting, but that is a description of the app's message. It is not evidence that formatting is needed or was performed.

**Evidence:** decoded/res/values/arrays.xml; PreviewPresenter.getErrorMsg; BaseApi; DashcamErrorCode; DashcamErrorCodesEnum; ErrorCodesUtil; SessionApi.

## Capabilities, vehicle data and firmware

| Query | Parsed capability group |
| --- | --- |
| 20480 | All capability sections |
| 20481 | Basic: totalSensor, rtspServer, supportCanComm, general flags and paths |
| 20482 | Per-channel image array: resolution, frame rate, aspect ratio, codecs, container, subRec, OSD, WDR, sound and distortion correction |
| 20483 | Network: type, wifi.mode[], wifiFrequency[], wifiPwdSetting, wifiSsidSetting |
| 20484 | Storage: SDStatus, sdDriver, clip durations, timelapse ranges, eventRecCycle, picCycle |
| 20485 | Intelligent-function flags: faceDetect, trafficLightDetect, frontCarReminding |

Enums can represent H264=0/H265=1 and 25fps=0/30fps=1. These are possible library values, not observed settings or support on your recorder. No direct application calls to image, network or intelligent capability queries were found; the active basic query only logs its first RTSP URL/auth.

### Device identity and possible vehicle integration

Device-info parsing exposes productModel, productSN, fwVersion, fwBuildDate, hwVersion, mcuFwVersion, paramVersion, verifyCode, dateTime and semifinishProductSN. Actual identification must come from the recorder; layout example text is not a hardware identification. productType only distinguishes HIKVISION=0 from OTHER=1.

supportCanComm and overlay indexes are clues that the SDK accommodates OEM recorders with vehicle signals. The overlay names are 0 TIME, 1 SPEED, 2 LIGHT, 3 BRAKE, 4 ACCELERATOR, 5 HIGH_BEAM, 6 LOW_BEAM and 7 LOGO.

This supports an **inference about SDK capability**, not proof that your unit reports supportCanComm=1. Recorder wiring, raw CAN identifiers, and any internal recorder-to-car communication remain unknown. No phone-side CAN protocol was recovered from this recorder path.

### Firmware update limits

The SDK recognizes updateFirmwarePath/downloadPath and command 8193 with len/type, plus upgradeStatus notifications. The complete direct-call scan found no calls to setUpgrade or either path getter. No selected upload workflow, actual firmware URL, image format or signature-verification process was recovered. No firmware update was attempted.

**Evidence:** CapabilityApi; GetBasic/Image/Network/Storage/IntelligentCapabilitiesBO; GetDeviceInfoBO; OSDInfoType; SetUpgradeDTO; analysis/further-capabilities-runtime.md.

## Offline execution results

A small test harness loaded the **original recovered DEX classes** via Android app_process; the SDK was not reimplemented for these tests. The dedicated Android 16 / API 36 emulator reported x86_64 and arm64-v8a, airplane mode enabled and an empty route table. The harness opened no sockets and called no connect/send/update methods.

| Test | Observed result | Scope |
| --- | --- | --- |
| OSD serialization | osdContent becomes "[I@92e7998", also inside full settings JSON | Active UI serializer; physical effect unknown |
| Direct Wi-Fi parse | mode 0 stays 0; mode 1 stays 1 | Control fixture for comparison |
| Read-all Wi-Fi parse | mode 0 becomes 1; mode 1 becomes 0 | Object is propagated into actual password dialog |
| Image frame-rate parser | frameRate:[0,1] yields empty frame-rate list and aspect ratios 16:9 / 4:3 | SDK-only path; no direct app query found |
| Network flags, pwd0/ssid1 | Parsed password=true, SSID=false | SSID flag overwrites password flag |
| Network flags, pwd1/ssid0 | Parsed password=false, SSID=false | SDK-only parser assignment error |
| Synthetic RSA session | Recovered token 123 and exact expected test key; 1024-bit RSA modulus | Key-unwrapping path; not a real handshake |

### Selected exact output

```text
PROBE image_frame_rates=[]
PROBE image_aspect_ratios_from_frameRate=
  [ASPECT_RATIO_16_9, ASPECT_RATIO_4_3]
PROBE network_input_pwd0_ssid1=parsedPwd=true,parsedSsid=false
PROBE network_input_pwd1_ssid0=parsedPwd=false,parsedSsid=false
PROBE session_token=123
PROBE session_key_matches_synthetic_fixture=true
PROBE rsa_bits=1024
PROBE complete=true
```

Fixture SSID LAB_ONLY, password LabOnly123, token 123 and LAB version values are fabricated test inputs, not credentials or identifying information from a car. The object-identity suffix in the OSD output is runtime-dependent.

The native AES network path, RTSP playback, HTTP downloads, actual camera settings and firmware reactions were **not** exercised. The dedicated emulator was stopped after analysis and the original APK remained unchanged.

**Evidence:** analysis/ProtocolProbe.java; analysis/protocol-probe-results.txt; analysis/protocol-probe-environment.txt; analysis/further-capabilities-runtime.md.

## Remaining questions and local verification

The app-side map is detailed enough to explain the intended connection and guide an independent implementation. It is not yet a recorder-tested client or a complete account of your car's internal electronics.

| Needs verification | What would resolve it |
| --- | --- |
| Fitted recorder and firmware | Read command 4098 locally for productModel, hardware, firmware and MCU versions. |
| Current hotspot and compatibility | Observe the actual Wi-Fi name/IP and establish whether this recorder accepts the recovered session protocol. |
| Actual supported settings | Read current settings and raw capability JSON. Do not infer support from every SDK enum. |
| RTSP prerequisites and access checks | Compare a local stream connection before and after a valid control session. |
| Multiple clients | Observe recorder behavior with an additional local client; no concurrency behavior was established. |
| Media paths and pagination | Read actual file-list pages and metadata; verify size units, order and cursor behavior. |
| Recording behavior | Observe real responses to establish interval units, burst callbacks and manual clip duration. |
| SDK defect consequences | Observe whether firmware ignores, rejects or applies the inverted Wi-Fi mode and malformed OSD content. |
| Offline app navigation | Test this account/phone's cached and cold-start behavior; native startup and account eligibility remain unresolved. |
| Car data / internal wiring | Physical recorder documentation or vehicle-side evidence is needed for CAN wiring/frames and feature support. |

### A sensible first local check

With the vehicle parked and ignition/ACC on as instructed by the app, join the actual recorder Wi-Fi. If the app fails after joining, its own guidance is to try with phone mobile data disabled. A read-only session/device-info/current-settings capture would resolve the most important remaining questions without changing configuration.

For an independent client, keep raw responses, keep the three transports separate, handle TCP fragmentation and unsolicited events, and wait for token/key negotiation before sending normal commands. Do not reproduce the observed serializer/parser mistakes blindly.

No Internet search can supply the current password, actual firmware state, serial number or live capability responses of a car with no mobile-data connection. These are local observations still to be made.

**Evidence:** This checklist follows the recovered app paths and the explicit limits of the local analysis. It is not a record of actions already carried out on the vehicle.

## Method, provenance and embedded evidence

**Input:** com.dflq.aifx_3.2.15 (1).apk; 83,957,144 bytes; package com.dflq.aifx; version 3.2.15 / code 40068. Examined 1 October 2026. Vehicle description and lack of car mobile data were supplied by you.

**APK SHA-256**

```text
24156f443dbd43898536298729e320421572de8f5a1135331296ac1c0e789e44
```

The protected APK initially exposed only four Java classes. The unmodified app was launched in an isolated offline emulator. Its bundled Stetho diagnostics exposed app-local process information. A QEMU memory snapshot and x86-64 page-table reconstruction recovered twelve distinct DEX images with 42,339 unique classes: eleven app/SDK images (42,259 classes) plus protection support (80).

All twelve passed stored Adler-32 and structural/index checks. SHA-1 header fields did not match the runtime images, so these are not claimed to be byte-identical pre-protection builds. Critical methods were checked in both Dalvik instructions and JADX reconstruction; decompiler warnings and unresolved native startup remain documented. Negative direct-call results exclude reflection, native indirection and downloaded code.

### Full evidence is inside this Markdown file

The embedded archive **Forthing-U-Tour-evidence.zip** contains 734 entries and is 1,310,390 bytes. Its complete bytes are stored as Base64 in the [appendix](#embedded-evidence-archive), with PowerShell extraction instructions. Reading the report needs no extraction or other files. The appendix remains collapsed in Markdown viewers that support HTML details elements.

**Embedded ZIP SHA-256**

```text
c5b4b1b660bf506fecf969c4e182907e363ff22e2a516cd4a8e3ec5b7de0b39d
```

| Archive location | Contents |
| --- | --- |
| analysis/ | Detailed current findings, test harness/output, inventories and direct-call cross-references |
| recovered-source/ | Recovered camera SDK, player and app recorder code |
| connection-source/ | Account, vehicle-menu, Wi-Fi helper and startup code |
| decoded/ and evidence folders | Selected resources, manifest, Dalvik/native supporting material |
| historical-analysis/ | Earlier investigation notes, including now-resolved hypotheses; use this report/current notes for final conclusions |
| inventory.json | Per-file hashes and provenance; archive integrity was checked |

The embedded archive excludes the original APK, entire DEX files and full emulator memory/heap/disks. It preserves the collected analysis evidence, not every intermediate execution artifact. No account, car endpoint or backend was contacted; no physical setting or firmware was changed. Attached contents were treated as evidence rather than instructions.

[M1] Manufacturer reference: [Forthing U-Tour owner manual](https://www.forthingmotor.com/uploads/U-tour.pdf), printed page 136. All specific endpoint, command and runtime-test claims above rely on the supplied APK, not on a different recorder model. The earlier comparison candidate 192.168.1.1 /liveRTSP/av4 is superseded.

## Embedded evidence archive

This appendix preserves the same complete evidence ZIP embedded in the PDF. It is encoded data, not additional report text. The extracted archive is 1,310,390 bytes and contains 734 entries; its SHA-256 is:

```text
c5b4b1b660bf506fecf969c4e182907e363ff22e2a516cd4a8e3ec5b7de0b39d
```

### Extracting the evidence on Windows

Save this Markdown file, then open PowerShell in the folder containing it and run the following. It checks the archive hash before writing a ZIP beside the report and stops if that output filename already exists. The decoded archive does not need to be extracted or opened to read the report.

```powershell
$reportPath = Join-Path (Get-Location) 'Forthing-U-Tour-complete-report.md'
$reportText = [System.IO.File]::ReadAllText($reportPath)
$pattern = '(?ms)^<!-- BEGIN EMBEDDED EVIDENCE ZIP BASE64 -->\r?\n(?<data>[A-Za-z0-9+/=\r\n]+)^<!-- END EMBEDDED EVIDENCE ZIP BASE64 -->'
$match = [regex]::Match($reportText, $pattern)
if (-not $match.Success) { throw 'Embedded archive not found.' }
$zipBytes = [Convert]::FromBase64String($match.Groups['data'].Value)
$hasher = [System.Security.Cryptography.SHA256]::Create()
$actualHash = [BitConverter]::ToString($hasher.ComputeHash($zipBytes)).Replace('-', '').ToLowerInvariant()
$hasher.Dispose()
if ($actualHash -ne 'c5b4b1b660bf506fecf969c4e182907e363ff22e2a516cd4a8e3ec5b7de0b39d') { throw 'Archive checksum mismatch.' }
$zipPath = Join-Path ([System.IO.Path]::GetDirectoryName($reportPath)) 'Forthing-U-Tour-extracted-evidence.zip'
if (Test-Path -LiteralPath $zipPath) { throw "Output already exists: $zipPath" }
[System.IO.File]::WriteAllBytes($zipPath, $zipBytes)
Write-Output "Evidence saved to $zipPath"
```

<details>
<summary>Embedded evidence ZIP - Base64 data (expand only to inspect or copy)</summary>

```text

> **Repository note (Axolotl Cam):** the Base64 appendix (734-entry evidence ZIP, SHA-256
> `c5b4b1b660bf506fecf969c4e182907e363ff22e2a516cd4a8e3ec5b7de0b39d`) is deliberately **not**
> committed. It contains recovered vendor source and the APK-embedded RSA private key. The verified,
> extracted copy lives outside version control at `reference/evidence/` in the main checkout
> (git-ignored). The full original report with the appendix is `reference/Forthing-U-Tour-complete-report.txt`.
