# My Forthing – Status

As of 2026-10-02: **release 1.0.0 (Build 1)**, built from `main` at `9ba3eb5` plus `fix/final-polish` (fixes from the
acceptance review: the app is pinned to German, accessibility labels, the live error line, user docs), rebuilt with
the package `me.ri3d.dashcam` (`feature/rename-dashcam`). Every feature branch is integrated. Unit tests on
`feature/rename-dashcam` (`./gradlew clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
:recorder:test`): **443 unit tests, 0 failures, 0 skipped** (`:app` 371, `:recorder` 72); `:app:lintDebug`: 0 errors,
24 warnings (dependency/AGP/targetSdk version notices and one plurals hint). On `fix/real-recorder-1` (fixes after the
first contact with the physical recorder): **457 unit tests, 0 failures, 0 skipped** (`:app` 383, `:recorder` 74);
lint unchanged (0 errors, 24 warnings). On `fix/real-recorder-2` (fixes after the second hardware test, **release
1.0.1 (Build 2)**; `./gradlew :recorder:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`): **467 unit
tests, 0 failures, 0 skipped** (`:app` 393, `:recorder` 74); lint 0 errors, 24 warnings. On `feature/cache` (cache
for thumbnails, listings and recorder values, **release 1.0.2 (Build 3)**; same command): **486 unit tests, 0 failures,
0 skipped** (`:app` 412, `:recorder` 74); lint 0 errors, 24 warnings. On `fix/real-recorder-3` (fixes after the third
hardware test, **release 1.0.3 (Build 4)**; same command): **496 unit tests, 0 failures, 0 skipped** (`:app` 419,
`:recorder` 77); lint 0 errors, 24 warnings. **Release 1.0.4 (Build 5)** on `main`: the 1.0.3 code, built with the real
`app/google-services.json` (Firebase config, accounts enabled); `:app:testDebugUnitTest`: **419 unit tests, 0 failures,
0 skipped**. On `feature/drive-restore` (Drive tab, import of Drive backups, "Vom Drive laden", silent Drive reconnect
from the app account; after review fixes, `./gradlew :app:testDebugUnitTest :recorder:test :app:lintDebug :app:assembleDebug`): **541 unit tests
(`:app` 464, `:recorder` 77), 0 failures, 0 skipped**; lint 0 errors, 24 warnings (unchanged).

Labels: **[SIM]** = against `RecorderSimulator` or `:recorder:runSimulator`; **emulator** = AVD Pixel_10_Pro_XL,
API 36, x86_64 (performance numbers indicative only). The instrumented tests in `app/src/androidTest` (plates 5,
enhance 21) were run on the emulator by the branch that owns them (see the feature table); the template
`ExampleInstrumentedTest` (package name) has no recorded run. None were re-run for the release, because
`connectedDebugAndroidTest` would reinstall the app on the shared emulator. **First contact with the physical
recorder: 2026-10-02** (owner's Diagnose export, release 1.0.0 on a phone with API 37; see "Verified on hardware"
below): the control session works end to end; live view and downloads did not. Second test the same day (build from
`fix/real-recorder-1`): file listing works; live view fails in Media3's SDP parsing; downloads stall. Third test (1.0.1,
mobile data on): downloads work (≈ 1.1 MB/s); live view failed with ECONNREFUSED on the app's own loopback proxy (fixed
in 1.0.3, which also repairs the recorder's SDP as captured). The owner checklist is
[`HARDWARE_CHECKLIST.md`](HARDWARE_CHECKLIST.md).

## Branches

