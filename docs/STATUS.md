# My Forthing – Status

As of 2026-10-02. `feature/release` is based on `main` at `95df1ce`; while it was written, `main` moved to `de52697`
(`feature/drive-backup` and `fix/download-notification` merged). Test counts run on `feature/release`
(`./gradlew :recorder:test :app:testDebugUnitTest --rerun`): **377 unit tests, 0 failures, 0 skipped**
(`:recorder` 72, `:app` 305); `:app:lintDebug`: 0 errors, 24 warnings (dependency/AGP/targetSdk version notices and
one plurals hint). Current `main` has 343 `:app` tests by `@Test` count (+36 backup, +2 downloads; not run here).
Labels: **[SIM]** = against `RecorderSimulator` or `:recorder:runSimulator`; **emulator** = AVD Pixel_10_Pro_XL,
API 36, x86_64 (performance numbers indicative only). Nothing has run against the physical recorder yet; the owner
checklist is [`HARDWARE_CHECKLIST.md`](HARDWARE_CHECKLIST.md).

## Branches

| Branch | State |
| --- | --- |
| foundation, recorder-protocol, recorder-connection, accounts, drive-auth, plates-core, enhance-core, live-view, media, enhance-ui, rename-my-forthing | merged into `main` (before `95df1ce`) |
| `fix/download-notification`, `feature/drive-backup` | merged into `main` (`02996be`, `6c38347`), after this branch's base |
| `feature/plates-ui` | **integration pending** (9 commits ahead of `main`) |
| `feature/release` | this branch: signing, versioning, ABI splits, release docs |

The 1.0.0 APKs built on this branch (`dist/`, `RELEASE.md`) contain neither "Sicherung" nor the plate screens
(`FeatureFlags.backup` / `plates` are `false` at `95df1ce`) and no `app/google-services.json` (accounts off). Rebuild
the release from `main` once `feature/plates-ui` and this branch are integrated; bump `versionCode` if any APK has
already been handed out.

## Features

