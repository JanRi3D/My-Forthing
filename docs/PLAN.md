# My Forthing – Implementation plan

Status: 2026-10-01, plan v1. Owner: project manager (Claude session). All code is written by delegated agents (`.claude/agents/dev.md`, reviewer `.claude/agents/reviewer.md`: Opus 5.5, effort xhigh).

## Product

Android companion app for the Forthing 4 U-Tour dashcam (Hikvision-SDK recorder at 192.168.42.1). App name **My Forthing**, package / applicationId `me.ri3d.dashcam` (app renamed by the owner on 2026-10-01 from the working name "Axolotl Cam", package id changed by the owner on 2026-10-02 before any APK was handed out; the repository folder keeps `Axolotl Cam`, internal Kotlin names keep the legacy `Axo` prefix, the launcher icon is unchanged). German UI, guest-first, optional Firebase account, independent Google Drive backup, on-device plate recognition and enhancement.

Technical reference: `docs/protocol/Forthing-U-Tour-protocol-report.md` (vendor app 3.2.15 analysis). Evidence labels in that report are binding: *App path* / *SDK-only* / *Offline test* / *Needs recorder verification*. Nothing offline can prove the physical recorder accepts commands; see "Hardware verification" below.

Design reference: `docs/design/*.dc.html` (Material 3 page of the artifact), mapping and deviations in `docs/design/README.md`.

## Stack and requirements (decided)

| Area | Decision |
| --- | --- |
| Language / UI | Kotlin, Jetpack Compose, Material 3, Navigation Compose, single `MainActivity` |
| Theme | Material You dynamic colour (Android 12+), static fallback palette below 12; optional "Schwarz" (pure black, Geist-like sans) theme; follows system light/dark; honours `animator_duration_scale`/reduce-motion |
| DI / data | Hilt; Room (local DB); DataStore Preferences; WorkManager (transfer queue); Coroutines + Flow |
| Build | AGP 9.3.3, Gradle 9.5, JDK 21, compileSdk 36 (installed platform), targetSdk 36, **minSdk 26** |
| Recorder control | `:recorder` pure-JVM module: FAAB framing, session (RSA-unwrapped AES-128-ECB zero-pad), commands, notifications, simulator; Android layer binds the socket to the recorder Wi-Fi `Network` |
| Live view | Media3 ExoPlayer + `media3-exoplayer-rtsp`, TCP interleaved, `rtsp://192.168.42.1/ch1/sub/av_stream`, no credentials (as traced) |
| Media HTTP | OkHttp bound to the recorder `Network` (socketFactory), `http://192.168.42.1` + recorder path |
| Accounts | Firebase Auth (Google via Credential Manager + `googleid`; email/password; reset + verification mails), Firestore (profile, app preferences), Firebase Storage (profile pictures) |
| Drive | Google Identity `AuthorizationClient` (play-services-auth), scope `drive.file`, Drive REST v3 over OkHttp, resumable uploads, tokens in EncryptedSharedPreferences/Keystore. Independent of Firebase |
| Plates | ML Kit Text Recognition v2 (on-device) + plate-pattern parsing; confidence only if ML Kit returns it |
| Enhancement | On-device LiteRT super-resolution candidate(s) evaluated against classical scaling in Phase 2 (licence + measured speed decide); originals always preserved |
| Secrets | `local.properties` (git-ignored): `dashcam.rsaKey`, keystore path/password; `app/google-services.json` git-ignored; placeholder config documented |
| Distribution | Signed release APK, dedicated upload keystore outside git; in-app "check for update" is **not** in scope v1 |

## Phases, dependencies, branches, owners

Rule: a feature starts only from integrated `main` containing every prerequisite. Parallel only with disjoint file ownership (see `docs/CONTRACTS.md` → Ownership). Each branch has exactly one owner agent. Fixes go to `fix/<topic>` or back to the owner's branch.

| Phase | Branch | Owner | Depends on | Scope |
| --- | --- | --- | --- | --- |
| 1 | `feature/foundation` | dev-A | – | Build fix, stack, theme, navigation, shared UI, Room/DataStore, guest profile + onboarding, Appearance settings, localisation, branding |
| 1 | `feature/recorder-protocol` | dev-B | – | `:recorder` module: codec, session, crypto, commands, notifications, error model, fixtures, simulator, SDK-defect regression tests |
| 2 | `feature/accounts` | dev-C | 1 | Firebase Auth flows, profile sync, guest→account migration, offline signed-in |
| 2 | `feature/drive-auth` | dev-D | 1 | Drive authorisation, token storage, REST client, folder/metadata format v1 writer/reader |
| 2 | `feature/plates-core` | dev-E | 1 | Recognition engine, plate parsing, history DB, performance probes |
| 2 | `feature/enhance-core` | dev-F | 1 | Model evaluation, frame enhance + clip upscale pipelines, progress/cancel |
| 2 | `feature/recorder-connection` | dev-G | 1 (both) | Connection manager + states, Wi-Fi binding, connection/troubleshooting screen, Home card, device info, SD card, recorder settings |
| 3 | `feature/live-view` | dev-H | 2-G | RTSP live view, full screen, screenshot, photo/record controls |
| 3 | `feature/media` | dev-I | 2-G | Browse (cursor paging), download queue, local library, clip player, share, explicit deletion targets, storage usage |
| 4 | `feature/drive-backup` | dev-J | 3-I, 2-D | Backup rules, durable transfer queue (WorkManager), status, confirmation, reconnect flow |
| 4 | `feature/plates-ui` | dev-K | 3-H, 3-I, 2-E | Live overlay, clip processing, search, history, links to clip + timestamp, metadata-in-backup toggle |
| 4 | `feature/enhance-ui` | dev-L | 3-I, 2-F | Enhance frame, upscale clip screens, outputs linked to originals |
| 5 | `feature/release` | dev-M | all | Signing, versioning, reproducible build docs, German user docs, Firebase/Drive setup docs, APK |
| 5 | reviews | reviewer | each integration | Independent review before merge of every phase |

Integration order into `main`: 1-A, 1-B → 2-G, 2-C, 2-D, 2-E, 2-F → 3-H, 3-I → 4-J, 4-K, 4-L → 5-M. Build (`assembleDebug`, unit tests) is verified after every merge.

## Validation plan

Offline/simulated: unit tests in `:recorder` (fixtures from the report), simulator-driven connection tests, Room/DataStore tests, WorkManager tests, Compose UI tests for key screens, emulator runs (Pixel_10_Pro_XL, API 36) for the workflows in brief §13 that do not need the car. Simulated results are labelled as such.

Hardware verification (owner checklist, delivered with release): session handshake on the real recorder, RTSP live stream, file listing/paging, downloads, photo/record commands, settings readback/confirm, notifications, SD card status, Wi-Fi + mobile data routing.

## External inputs required from the owner

1. Firebase project (new, or reuse "my-forthing"): Android app with package `me.ri3d.dashcam`, debug + release SHA-1/SHA-256 registered, Google sign-in enabled → `app/google-services.json`.
2. Google Cloud (same project): Drive API enabled, OAuth consent screen with scope `drive.file`, test users, Android OAuth clients for package `me.ri3d.dashcam` (debug + release certificates). Registrations made for an earlier package name do not apply and must be redone.
3. Representative dashcam recordings (MP4/JPG from the SD card) for plate/enhancement evaluation.
4. Physical recorder sessions for the hardware checklist.

## Out of scope (v1)

Apple sign-in, e-mail code sign-in (shown in the artifact, not in the brief), firmware update, timelapse UI (SDK-only paths), "Restart dashcam" (no documented command), web app (format prepared only), in-app updater.