| Branch | State |
| --- | --- |
| `feature/recorder-protocol`, `feature/foundation`, `feature/drive-auth`, `feature/accounts`, `feature/plates-core`, `feature/enhance-core`, `feature/recorder-connection`, `feature/live-view`, `feature/media`, `feature/rename-my-forthing`, `feature/enhance-ui` | merged (`e726184` … `95df1ce`) |
| `fix/download-notification`, `feature/drive-backup` | merged (`02996be`, `6c38347`) |
| `feature/release` | merged (`32f56f5`) |
| `feature/plates-ui` | merged (`b153bf4`) |
| `feature/release-final` | merged (`9ba3eb5`) |
| `fix/final-polish` | merged (`d21c42f`) |
| `feature/rename-dashcam` | merged (`ae0bcc6`): package id changed to `me.ri3d.dashcam`, rebuilt APKs |
| `feature/lion-icon` | merged (`8932583`): the owner's lion replaces the axolotl as launcher icon (light `#F4F5F7` background, themed-icon layer) and Welcome logo; rebuilt APKs |
| `fix/profile-shortcut` | merged (`fa3bced`): the Home profile picture opens Konto (guest, signed-out and signed-in state) instead of Einstellungen; Welcome logo no longer announced twice; rebuilt APKs |
| `fix/real-recorder-1` | merged (`bb7ca54`): fixes from the first hardware test – live view tries the recorder's own RTSP URL (20481) then the traced one, UDP after a 461; lenient recorder HTTP (any Content-Type but HTML, Content-Length optional) with the failure reason in Übertragungen; RTSP attempts and recorder HTTP requests in the Diagnose export (`notes`); Wi-Fi password change sends the only mode 20483 lists; SD values read as MB when they fit a card; rebuilt APKs |
| `feature/cache` | merged (`4ffb58a`): persistent thumbnail disk cache (64 MB LRU, keys path + recorder time, header-independent, offline), recorder tabs from the library at once with "Stand: …" (also offline), listings page by page to the end without moving the list, `fileNew` inserted / `fileDel` removed without re-listing, lowest-priority thumbnail prefetch (paused by downloads and scrolling, 300 per session), last 4098/4097/4099/capabilities per recorder shown as "zuletzt gelesen" (Home card, Verbindung, SD-Karte, Einstellungen), DB 4; release 1.0.2 (Build 3) |
| `fix/real-recorder-2` | merged (`34a5f71`): fixes from the second hardware test – loopback RTSP proxy that repairs the recorder's SDP for Media3 (`a=control:*`, video section only); raw RTSP DESCRIBE in Diagnose (`rtsp.describe`); recorder HTTP one request at a time (90 s read timeout), one download at a time, thumbnails paused during downloads, stalled downloads resume within the run (5/15/45 s) with speed display; `.thm` decoded by content; simulator pages of 20; release 1.0.1 (Build 2) |
| `fix/real-recorder-3` | merged (`5b21fa9`): fixes from the third hardware test – the RTSP proxy listens on `127.0.0.1` (it was on `::1`, the ECONNREFUSED), notes `listening` / `accepted` / `TEARDOWN forwarded`, lets Media3's TEARDOWN through on stop; the SDP rewrite handles the recorder's exact SDP (session lines, start codes stripped from the parameter sets, the cut SPS repaired – Media3 would otherwise crash the app); `SimulatorRtspServer` in `:recorder:runSimulator` (port 7554) streams `sim/clip.mp4` in the recorder's RTSP shape, live view plays it on the emulator; release 1.0.3 (Build 4) |
| `feature/drive-restore` | in review (not merged): Drive tab in Aufnahmen, every complete Drive backup imported into the library (also after clearing data / on a new phone), "Vom Drive laden", the Drive account follows the app account and reconnects silently |

## Release 1.0.4 (Build 5)

In `dist/` of the main checkout (git-ignored) with `SHA256SUMS.txt`, built on `main` (tag `v1.0.4`; `./gradlew clean
:app:assembleRelease :app:assembleDebug`; versionCode 5, so it installs over 1.0.0–1.0.3 without uninstalling; same
signing key; database still version 4); the 1.0.3 (Build 4) APKs moved to `dist/1.0.3-4/`, the older ones are in
`dist/1.0.2-3/`, `dist/1.0.1-2/` and `dist/1.0.0-1/`, each with their checksums. Build, verification and signing in
[`RELEASE.md`](RELEASE.md). Same code as 1.0.3; the only difference is the Firebase configuration compiled in. The APKs
have the same byte sizes as 1.0.3 (zip alignment padding) but other contents and hashes.