| Feature | Implemented | Tested offline | Hardware-unverified | Blocked on owner input |
| --- | --- | --- | --- | --- |
| **Foundation** (`core/`: theme Material You / Schwarz, navigation, guest profile + onboarding, settings, Darstellung, logging with redaction) | yes | 21 unit (profile, prefs/DB, log redaction, theme, start destination); emulator | – | – |
| **Recorder protocol** (`:recorder`: framing, session crypto, commands, notifications, error codes, SDK-defect regressions, simulator) | yes | 72 unit [SIM] (fixtures from the protocol report, fragmentation, heartbeat loss, auto-ack, Wi-Fi mode / OSD / G-sensor / capability-flag regressions) | everything: handshake with the vendor key, AES traffic, timing, real replies and error codes | recorder sessions (plan input 4) |
| **Dashcam connection** (`dashcam/`: Wi-Fi binding, states, Verbindung, Home card, SD-Karte, recorder settings with readback, Diagnose) | yes | 42 unit [SIM]; emulator walkthrough against `:recorder:runSimulator` [SIM]; real Wi-Fi path on the emulator up to -101 | FORTHING hotspot binding with mobile data on, 4097/4098/4099 values and units, every 8192 change and its readback, Wi-Fi password change, format, factory reset, notifications, capability replies (`features/dashcam.md` 1–14) | recorder sessions |
| **Live view** (`live/`: RTSP over TCP via Media3, full screen, screenshot, Foto / 5er-Serie / Aufnahme) | yes | 16 unit (Robolectric, fake player) [SIM]; emulator: states, retries, commands [SIM] | **playback never exercised** (simulator has no RTSP server): stream, codec, latency, screenshot from a real frame, keep-screen-on, command replies (`features/live.md` 1–9) | recorder sessions |
| **Media** (`media/`: listing with cursor paging, download queue with resume, Handy library, clip player, share, three-copy deletion, Speicher) | yes | 41 unit [SIM] (MockWebServer, WorkManager, migration 2→3); emulator with simulator HTTP incl. force-stop + Range resume [SIM] | paging semantics, totals units, paths, HTTP Range support, type 2 content, time zone, 4101, file notifications (`features/media.md` 1–9) | recorder sessions |
| **Accounts** (`account/`: Google + e-mail sign-in, verification/reset mails, profile sync, guest → account linking) | yes | 51 unit (linking matrix, sync, errors, not-configured) ; emulator: not-configured path only | – (no recorder) | **Firebase project + `app/google-services.json`** (`SETUP.md`); real sign-in untested |
| **Drive auth** (`drive/`: authorisation, encrypted account record, REST v3 client, format v1 writer/reader) | yes | 42 unit (`DriveRestApiTest` 15, `GoogleDriveAuthTest` 18, `DriveFormatTest` 9); emulator: account picker opens, cancel | – | **Google Cloud: Drive API, consent screen, Android OAuth clients** (`SETUP.md`); real connect untested |
| **Drive backup** (`backup/`: rules, single-slot upload queue, MD5 verification, sidecars, pause/reconnect, Drive-Kopie löschen, Drive-Status prüfen) | on `main` since `6c38347` (not in this branch's APKs) | 36 unit (`DriveBackupTest` 22, `BackupQueueTest` 9, `BackupRulesTest` 5; branch-reported, not run here); emulator without a connected Drive | – | Cloud setup as above, then `features/backup.md` checklist 1–8 |
| **Plates core** (`plates/`: ML Kit OCR, German/EU plate rules, history DB, live throttle, clip scanner, sidecar export) | yes | 33 unit; instrumented synthetic evaluation (44 plates + 16 negatives) and benchmarks on the emulator | accuracy on real footage, phone speed, CPU/thermal | **real dashcam recordings** (plan input 3); decision on ML Kit usage metrics |
| **Plates UI** (`plates/ui`: live overlay, clip check, search, detail, settings, honesty rules) | on `feature/plates-ui`, **integration pending** | 23 unit on the branch (+1 core test; by `@Test` count, not run here); emulator: clip check on the fixture, screens with a seeded history [SIM] | live overlay (needs RTSP), real clip checks, automatic check after real downloads | real recordings |
| **Enhance core** (`enhance/`: QuickSRNet ×4 frame enhancer + classical fallback, GPU clip upscaler, live-sharpen probe, honesty sidecars) | yes | 20 unit; 17 instrumented + 4 opt-in benchmarks on the emulator (model comparison, seams, rotation, cancellation) | phone speed, 1440p/2160p encode (emulator encoder stops at 2048 px), live sharpening on a real GPU, battery/thermal | real recordings for the model choice |
| **Enhance UI** (`enhance/ui`: Bild verbessern, Clip hochskalieren with WorkManager job, Schärfen, settings + licences) | yes | 39 unit (Robolectric); emulator: frame enhance + save, upscale targets disabled by the emulator encoder [SIM] | a successful real upscale, notification while running, pinch zoom, live sharpening | – |
| **Release** (signing, versioning, per-ABI APKs, docs) | this branch | `assembleRelease` signed + `apksigner`/`aapt2` verified; `assembleDebug` universal | install/update on a real phone (Android 8–16) | keystore backup by the owner (`RELEASE.md` §5) |

## Open decisions for the owner

- ML Kit sends usage/performance metrics to Google (no image content); keep, or strip the transport services from the
  merged manifest (`features/plates.md`).
- Terms and privacy links point to placeholder pages under `Branding.supportUrl`.
- In-app account deletion is not offered (needed before any Play Store release).
- R8 stays off (APK size, `RELEASE.md` §8) until keep rules are written and the release build is re-tested.
- Not in v1: Apple sign-in, e-mail code sign-in, firmware update, timelapse, "Restart dashcam", web app, in-app updater.
