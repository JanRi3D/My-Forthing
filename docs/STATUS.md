# My Forthing – Status

As of 2026-10-02: **release 1.0.0 (Build 1)**, built from `main` at `9ba3eb5` plus `fix/final-polish` (fixes from the
acceptance review: the app is pinned to German, accessibility labels, the live error line, user docs), rebuilt with
the package `me.ri3d.dashcam` (`feature/rename-dashcam`). Every feature branch is integrated. Unit tests on
`feature/rename-dashcam` (`./gradlew clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
:recorder:test`): **443 unit tests, 0 failures, 0 skipped** (`:app` 371, `:recorder` 72); `:app:lintDebug`: 0 errors,
24 warnings (dependency/AGP/targetSdk version notices and one plurals hint).

Labels: **[SIM]** = against `RecorderSimulator` or `:recorder:runSimulator`; **emulator** = AVD Pixel_10_Pro_XL,
API 36, x86_64 (performance numbers indicative only). The instrumented tests in `app/src/androidTest` (plates 5,
enhance 21) were run on the emulator by the branch that owns them (see the feature table); the template
`ExampleInstrumentedTest` (package name) has no recorded run. None were re-run for the release, because
`connectedDebugAndroidTest` would reinstall the app on the shared emulator. **Nothing has run against the physical
recorder or on a real phone yet**; the owner checklist is [`HARDWARE_CHECKLIST.md`](HARDWARE_CHECKLIST.md).

## Branches

| Branch | State |
| --- | --- |
| `feature/recorder-protocol`, `feature/foundation`, `feature/drive-auth`, `feature/accounts`, `feature/plates-core`, `feature/enhance-core`, `feature/recorder-connection`, `feature/live-view`, `feature/media`, `feature/rename-my-forthing`, `feature/enhance-ui` | merged (`e726184` … `95df1ce`) |
| `fix/download-notification`, `feature/drive-backup` | merged (`02996be`, `6c38347`) |
| `feature/release` | merged (`32f56f5`) |
| `feature/plates-ui` | merged (`b153bf4`) |
| `feature/release-final` | merged (`9ba3eb5`) |
| `fix/final-polish` | merged (`d21c42f`) |
| `feature/rename-dashcam` | this branch: package id changed to `me.ri3d.dashcam`, rebuilt APKs |

## Release 1.0.0 (Build 1)

In `dist/` of the main checkout (git-ignored) with `SHA256SUMS.txt`; build, verification and signing in
[`RELEASE.md`](RELEASE.md).