| File | Bytes | SHA-256 |
| --- | --- | --- |
| `MyForthing-1.0.4-5-arm64-v8a.apk` | 36,464,142 | `341608f2ee11be71c61b02610f6f26e5719a2d0007f4cec5325a6667869e28ce` |
| `MyForthing-1.0.4-5-armeabi-v7a.apk` | 30,338,046 | `e8791799b73745492c93d82e5c0b5e754edd44e777b28b025107d8006a76dd68` |
| `MyForthing-1.0.4-5-debug-universal.apk` (owner's emulator / ADB only, debug key) | 88,506,179 | `1fd31e04683fe904154e67082b66af97b7dd1e3aa22a558ca1430a69974dfc5e` |

- Release APKs: `me.ri3d.dashcam`, versionCode 5, versionName 1.0.4, label "My Forthing", not debuggable, one ABI each,
  signed (v2) with `CN=My Forthing, O=Jan Ried`, certificate SHA-1 `50:BD:A2:FD:…:9C:40:8B:76` / SHA-256
  `82a1f729…f9e735dc` (`apksigner verify`, `aapt2 dump badging`).
- Contains every feature, including "Sicherung" and the plate screens (`FeatureFlags` all `true`).
- Built **with** `app/google-services.json`: built with Firebase config, accounts enabled. All five `FIREBASE_*`
  `BuildConfig` values are non-empty and present in the release dex (1.0.0–1.0.3 had accounts disabled, "Konto-Dienst ist
  in dieser Installation nicht eingerichtet."). Real sign-in, mails and sync are still unverified on a phone.
- Google Drive needs no file in the app: it works with this APK as soon as the Google Cloud project has an Android
  OAuth client for `me.ri3d.dashcam` with the release SHA-1 (`SETUP.md` §3); until then "Google-Cloud-Konfiguration fehlt
  (Statuscode 10)".

## Features

Unit test counts are `@Test` methods per package on `fix/real-recorder-3` (all run above). Instrumented and emulator results are as
reported by the owning branch in `docs/features/*.md`.

| Feature | Unit tests | Emulator / instrumented | Simulator-only [SIM] | Hardware-unverified | Blocked on owner input |
| --- | --- | --- | --- | --- | --- |
| **Foundation** (`core/`: theme, navigation, guest profile + onboarding, settings, Darstellung, log redaction; `AppLocale.kt`: UI pinned to German) | 23 | emulator walkthrough; on an en-US emulator Media3 controls ("Player-Steuerelemente anzeigen", "Geschwindigkeit"), Material 3 sheet ("Ziehpunkt"), dates ("2. Oktober 2026 um 05:24") and sizes ("6,3 MB") are German, also on the first start | – | the pin on Android 8–12 (no per-app language; covered by a Robolectric test only) | – |
| **Recorder protocol** (`:recorder`: framing, session crypto, commands, notifications, error codes, SDK-defect regressions, simulator incl. RTSP) | 77 [SIM] | – (pure JVM) | everything: fixtures from the protocol report, fragmentation, heartbeat loss, auto-ack, Wi-Fi mode / OSD / G-sensor / capability-flag regressions; hardware reply shapes | heartbeat-loss timing, error codes (handshake, AES traffic, replies and notifications: **verified on hardware 2026-10-02**) | recorder sessions |
| **Dashcam connection** (`dashcam/`: Wi-Fi binding, states, Verbindung, Home card, SD-Karte, recorder settings with readback, Diagnose, last read values "zuletzt gelesen") | 51 [SIM] | walkthrough against `:recorder:runSimulator` [SIM]; real Wi-Fi path up to -101 | settings readback, format, reset | hotspot binding with mobile data **on**, 4099 units (MB assumed), every 8192 change (`chanNo` 0 vs 1), Wi-Fi password (mode 0 from 20483), format, factory reset, recStatus meaning (`features/dashcam.md` 1–14); binding with mobile data off, session, 4097/4098/4099 values, notifications, capabilities: **verified on hardware 2026-10-02** | recorder sessions |
| **Live view** (`live/`: RTSP via Media3 through a loopback SDP-repair proxy, full screen, screenshot, Foto / 5er-Serie / Aufnahme) | 35 (Robolectric, fake player; fake RTSP server; Media3's own SDP parser and track building on the recorder's exact SDP) | **playback of the simulated recorder stream on the emulator** through the proxy (picture, "Live" badge, 320x176, screenshot, plate frames, full screen, TEARDOWN, three opens in a row) [SIM] | the recorder's RTSP shape (`SimulatorRtspServer`: its SDP defects, aggregate SETUP, TCP interleaved RTP); commands | stream (**failed on hardware 2026-10-02**: second test "missing attribute control", third test ECONNREFUSED on the app's `::1` proxy – both fixed; the captured SDP is repaired and parsed by Media3 in tests, but no recorder RTP has reached a decoder yet), codec 880x496 Main as the SDP says, latency, screenshot from a real frame, keep-screen-on, command replies (`features/live.md` 0–9) | recorder sessions |
| **Media** (`media/`: cursor paging, download queue with resume, Handy library, clip player, share, three-copy deletion, Speicher, thumbnail cache + prefetch, cached listings) | 63 [SIM] (MockWebServer, WorkManager, migrations 2→3, 3→4) | simulator HTTP incl. force-stop + Range resume [SIM]; cache: all tabs offline with lists and thumbnails after a simulator session [SIM] | listing, paging, downloads, 4101 deletion | downloads (**stalled on hardware 2026-10-02** after 3.2 MB of 132 MB next to thumbnails and a second download; fix: one request and one download at a time, 90 s reads, in-run resume after 5/15/45 s), HTTP Range support, `.thm` content, type 2 content, time zone, 4101 (`features/media.md` 0–9); listing with pages of 20, `totalFileNum` without `totalFileSize`, `/sd/DCIM` + `/sd/EVENT` (`…G.mp4`) paths, `.thm` as `application/binary`, `fileNew`/`fileDel`, recorder clock offset: **verified on hardware 2026-10-02** | recorder sessions |
| **Accounts** (`account/`: Google + e-mail sign-in, verification/reset mails, profile sync, guest → account linking) | 51 (fakes, no Firebase) | not-configured path only | – | real sign-in, mails, sync between phones | – (`app/google-services.json` in place, compiled into 1.0.4 (Build 5)) |
| **Drive auth** (`drive/`: authorisation, encrypted account record, REST v3 client, format v1) | 42 | account picker opens, cancel | – | real connect, quota, revoke | **Google Cloud: Drive API, consent screen, Android OAuth clients** (`SETUP.md` §3) |
| **Drive backup** (`backup/`: rules, single-slot upload queue, MD5 verification, sidecars, pause/reconnect, Drive-Kopie löschen, Drive-Status prüfen) | 36 | Drive **not** connected; states seeded in the debug DB; two incident downloads [SIM] | connected states only in unit tests | every upload path (`features/backup.md` 1–8, checklist C3) | Cloud setup as above |
| **Drive restore** (`backup/DriveRestore`, `media/DriveDownloads`, Drive tab: import of every complete backup into the library, merge with recorder/phone rows, "Vom Drive laden" with MD5 check, Drive thumbnails, `driveAccount` synced with the app account, silent reconnect; `docs/features/drive-restore.md`) | 45 (in-memory Drive, MockWebServer, WorkManager, fake authoriser) | reviewer's smoke run: Drive tab not-connected card renders, German texts, no crash (the five-segment row was cut at narrow widths); after the switch to a scrollable tab row, at 360 dp all labels whole, Drive tab card with the new text, Home tile with the tab names | – | everything against real Drive and a real Google account: silent authorisation after a reinstall, `thumbnailLink` with the token, `alt=media` Range resume, photo display, Firebase sync of `driveAccount` (`features/drive-restore.md` owner checklist 1–8) | Cloud setup as above |
| **Plates core** (`plates/`: ML Kit OCR, plate rules, history DB, live throttle, clip scanner, sidecar export) | 34 | instrumented: `PlateEvaluationTest` (synthetic 44 plates + 16 negatives), `PlateBenchmark` (2), `ClipPlateScannerTest` (2) | – | accuracy on real footage, phone speed, CPU/thermal | **real dashcam recordings**; decision on ML Kit usage metrics |
| **Plates UI** (`plates/ui`: live overlay, clip check, search, detail, settings, honesty rules) | 25 | clip check on the simulator fixture, screens with a seeded history [SIM] | clip check (fixture without plates); live frames collected from the simulated stream on the emulator (4.2 frames/s, no plates in the clip) | live overlay alignment and lag, sharpening kept out of plate frames (checklist B3), real clip checks, automatic check after real downloads | real recordings |
| **Enhance core** (`enhance/`: QuickSRNet ×4 + classical fallback, GPU clip upscaler, live-sharpen probe, honesty sidecars) | 20 | instrumented: `EnhanceInstrumentedTest` (17), `EnhanceBenchmark` (4, opt-in) | – | phone speed, every clip encode incl. 1080p (the emulator's only H.264 encoder stops at 8192 macroblocks), live sharpening on a real GPU, battery/thermal | real recordings for the model choice |
| **Enhance UI** (`enhance/ui`: Bild verbessern, Clip hochskalieren, Schärfen, settings + licences) | 39 (Robolectric) | frame enhance + save; **every** upscale target incl. 1080p (1964 × 1080 for the fixture) disabled, because the emulator's AVC encoder rejects that size – nothing about 1080p working on the emulator can be inferred [SIM] | – | any real upscale (1080p, 1440p, 2160p), its notification, pinch zoom, live sharpening (needs RTSP) | – |
| **Release** (signing, versioning, per-ABI APKs, docs) | – | `apksigner` / `aapt2` verified; universal debug APK for the emulator | – | install and update on a real phone (Android 8–16) | keystore backup by the owner (`RELEASE.md` §5) |

## Verified on hardware (2026-10-02)

Owner's Diagnose export with release 1.0.0: recorder `AE-DC2013-LQ2`, fw `SX5G-3776510A_A` (2024-03-21); phone API 37
on `FORTHING-A267451`, network bound, mobile data off. Moved from "unverified" to **verified**:
- Wi-Fi request and socket binding (mobile data off); FAAB framing; RSA/AES session handshake with the vendor key
  (session `1.1.3`, productType 0, timeOut 10); AES traffic; keepalives.
- 4098, 4097, 4099 and all capability queries 20480–20485 answered; `osdContent` is a real array.
- Unsolicited notifications with frame sequence 0xFFFFFFFF (-1): `sdStatus`, `recStatus`, `fileNew`, `fileDel`.
- Loop-clip paths `/sd/DCIM/ch1_YYYYMMDD_HHMMSS_NNNN.mp4` with `.thm` thumbnails; `downloadPath`
  `http://192.168.42.1:80`; recorder clock ≈ 2.5 min ahead of the phone.

Not working in that build, fixed on `fix/real-recorder-1` and awaiting the next test: live view (recorder reports
`rtsp://192.168.42.1:554/ch1/sub`), downloads (reason not captured).

Second test the same day (build from `fix/real-recorder-1`, session `Ready` throughout, keepalives every 4 s):
- **Verified:** 4100 listing – pages of 20 although 50 are asked, `totalFileNum` 144 (loop) / 1997 (events), no
  `totalFileSize`; events in `/sd/EVENT/` named `ch1_YYYYMMDD_HHMMSS_NNNNG.mp4`, loop clips in `/sd/DCIM/`; `.thm`
  served as `application/binary`, 3–6 KB; a 5-minute clip is 138,152,548 bytes (≈ 3.7 Mbit/s), served
  `200 application/binary` with Content-Length.
- **Live view:** both URLs answer DESCRIBE; Media3 1.11 rejects the SDP (`ERROR_CODE_IO_UNSPECIFIED (2000):
  IllegalArgumentException: missing attribute control`). Fixed on `fix/real-recorder-2` with the loopback proxy
  (no Media3 option exists); the SDP text is captured by the next Diagnose (`rtsp.describe`).
- **Downloads:** stalled after 3,276,800 bytes (`SocketTimeoutException`, also on the Range resume) while a second
  download and thumbnails ran and the recorder recorded. Fixed on `fix/real-recorder-2` (see Media row); the cause
  (single-connection server, card writes) is a hypothesis until the next test. Differences handled: Wi-Fi `mode` 1 read vs.
20483 `[0]` (the password change now sends 0); 4099 values read as MB. Open: 4097 `chanNo 0` while 8192 sends
`chanNo 1` (checklist E).

Third test the same day (app 1.0.1, **mobile data on**, Wi-Fi bound, session Ready):
- **Verified:** downloads – 28,563,628 bytes in 26 s (≈ 1.1 MB/s), no stall, with mobile data on (the bound network
  carries HTTP); RTSP on port 554 with mobile data on: OPTIONS 200 (`Public: OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY,
  PAUSE`), DESCRIBE 200 with a 174-byte SDP (`v=0`, `m=video 0 RTP/AVP 96`, `a=rtpmap:96 H264/90000`, `a=fmtp:96
  profile-level-id=4DE028;packetization-mode=1;sprop-parameter-sets=<start-coded SPS>,<start-coded PPS>`; no
  `o=`/`s=`/`t=`/`c=`, no `a=control`; SPS: Main, 880x496, cut after `bitstream_restriction_flag`).
- **Live view:** every attempt `ERROR_CODE_IO_UNSPECIFIED (2000): ErrnoException: connect failed: ECONNREFUSED`, no
  proxy note: the proxy listened on `::1` (Android's `getLoopbackAddress()`), Media3 dialled `127.0.0.1`. Fixed on
  `fix/real-recorder-3` with the SDP repairs (`features/live.md`, "SDP repair"); the stream itself is still unverified.

## Open decisions and inputs for the owner

- Inputs: Google Cloud OAuth setup (`SETUP.md`); real dashcam
  recordings; recorder sessions through `HARDWARE_CHECKLIST.md`; keystore backup (`RELEASE.md` §5).
- ML Kit sends usage/performance metrics to Google (no image content); keep, or strip the transport services from the
  merged manifest (`features/plates.md`).
- Terms and privacy links point to placeholder pages under `Branding.supportUrl`.
- In-app account deletion is not offered (needed before any Play Store release).
- R8 stays off (APK size, `RELEASE.md` §8) until keep rules are written and the release build is re-tested.
- Not in v1: Apple sign-in, e-mail code sign-in, firmware update, timelapse, "Restart dashcam", web app, in-app updater.