| File | Bytes | SHA-256 |
| --- | --- | --- |
| `MyForthing-1.0.0-1-arm64-v8a.apk` | 36,379,986 | `f1662e728792c33c5088fa308c1280d75c4e6878ea6873501bfd050b36a42116` |
| `MyForthing-1.0.0-1-armeabi-v7a.apk` | 30,253,890 | `5289b4f3dfa3a2185b001269d0f407fa342c80d04ed6c496367f4a6feb26c287` |
| `MyForthing-1.0.0-1-debug-universal.apk` (owner's emulator / ADB only, debug key) | 88,372,816 | `29695b1849ba6a010d777e2d52880f1012b46b28e8242e607b10dd2a4b22f7a4` |

- Release APKs: `me.ri3d.dashcam`, versionCode 1, versionName 1.0.0, label "My Forthing", not debuggable, one ABI each,
  signed (v2) with `CN=My Forthing, O=Jan Ried`, certificate SHA-256 `82a1f729…f9e735dc`. A second clean build gave
  the same bytes.
- Contains every feature, including "Sicherung" and the plate screens (`FeatureFlags` all `true`).
- Built **without** `app/google-services.json`: the online account shows "Konto-Dienst ist in dieser Installation nicht
  eingerichtet."; everything else works without it. Accounts need a new build once the file exists (versionCode 2).
- Google Drive needs no file in the app: it works with this APK as soon as the Google Cloud project has an Android
  OAuth client for `me.ri3d.dashcam` with the release SHA-1 (`SETUP.md` §3); until then "Google-Cloud-Konfiguration fehlt
  (Statuscode 10)".

## Features

Unit test counts are `@Test` methods per package on `main` (all run above). Instrumented and emulator results are as
reported by the owning branch in `docs/features/*.md`.

| Feature | Unit tests | Emulator / instrumented | Simulator-only [SIM] | Hardware-unverified | Blocked on owner input |
| --- | --- | --- | --- | --- | --- |
| **Foundation** (`core/`: theme, navigation, guest profile + onboarding, settings, Darstellung, log redaction; `AppLocale.kt`: UI pinned to German) | 23 | emulator walkthrough; on an en-US emulator Media3 controls ("Player-Steuerelemente anzeigen", "Geschwindigkeit"), Material 3 sheet ("Ziehpunkt"), dates ("2. Oktober 2026 um 05:24") and sizes ("6,3 MB") are German, also on the first start | – | the pin on Android 8–12 (no per-app language; covered by a Robolectric test only) | – |
| **Recorder protocol** (`:recorder`: framing, session crypto, commands, notifications, error codes, SDK-defect regressions, simulator) | 72 [SIM] | – (pure JVM) | everything: fixtures from the protocol report, fragmentation, heartbeat loss, auto-ack, Wi-Fi mode / OSD / G-sensor / capability-flag regressions | handshake with the vendor key, AES traffic, timing, real replies and error codes | recorder sessions |
| **Dashcam connection** (`dashcam/`: Wi-Fi binding, states, Verbindung, Home card, SD-Karte, recorder settings with readback, Diagnose) | 42 [SIM] | walkthrough against `:recorder:runSimulator` [SIM]; real Wi-Fi path up to -101 | session, settings readback, SD card, diagnostics | FORTHING hotspot binding with mobile data on, 4097/4098/4099 values and units, every 8192 change, Wi-Fi password, format, factory reset, notifications, capabilities (`features/dashcam.md` 1–14) | recorder sessions |
| **Live view** (`live/`: RTSP via Media3, full screen, screenshot, Foto / 5er-Serie / Aufnahme) | 16 (Robolectric, fake player) | states, retries, commands [SIM] | commands; **RTSP playback never exercised** (the simulator has no RTSP server) | stream, codec, latency, screenshot from a real frame, keep-screen-on, command replies (`features/live.md` 1–9) | recorder sessions |
| **Media** (`media/`: cursor paging, download queue with resume, Handy library, clip player, share, three-copy deletion, Speicher) | 43 [SIM] (MockWebServer, WorkManager, migration 2→3) | simulator HTTP incl. force-stop + Range resume [SIM] | listing, paging, downloads, 4101 deletion | paging semantics, totals units, paths, HTTP Range support, type 2 content, time zone, 4101, file notifications (`features/media.md` 1–9) | recorder sessions |
| **Accounts** (`account/`: Google + e-mail sign-in, verification/reset mails, profile sync, guest → account linking) | 51 (fakes, no Firebase) | not-configured path only | – | real sign-in, mails, sync between phones | **Firebase project + `app/google-services.json`** (`SETUP.md` §2) |
| **Drive auth** (`drive/`: authorisation, encrypted account record, REST v3 client, format v1) | 42 | account picker opens, cancel | – | real connect, quota, revoke | **Google Cloud: Drive API, consent screen, Android OAuth clients** (`SETUP.md` §3) |
| **Drive backup** (`backup/`: rules, single-slot upload queue, MD5 verification, sidecars, pause/reconnect, Drive-Kopie löschen, Drive-Status prüfen) | 36 | Drive **not** connected; states seeded in the debug DB; two incident downloads [SIM] | connected states only in unit tests | every upload path (`features/backup.md` 1–8, checklist C3) | Cloud setup as above |
| **Plates core** (`plates/`: ML Kit OCR, plate rules, history DB, live throttle, clip scanner, sidecar export) | 34 | instrumented: `PlateEvaluationTest` (synthetic 44 plates + 16 negatives), `PlateBenchmark` (2), `ClipPlateScannerTest` (2) | – | accuracy on real footage, phone speed, CPU/thermal | **real dashcam recordings**; decision on ML Kit usage metrics |
| **Plates UI** (`plates/ui`: live overlay, clip check, search, detail, settings, honesty rules) | 25 | clip check on the simulator fixture, screens with a seeded history [SIM] | clip check (fixture without plates); **live overlay never exercised** (no RTSP) | live overlay alignment and lag, sharpening kept out of plate frames (checklist B3), real clip checks, automatic check after real downloads | real recordings |
| **Enhance core** (`enhance/`: QuickSRNet ×4 + classical fallback, GPU clip upscaler, live-sharpen probe, honesty sidecars) | 20 | instrumented: `EnhanceInstrumentedTest` (17), `EnhanceBenchmark` (4, opt-in) | – | phone speed, every clip encode incl. 1080p (the emulator's only H.264 encoder stops at 8192 macroblocks), live sharpening on a real GPU, battery/thermal | real recordings for the model choice |
| **Enhance UI** (`enhance/ui`: Bild verbessern, Clip hochskalieren, Schärfen, settings + licences) | 39 (Robolectric) | frame enhance + save; **every** upscale target incl. 1080p (1964 × 1080 for the fixture) disabled, because the emulator's AVC encoder rejects that size – nothing about 1080p working on the emulator can be inferred [SIM] | – | any real upscale (1080p, 1440p, 2160p), its notification, pinch zoom, live sharpening (needs RTSP) | – |
| **Release** (signing, versioning, per-ABI APKs, docs) | – | `apksigner` / `aapt2` verified; universal debug APK for the emulator | – | install and update on a real phone (Android 8–16) | keystore backup by the owner (`RELEASE.md` §5) |

## Open decisions and inputs for the owner

- Inputs: Firebase project + `app/google-services.json`; Google Cloud OAuth setup (`SETUP.md`); real dashcam
  recordings; recorder sessions through `HARDWARE_CHECKLIST.md`; keystore backup (`RELEASE.md` §5).
- ML Kit sends usage/performance metrics to Google (no image content); keep, or strip the transport services from the
  merged manifest (`features/plates.md`).
- Terms and privacy links point to placeholder pages under `Branding.supportUrl`.
- In-app account deletion is not offered (needed before any Play Store release).
- R8 stays off (APK size, `RELEASE.md` §8) until keep rules are written and the release build is re-tested.
- Not in v1: Apple sign-in, e-mail code sign-in, firmware update, timelapse, "Restart dashcam", web app, in-app updater.
