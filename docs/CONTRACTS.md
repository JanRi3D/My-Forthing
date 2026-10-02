# My Forthing – Shared interfaces, data contracts and ownership

Binding for every agent. Producers implement exactly these shapes (improvements allowed when documented in the report and in this file). Consumers code against them. Kotlin signatures are normative; naming may add suffixes but not change meaning.

## 1. Modules and ownership

| Path | Owner branch | Contents |
| --- | --- | --- |
| `recorder/` (Gradle `:recorder`, Kotlin/JVM, no Android deps) | feature/recorder-protocol | `me.ri3d.dashcam.recorder.*` |
| `app/.../core/` | feature/foundation | theme, navigation, shared UI, DB, prefs, profile, branding, logging, `core/home`, `core/onboarding`, `core/settings` (app section) |
| `app/.../dashcam/` | feature/recorder-connection | connection manager, network binding, connection screen, Home dashcam card, device/SD info, recorder settings |
| `app/.../live/` | feature/live-view | RTSP player, screenshot, photo/record controls |
| `app/.../media/` | feature/media | recorder browsing, download queue, local library, clip player, share, delete, storage usage |
| `app/.../account/` | feature/accounts | Firebase auth, profile sync, upgrade flow |
| `app/.../drive/` | feature/drive-auth | Drive authorisation, REST client, format v1 |
| `app/.../backup/` | feature/drive-backup | rules, transfer queue, status |
| `app/.../plates/` | feature/plates-core then feature/plates-ui | engine, history, UI |
| `app/.../enhance/` | feature/enhance-core then feature/enhance-ui | models, pipelines, UI |
| `docs/features/<feature>.md` | the feature's owner | setup notes, decisions, measurements, hardware-verification items for that feature |
| `docs/` (rest), `.claude/` | project manager | plans, contracts, reports |

Shared files (`app/build.gradle.kts`, `gradle/libs.versions.toml`, `settings.gradle.kts`, `AndroidManifest.xml`, `core/navigation/Routes.kt`, `core/navigation/AxoNavHost.kt` (one registration line / slot argument per feature), `core/FeatureFlags.kt` (flip your own flag only), `core/data/AppDatabase.kt` (add entities + migration), `res/values/strings.xml`, `res/xml/data_extraction_rules.xml` (add excludes only), `core/settings/SettingsScreen.kt` (append one row per feature in the App section, nothing else)): owned by foundation; other agents may **append** (new deps, new routes, new entities, new strings in their own `<!-- feature -->` block) and must list every such change in their report. Android Auto Backup is disabled (`allowBackup="false"`, cloud and device-transfer excludes): local data stays on the phone, as the UI promises. Hilt: each feature provides its own `@Module` in its package; never edit another feature's module.

## 2. Naming, branding, localisation

- Package root `me.ri3d.dashcam` (applicationId and namespace). App name from `R.string.app_name` only. `core/branding/Branding.kt` holds `appName`, `driveRootFolderName`, `supportUrl`; launcher icon resources (the owner's lion, charcoal `#282629` on `#F4F5F7`) under `res/mipmap-*` / `res/drawable/ic_launcher_*`; the in-app logo is `res/drawable/ic_logo_lion` (same path, independent of the launcher asset, tinted `onSurface` on dark surfaces). Renaming = this file + icon + logo + `app_name` (a package change also needs new Firebase / Google Cloud registrations, `docs/features/accounts.md`, `docs/features/drive.md`). `Axo` (`AxoTheme`, `AxoTopBar`, `AxoNavHost`, `AxoColors`, …) is a legacy code prefix from the working name "Axolotl Cam" and is kept; it never reaches the UI.
- German is the **default** locale (`res/values/strings.xml`), `generateLocaleConfig = true`. The app is pinned to `de` whatever the phone language (`AppLocale.kt`: per-app language through `LocaleManager` on API 33+, a German activity configuration and default locale on every API level), so library texts (Media3, Material 3) and `DateUtils`/`Formatter` output are German too, and the generated locale config lists only `de`. No hard-coded UI text, including content descriptions, notifications, errors. Plurals via `<plurals>`. Dates/sizes via `DateUtils` / `Formatter`.
- Accessibility: every icon-only control has a content description; touch targets ≥ 48 dp; dynamic type supported; animations use Material motion and are skipped when `Settings.Global.ANIMATOR_DURATION_SCALE == 0`.

## 3. Theme (foundation)

`core/theme/AxoTheme.kt`: `AxoTheme(theme: AppTheme, content)`. `enum class AppTheme { MATERIAL_YOU, BLACK }`. MATERIAL_YOU → `dynamicDarkColorScheme` / `dynamicLightColorScheme` on API 31+, static blue fallback below (tokens from `docs/design/M3Home.dc.html` `.t-blue`). BLACK → always dark, surfaces `#050506 / #0b0c0e / #0e0f11 / #16181b / #1f2125`, primary `#f4f5f7`, error `#ff6961`. Semantic extras `AxoColors.recording` (#ff453a or error tone) and `AxoColors.ok` (#30d158 or tertiary tone) via `LocalAxoColors`.

## 4. Navigation (foundation owns `Routes.kt`; features append)

Type-safe routes (`@Serializable`, all implement `sealed interface Route`): `Welcome`, `SignIn`, `CreateAccount`, `VerifyEmail`, `ForgotPassword`, `ResetSent`, `OfflineProfile`, `Upgrade`, `Home`, `Connection`, `Live`, `Recordings(tab: String? = null)`, `SdCard`, `SdFiles(category: String)`, `Settings`, `Appearance`, `Plates(query: String? = null)`, `PlateDetail(plateId: Long)`, `Clip(mediaId: String, positionMs: Long = 0)`, `Enhance(mediaId: String, positionMs: Long)`, `Upscale(mediaId: String)`, `Backup`, `Storage`. `fun startDestination(hasProfile: Boolean): Route` → `Home` if a profile exists, else `Welcome`.

**Registration pattern (as implemented):** each feature adds `fun NavGraphBuilder.xxxGraph(navController: NavController, …)` in its own package and one line inside `AxoNavHost { … }` (`core/navigation/AxoNavHost.kt`). Existing: `onboardingGraph(navController)`, `homeGraph(navController, dashcamCard: @Composable () -> Unit = { HomeDashcamCard() })` (the Home avatar opens `Account`, the Einstellungen tile `Settings`), `settingsGraph(navController, recorderSettingsSection: RecorderSettingsSection? = null)`. The dashcam feature supplies both slots. `AxoNavHost` wraps content in a `Surface`; transitions are shared-axis X, `None` under reduce-motion.

**Feature flags:** `object FeatureFlags { const val accounts, plates, backup, live, media, dashcam = false }` (`core/FeatureFlags.kt`). Routes/buttons of unfinished features are hidden or disabled behind these; the owning feature flips its own flag to `true` in its branch.

## 5. Core models (foundation, `core/model`)

```kotlin
data class LocalProfile(val id: String, val displayName: String, val avatarPath: String?, val createdAt: Long, val linkedUid: String?)
enum class AppTheme { MATERIAL_YOU, BLACK }
data class AppPreferences(
  val theme: AppTheme = AppTheme.MATERIAL_YOU,
  val platesLive: Boolean = false, val platesClips: Boolean = false,
  val liveUpscale: Boolean = false, val exportQuality: ExportQuality = ExportQuality.Q1440,
  val backupMode: BackupMode = BackupMode.MANUAL, val backupOnMobileData: Boolean = false,
  val backupRequireInternetWifi: Boolean = true, val backupIncludePlateMetadata: Boolean = false,
)
enum class BackupMode { MANUAL, INCIDENTS, ALL }
```
`AppPreferences` is app-only. Recorder settings are a different type (§7) and are never written from preferences. As implemented: `enum class ExportQuality { Q1080, Q1440, Q2160 }`; `LocalProfile` is a Room entity (`local_profile`), `avatarPath` relative to `filesDir`; `LocalProfileDao { fun observe(): Flow<LocalProfile?>; suspend fun upsert(profile) }`; `ProfileRepository.createLocal(displayName, avatar: Uri?)`; `PreferencesRepository(@Singleton) { val preferences: Flow<AppPreferences>; suspend fun update(transform) }`. Hilt `core/di/CoreDataModule` provides `AppDatabase` (`myforthing.db`, schema export on, no destructive migration), `LocalProfileDao` and the unqualified `DataStore<Preferences>`; features needing their own DataStore add a qualifier. Shared UI as implemented: `AxoTopBar(title, onBack, actions)`, `StateView(state, …)`, `ConfirmDialog(title, text, confirmLabel, onConfirm, onDismiss, danger)` (danger = extra acknowledgement checkbox), `ListGroup/ListRow/SectionHeader`, `LocalSnackbarHostState`, `LocalReduceMotion`. `UiText` = `Res(@StringRes id, args)` | `Dynamic(text)`.

Result/error conventions: suspend functions return `Result<T>` or domain sealed classes; UI state is `sealed interface UiState<T> { Loading; Empty; Error(message: UiText, retry: (() -> Unit)?); Ready(data) }` in `core/ui/UiState.kt`. `UiText` = string-resource reference or dynamic text.

Logging: `core/log/Log.kt` wrapper (`Log.d` only in debug builds) with a **redaction** helper `redact(json)` that masks `token`, `tokenNum`, `aescode`, `passwd`, `password`, `key`, `access_token`, `refresh_token`, `idToken`, `id_token` (case-insensitive, whole values of any JSON type; double-encoded JSON inside strings is not masked, so never log JSON-in-JSON). Protocol code logs frames only through it.

## 6. `:recorder` module API (feature/recorder-protocol) – pure JVM

```kotlin
// Framing: "FAAB" + u32 seq (BE) + u32 bodyLen (BE) + body
object FrameCodec { fun encode(seq: Int, body: ByteArray): ByteArray; class Decoder { fun feed(bytes: ByteArray, off: Int, len: Int): List<Frame> } }
data class Frame(val seq: Int, val body: ByteArray)

// Crypto: AES-128-ECB zero padding; RSA/ECB/PKCS1Padding unwrap of aescode; key is hex text
object SessionCrypto {
  fun unwrapAesKey(aescodeB64: String, rsaPrivateKey: ByteArray): String // hex
  fun encrypt(json: String, hexKey: String): String                      // base64, no line breaks
  fun decrypt(b64: String, hexKey: String): String                       // GB2312, trailing NUL stripped
}

// Transport abstraction so Android can bind sockets to the recorder Network
interface RecorderTransport { suspend fun connect(host: String, port: Int, timeoutMs: Int); suspend fun write(bytes: ByteArray); suspend fun read(buf: ByteArray): Int; fun close() }
fun interface RecorderTransportFactory { fun create(): RecorderTransport }

// Session state machine – TCP-connected and session-ready are distinct states
sealed interface SessionState {
  object Idle; object Connecting; object TcpConnected; object Negotiating
  data class Ready(val token: Int, val version: String?, val productType: Int?, val timeoutSec: Int?)
  data class Failed(val reason: RecorderError)
}
class RecorderClient(transportFactory: RecorderTransportFactory, rsaPrivateKey: ByteArray, scope: CoroutineScope, clock: Clock) {
  val state: StateFlow<SessionState>
  val notifications: SharedFlow<RecorderNotification>   // 16384 normal + 16385 events (auto-ack sent for 16385)
  suspend fun start(host: String = "192.168.42.1", port: Int = 7878)   // connect + negotiate; one immediate retry as traced
  suspend fun stop()
  suspend fun send(cmd: RecorderCommand): RecorderReply                 // encrypted after Ready; seq-matched; raw JSON kept
  suspend fun <T> request(cmd: RecorderCommand, parse: (RecorderReply) -> T): RecorderResult<T>
}
data class RecorderReply(val msgId: Int, val rval: Int /* -1 if absent */, val param: JsonElement?, val rawJson: String)
sealed interface RecorderResult<out T> { data class Ok<T>(val value: T, val reply: RecorderReply) : RecorderResult<T>; data class Failed(val error: RecorderError) : RecorderResult<Nothing> }
data class RecorderError(val code: Int /* rval, or local -101/-102/-103/4096 */, val source: Source /* RECORDER, LOCAL, TIMEOUT */, val rawJson: String?, val message: String?)
```

Commands (`RecorderCommand` sealed, msgIds exactly as in the report): StartSession(1), StopSession(2), KeepAlive(3), GetSetting(4096, chanNo, type), GetAllSettings(4097), GetDeviceInfo(4098), GetStorageInfo(4099, driver=1), ListFiles(4100, driver=1, type, lastFileName, pageNum=50), DeleteFiles(4101, fileList), SetSettings(8192, SettingsPatch), FormatStorage(12288, driver=1), FactoryReset(12289), TakePhoto(12292, chanNo=1, interval=3, number), StartRecord(12293, recType), StopRecord(12294, recType=2), GetCapabilities(20480..20485). Parsers keep **raw JSON** and unknown fields; the three distinct field sets stay distinct: file lists (`fileName/fileThm/fileTime`), command replies (`filePath/thmPath/fileTime`), events (`filePath/fileThm/time`).

Settings model: `RecorderSettings(global: GlobalSettings, channels: List<ChannelSettings>, raw: JsonObject)`; `WifiParam(mode: Int /* 0 AP, 1 STA, preserved exactly as received */, ssid, passwd, frequency)`; `OsdInfo(enableOsd: Int, osdContent: List<Int>)` serialised as a real JSON array; G-sensor sent as raw int 1/2/3 with UI labels **1 = Hoch, 2 = Mittel, 3 = Niedrig** (traced app behaviour; the SDK naming conflict is documented in code).

Regression tests required: Wi-Fi mode not inverted in read-all; OSD array serialisation; G-sensor mapping; network-capability flags not overwriting each other; fragmented and combined frames; missing rval → -1; heartbeat loss ≈ 11 s; dual 5 s session monitors; event auto-ack uses a fresh sequence.

Timing constants (from the report): connect timeout 3000 ms, one immediate retry, session monitors 5000 ms, keepalive every 4000 ms starting 4 s after key init, disconnect when no successful keepalive for > 10 s.

Simulator: `RecorderSimulator` implements `RecorderTransport` in memory with scripted replies/notifications and fault injection (fragmentation, delays, bad rval). Fixtures in `recorder/src/test/resources/fixtures/*.json`.

RSA private key: supplied by the app at runtime (`BuildConfig.DASHCAM_RSA_KEY` from `local.properties` key `dashcam.rsaKey`, Base64 PKCS#8 or PKCS#1 – the module accepts both, also PEM text). Tests use a generated test key, never the vendor key.

**As implemented (deviations accepted by the manager):**
- `WifiParam(mode: Int?, ssid: String?, passwd: String?, frequency: Int?)`: absent fields stay absent and are not sent; `mode` is never converted; `toString` hides the password.
- Extra `RecorderNotification.Unmatched(seq, reply)` for replies without a waiting request (e.g. a second burst-photo reply) and unknown msgIds; `Normal.raw` / `Event.raw` are a redacted `RecorderReply`.
- Argument order: `GetSetting(type, chanNo = 1)`, `ListFiles(type, lastFileName = "", pageNum = 50, driver = 1)`, `TakePhoto(chanNo = 1, interval = 3, number = 1)` (wire order), `StartRecord(recType, chanNo = 1)`, `StopRecord(recType = 2, chanNo = 1)` (report includes `chanNo`), `DeleteFiles` rejects an empty list. Bodies keep the vendor field order (`msgId, token, param`; parameterless `token, msgId`).
- `stop()` disconnects directly like the original app and never sends msgId 2; `send(StopSession)` cancels the heartbeat and closes the session afterwards (reply or timeout); a received msgId 2 disconnects first.
- Plaintext detection: a body starting with `{` is plaintext JSON; parsing stops at the last `}` (trailing padding tolerated). Every `start()` gets two connect attempts. Per-request timeout: `send(cmd, timeoutMs = 10_000)` / `request(cmd, parse, timeoutMs = 10_000)` (`RecorderClient.DEFAULT_REQUEST_TIMEOUT_MS`, not traced, tune on hardware; formatting and bursts may need more) fail with -205 `TIMEOUT` and drop the pending entry (a late reply arrives as `Unmatched`). `timeoutMs` is the last parameter, so pass the parser as `::parseX` or an explicit lambda argument, not a trailing lambda. Heartbeat loss after ≈ 11 s still ends the session.
- Single 5 s session deadline (same observable behaviour as the SDK's two monitors: local result 4096 + disconnect). `MAX_BODY` 64 KiB.
- Local codes: -101 connect, -102 send, -103 insufficient parameter, 4096 session monitor, -201 heartbeat lost, -202 disconnected, -203 session key invalid (missing/bad RSA key fails `start()` before connecting), -204 unparsable reply, -205 request timeout. Error JSON kept in errors is redacted. `RecorderSettings`/`GlobalSettings.toString()` are redacted.
- Typed parsers are top-level functions: `parseDeviceInfo`, `parseStorageInfo`, `parseSettings`, `parseFileList`, `parseCaptureResult`, `parseAllCapabilities`, `parseBasicCapabilities`, `parseImageCapabilities` (list), `parseNetworkCapabilities`, `parseStorageCapabilities`, `parseIntelligentCapabilities`, `parseNormalNotification`, `parseEventNotification`; usage `client.request(GetDeviceInfo, ::parseDeviceInfo)`. `ErrorCodes.APP_MEANINGS` (enum `AppMeaning`), `HAT_NAMES`, local codes.
- `RecorderClient(transportFactory, rsaPrivateKey, scope, clock = Clock.SYSTEM, diagnostics: RecorderDiagnostics? )`, `fun interface Clock { fun nowMs(): Long }`; `FrameCodec.Decoder(onGarbage)`; `SessionCrypto` also exposes `loadPrivateKey(ByteArray)` and `hexToBytes`.
- `RecorderSimulator` is a **test fixture** (`recorder/src/testFixtures`, `java-test-fixtures` plugin): consumers use `testImplementation(testFixtures(project(":recorder")))`. It generates its own 1024-bit key pair (`privateKeyPkcs8`), honours `connectDelayMs`/connect timeout and has `failWrites`. Module targets Java/Kotlin 11 like `:app`.
- Not implemented (SDK-only): 12295 time setting, 8193 firmware, 8194/39317/39318 timelapse, UDP discovery.

## 7. Connection manager (feature/recorder-connection, `dashcam/`)

```kotlin
sealed interface RecorderConnectionState {
  object NoWifi; data class WrongWifi(val ssid: String?); object Connecting; object TcpConnected; object Negotiating
  data class Ready(val info: DeviceInfo?, val session: SessionState.Ready, val network: Network?)
  data class Error(val error: RecorderError, val retry: Boolean)
}
interface RecorderConnectionManager {
  val state: StateFlow<RecorderConnectionState>
  val notifications: SharedFlow<RecorderNotification>
  suspend fun connect(); suspend fun disconnect()
  suspend fun <T> request(cmd: RecorderCommand, parse: (RecorderReply) -> T): RecorderResult<T>
  val recorderNetwork: StateFlow<Network?>      // Wi-Fi network used for binding HTTP/RTSP
  fun httpClient(): OkHttpClient                // bound to recorderNetwork; cleartext allowed for 192.168.42.1 only
  fun mediaUrl(recorderPath: String): String    // "http://192.168.42.1" + path
}
```
Wi-Fi binding: `ConnectivityManager.requestNetwork` for `TRANSPORT_WIFI`; sockets and OkHttp use `network.bindSocket` / `network.socketFactory`; **never** `bindProcessToNetwork`, so internet stays usable. If no suitable network is found, show the troubleshooting hint (mobile data off) from the report. Recorder settings are shown as "bestätigt" only after `rval == 0` and a readback.

As implemented in `dashcam/`: states also include `Disconnected`; `connect(ignoreSsid = false)` (a second call joins the running attempt; connect/stop/network-loss are serialised by one Mutex); `request(cmd, parse, timeoutMs)`; `sdStatus`, `recStatus`, `storage: StateFlow<StorageInfo?>` (one 4099 per session; all cleared on stop), `ssid`, `simulator: StateFlow<Boolean>` + `setSimulator()` (debug builds only; host 10.0.2.2), `refreshSsid()`, `mobileDataEnabled(): Boolean?`, `diagnosticLog()`, `suspend capabilities(group: CapabilityGroup): RecorderReply?` (20480–20485 of the current session, 3 s timeout, cached until the session ends), `note(topic, message)` / `notes(): Map<String, List<RecorderDiagnostic.Info>>` (Diagnose `notes`, last 50 per topic, redacted; `httpClient()` logs every request under `http`). Sockets are always bound to the `Network` found in the attempt (never an unbound fallback; -101 otherwise); `httpClient()` throws `RecorderNotBoundException` when nothing is bound (except simulator mode). Session lives while the app is foregrounded (ON_STOP disconnects; ON_START reconnects only after a previous Ready). Command outcomes: `ChangeStatus.Confirmed` (readback matches), `Unconfirmed`, `Accepted` (rval 0 without readback: format, factory reset), `Unknown(error)` for -201/-202/-205 ("Ergebnis unbekannt – neu verbinden und prüfen"). Wi-Fi dialog changes only the password (8–16 printable ASCII with letter+digit, typed twice), resubmits `WifiParam` with ssid/frequency as read and no chanNo; `mode` is the only value capability 20483 lists, otherwise as read (hardware 2026-10-02: reads 1, supports only `[0]`). Routes added: `Diagnostics` (read-only capture + share as JSON). Composables: `DashcamHomeCard`, `DashcamSettingsSection`, `dashcamGraph`. `FeatureFlags.dashcam = true`.

## 7a. Live view (feature/live-view, `live/`) – as implemented

RTSP via Media3 `RtspMediaSource` through `RtspSdpProxy` on 127.0.0.1 (repairs the recorder's SDP: `a=control:*`, first video section only), whose socket to the recorder comes from `(state as Ready).network.socketFactory`; URL candidates `rtspCandidates()`: the capability 20481 `rtspServer` URL of the first channel (hardware: `rtsp://192.168.42.1:554/ch1/sub`; rtsp on 192.168.42.1 without credentials only), then the traced `/ch1/sub/av_stream`; each over TCP interleaved, an RTSP 461 retries the same URL with UDP (`LivePlayer.play(url, socketFactory, tcp)`); starts only when the connection is `Ready` and the screen is started; stops on ON_STOP/leave; one automatic retry of the list after 1.5 s, then `StreamState.Failed(StreamError(name, code, cause, rtspStatus, udp))` with German reasons by Media3 error-code group and code + raw cause as a second line; every attempt noted under `notes.rtsp`; audio track disabled. Commands: 12292 (`number` 1 or 5), 12293 (`recType` 1, 10 s UI countdown, never 12294); late/unmatched replies are shown with rval meaning. **Screenshot folder contract**: `filesDir/screenshots/<uuid>.jpg` written first, then `<uuid>.json` `{ id, capturedAt (ISO-8601 with offset), source: "live", width, height }` (a JSON means its JPEG is complete); EXIF `DateTimeOriginal`/`OffsetTimeOriginal`; a MediaStore copy in `Pictures/<app name>` on API 29+ (intended; Google Photos backup of Pictures is the user's own setting). Phase 4 hooks: `liveGraph(navController, leadingControls: RowScope slot /* Plates */, trailingControls: RowScope slot /* Upscale */, belowControls: ColumnScope slot /* recognised plates + Verlauf, battery note */, overlay: BoxScope.(videoRect: Rect) -> Unit, renderEffect: @Composable (videoRect: Rect) -> RenderEffect?)`; `LiveFrameSource.frames(targetFps 1..30, wanted: () -> Boolean = { true }): Flow<Frame>` (software ARGB ≤ 1280 px, only while collected and playing; pass `processor::wantsFrame`), `videoSize` while playing. `FeatureFlags.live = true`.

## 8. Media library (feature/media, `media/`) – Room entities registered in `core/data/AppDatabase`

```kotlin
@Entity data class MediaItem(
  @PrimaryKey val id: String,              // UUID v4, stable, used in the Drive format
  val kind: MediaKind,                     // ORIGINAL_VIDEO, ORIGINAL_PHOTO, SCREENSHOT, ENHANCED_FRAME, UPSCALED_CLIP
  val category: MediaCategory,             // NORMAL, EVENT, USER, UNKNOWN (recorder type 0/1/2/other; raw kept)
  val recorderType: Int?, val recorderPath: String?, val recorderThumbPath: String?,
  val originalFileName: String, val recorderTime: String?,  // raw "yyyy-MM-dd HH:mm:ss", timezone UNKNOWN
  val recorderTimeEpochGuess: Long?,       // parsed with the phone's zone, flagged as a guess
  val localUri: String?, val localSizeBytes: Long?, val localThumbPath: String?, val downloadedAt: Long?,
  val parentId: String?,                   // for ENHANCED_FRAME / UPSCALED_CLIP
  val parentPositionMs: Long?,
  val driveFileId: String?, val backupState: BackupState, val backupError: String?, val driveMd5: String?,
  val createdAt: Long
)
enum class BackupState { NONE, QUEUED, UPLOADING, DONE, FAILED }
```
Three copies, three explicit actions: `deleteLocalCopy(id)`, `deleteOnRecorder(path)` (command 4101, confirmation dialog), `deleteOnDrive(id)` (confirmation). None cascades.

Downloads: `DownloadQueue` (WorkManager, foreground notification) with per-item progress `StateFlow<Map<String, TransferProgress>>`, cancel, retry, resume via HTTP `Range` when the recorder answers 206, otherwise restart into a temp file and atomic rename.

As implemented in `media/` (DB version 3, `MIGRATION_2_3`): `MediaRepository.observe(id)/observe(kind?, category?)/observeLocal()/observeChildren(id)/get(id)/localItems(kinds)`, `upsertFromRecorderListing(type, files)` (path reuse with a different `fileTime` detaches a row that has a phone/Drive copy and inserts a new one; a listed path without a row re-links a detached row with the same type/name/time), `reconcileRecorderListing(type, listed, keep)` (only when the listed count reached `totalFileNum`; never rows in transfer; never deletes local files), `registerDerived(kind, file, parentId, parentPositionMs, info: EnhancementInfo?)`, `importScreenshots()`/`watchScreenshots()`, `markDownloaded`, `deleteLocalCopy`, `deleteOnRecorder(paths)`, `markRecorderDeleted(path)`, `markDriveDeleted(id)`, `update(id, transform)`. Downloads run only with a `Ready` session on the bound network (`RecorderHttp.client()` throws `RecorderNotReadyException` otherwise; not-ready/not-bound retries never count towards `DownloadWorker.MAX_ATTEMPTS = 10`); responses validated leniently (any Content-Type but `text/html`, absent accepted; Content-Length optional, `0` incomplete; `.part.size` consistency on resume only where sizes are known); at most `DownloadQueue.MAX_PARALLEL = 1` running (2 in simulator mode), others held; stalls (`SocketTimeoutException` / `SocketException`) resume within the worker run after 5/15/45 s; the recorder OkHttp client runs one request at a time (90 s read timeout) and thumbnails pause while a download runs; `TransferProgress(…, failure: DownloadFailure?, httpCode, detail)` (also for WAITING after a real failure); failures noted under `notes.http`; recorder thumbnails are `.thm` (decoded by content), rows prefer the local thumbnail. Recordings tabs: Schleife (type 0), Vorfälle (1), Fotos (2), Handy (local). Paging ends on an empty page, or on a short page only once `totalFileNum` is reached. Slots: `mediaGraph(navController, selectionActions: RowScope.(List<MediaItem>, clearSelection) -> Unit, clipActions: (MediaItem, positionMs) -> Unit, clipExtras: (MediaItem, positionMs, seekTo: (Long) -> Unit) -> Unit, clipOverlay: BoxScope.(MediaItem, positionMs) -> Unit, clipDeleteTargets: (MediaItem) -> List<DeleteTarget(label, enabled, title, text, danger, onConfirm)>)` – the clip screen renders chooser and confirmations itself. Routes `Storage` (phone usage, "Freigeben" warns about last copies) and `SdFiles(category)`. Debug builds add cleartext for 10.0.2.2 (simulator HTTP on port 8080, `:recorder:runSimulator`). `FeatureFlags.media = true`.

## 9. Accounts (feature/accounts, `account/`)

```kotlin
sealed interface AccountState {
  object Guest
  data class SignedIn(val uid: String, val email: String?, val emailVerified: Boolean, val displayName: String?, val photoUrl: String?, val online: Boolean)
}
interface AccountRepository {
  val state: StateFlow<AccountState>
  suspend fun signInGoogle(activity: Activity): Result<Unit>
  suspend fun signInEmail(email: String, password: String): Result<Unit>
  suspend fun createEmail(email: String, password: String): Result<Unit>
  suspend fun sendPasswordReset(email: String): Result<Unit>
  suspend fun sendVerification(): Result<Unit>
  suspend fun reloadVerification(): Result<Boolean>
  suspend fun signOut()
  suspend fun linkGuestProfile(strategy: MergeStrategy): Result<Unit>
}
enum class MergeStrategy { KEEP_LOCAL, KEEP_REMOTE, ASK }
```
As implemented in `account/`: `AccountRepository.isConfigured`; `AccountNotConfigured` (every auth call fails with it when `app/google-services.json` is absent – Gradle parses that git-ignored file into `BuildConfig.FIREBASE_*`, no google-services plugin); `MergeConflict(local: ProfileSummary, remote: ProfileSummary)` and `LinkedToOtherAccount(accountEmail)` from `linkGuestProfile` (`ASK` surfaces the conflict, `KEEP_REMOTE` = switch accounts); `SignedIn` is reported only when the Firebase user equals `LocalProfile.linkedUid`, with `displayName` from the local profile; synced preferences are only `theme` and `exportQuality`; `accountGraph(navController)`, `AccountSettingsRow(shape, onNavigate)`, `Throwable.toAccountMessage(): UiText?` (null = cancelled); route `Account` (Konto; direct entry from the Home avatar, handles guest, signed-out and signed-in state itself). Firebase is never initialised for guests.

Firestore: `users/{uid}` { displayName, photoPath, preferences (theme, exportQuality), createdAt, updatedAt }. Storage: `users/{uid}/avatar.jpg` (optional; without it the picture stays on the phone and is never deleted by a re-sign-in). Migration rule: if `users/{uid}` already has data, show local vs remote and let the user choose; never overwrite silently. Signed-in users without network keep working from the cached `LocalProfile`. Firebase is initialised lazily; a missing or placeholder `google-services.json` must not crash guest mode.

## 10. Drive (feature/drive-auth, `drive/`) and backup (feature/drive-backup, `backup/`)

```kotlin
sealed interface DriveAuthState { object NotConnected; data class Connected(val accountEmail: String?, val scopes: Set<String>); data class NeedsReconnect(val reason: String) }
interface DriveAuth { val state: StateFlow<DriveAuthState>; suspend fun connect(activity: Activity): Result<Unit>; suspend fun disconnect(); suspend fun accessToken(): Result<String> }
interface DriveApi {
  suspend fun ensureRootFolder(): Result<String>
  suspend fun uploadResumable(file: File, name: String, mime: String, parentId: String, appProperties: Map<String, String>, sessionUri: String?, onProgress: (Long, Long) -> Unit): Result<DriveFile>
  suspend fun writeJson(name: String, parentId: String, json: String, appProperties: Map<String, String>): Result<DriveFile>
  suspend fun list(query: String): Result<List<DriveFile>>
  suspend fun delete(id: String): Result<Unit>
  suspend fun about(): Result<DriveQuota>
}
```
No Firebase call anywhere in `drive/`. Scope `https://www.googleapis.com/auth/drive.file` (the future web app must live in the same Google Cloud project to see these files). Insufficient storage (HTTP 403 `storageQuotaExceeded`) and revoked access (401 / `invalid_grant`) map to explicit states and the reconnect flow.

### Drive format v1 (documented for the web app in `docs/DRIVE_FORMAT.md`, written by feature/drive-auth)

```
<Drive>/My Forthing/                 root folder; appProperties: mf.format=1, mf.role=root
  myforthing.json                    { "format": 1, "app": "me.ri3d.dashcam", "createdAt": "ISO-8601" }
  media/<yyyy-MM>/<mediaId>.<ext>    original or derived file; appProperties: mf.id, mf.kind, mf.category, mf.parent (optional), mf.format=1
  media/<yyyy-MM>/<mediaId>.json     sidecar, written ONLY after the media upload is verified (md5Checksum match)
```
Sidecar schema v1:
```json
{ "format": 1, "id": "<uuid>", "kind": "ORIGINAL_VIDEO|ORIGINAL_PHOTO|SCREENSHOT|ENHANCED_FRAME|UPSCALED_CLIP",
  "category": "NORMAL|EVENT|USER|UNKNOWN", "recorderType": 1, "originalFileName": "...", "recorderPath": "...",
  "recorderTime": "2026-10-01 12:00:00", "recorderTimeZone": null, "downloadedAt": "ISO-8601 with offset",
  "sizeBytes": 123, "md5": "...", "mime": "video/mp4", "durationMs": 60000,
  "parent": { "id": "<uuid>", "positionMs": 37000 },
  "plates": [ { "text": "B-MK 4821", "normalized": "BMK4821", "positionMs": 37000, "confidence": 0.93, "box": [0, 0, 0, 0] } ],
  "backup": { "complete": true, "completedAt": "ISO-8601" } }
```
`parent` and `plates` may be `null`; `confidence` may be `null`. `recorderTimeZone` stays `null` unless the recorder reports one. Incomplete backups are visible as media files without a sidecar (or sidecar `complete=false`). Discovery: `files.list` with `q="appProperties has { key='mf.format' and value='1' }"`.

Backup queue: `BackupQueue` (WorkManager unique work per mediaId, constraints from `AppPreferences`), states in `MediaItem.backupState`, resumable-upload session URI persisted for restart recovery, duplicate prevention by querying `mf.id` before upload. Rules from the drive-auth review: when `DriveAuthState.Connected.accountEmail` changes (account switch), reset every `driveFileId`/`backupState`/`driveMd5` and drop stored upload-session URIs (they can complete an upload without the auth header); `DriveApi.delete()` treats 404 as success, so never use it to infer that a file existed in the current account; `deleteOnDrive(id)` removes the sidecar together with the media file; uploads run one at a time (no folder cache/lock in `DriveApi`). As implemented in `drive/`: `DriveAuth.connect(activity, chooseAccount = false)`, `invalidate(token, revoked)`, `NeedsReconnect(reason, accountEmail?)`, `DriveApi.ensureMonthFolder(rootId, month)`, `uploadResumable(..., sessionUri, onSessionUri, onProgress)`, `DriveError` sealed hierarchy (`NotConnected, NeedsReconnect, InsufficientStorage, Offline, Cancelled, Authorization(statusCode), ScopeNotGranted, Http(code, reason)`), every v1 file carries `mf.role` (root/manifest/folder/media/sidecar), `DriveFormatReader.scan(api)` → `DriveBackupEntry(mediaId, media, sidecar) { complete }`.

Backup as implemented in `backup/` (feature/drive-backup): `BackupRules.automatic(item, mode, parent)` (MANUAL / INCIDENTS = category EVENT / ALL = local originals; derived items only once the original is DONE; only items with a phone copy), `BackupRules.constraints(prefs)` (default network with validated internet, unmetered unless mobile allowed, Wi-Fi when required; `NOT_VPN` removed) and `networkFits(caps, prefs)` re-checked by the worker before uploading; `DriveBackup.upload(id)` = duplicate check by `mf.id` (adopt on md5 match; a sidecar-less different-md5 file is deleted only if it is this device's recorded unverified upload or older than 24 h, else wait; different md5 with sidecar = `DRIVE_CONFLICT`, untouched) → resumable upload with the session stored per account hash → md5 verification → sidecar (plates only via `PlateExport`) → DONE only if the account is unchanged; sessions dropped only on non-retryable 4xx; `deleteOnDrive` deletes media, then sidecar, then `markDriveDeleted` (a left sidecar sets `backupError = SIDECAR_LEFT` and keeps the delete target); `reconcile()` via `DriveFormatReader` sets vanished DONE items to NONE (skipping items done < 5 min ago), never touching local files; account switch resets Drive fields, sessions, exclusions, unverified records; `BackupQueue` = one unique work at a time + upload lock, `BackupInitializer` (androidx.startup), German foreground notification cancelled in `finally`, pauses for NeedsReconnect/NotConnected/storage full (persisted). UI: route `Backup` (status card, mode, conditions, plate switch, queue), `BackupSelectionAction` ("Sichern"), `driveDeleteTargets`, `BackupStateTag` in media rows (only DONE reads "In Drive gesichert"). `FeatureFlags.backup = true`.

## 11. Plates (feature/plates-core → feature/plates-ui, `plates/`)

```kotlin
data class PlateDetection(val text: String, val normalized: String, val confidence: Float?, val box: RectF, val frameTimestampMs: Long)
interface PlateRecognizer { suspend fun recognize(frame: Bitmap, timestampMs: Long): List<PlateDetection>; fun close() }
@Entity data class Plate(@PrimaryKey(autoGenerate = true) val id: Long, val normalized: String, val display: String, val firstSeen: Long, val lastSeen: Long, val count: Int)
@Entity data class PlateSighting(@PrimaryKey(autoGenerate = true) val id: Long, val plateId: Long, val mediaId: String?, val positionMs: Long?, val source: SightingSource /* LIVE, CLIP */, val seenAt: Long, val confidence: Float?, val cropPath: String?)
```
Unreadable characters are stored with `?` in `display` and `confidence = null`, never guessed. Live processing is throttled by measured frame time (`AdaptiveThrottle`). History is local by default; it is exported only when `backupIncludePlateMetadata` is on.

As implemented in `plates/` (ML Kit Text Recognition v2 Latin, bundled): `PlateDetection(text, normalized, confidence: Float?, box: RectF, frameTimestampMs, format: PlateFormat /* GERMAN | GENERIC */)`; `MlKitPlateRecognizer(maxWidth = 1280)`; `PlateSighting` also has `display` (the sighting's own reading, used as sidecar `text`) and box columns; `PlateRepository.history()/search(query)/plate(id): Flow<PlateWithSightings?>/recordSightings(detections, source, mediaId?, positionMs?, frame?, seenAt, frameScale)/clear()/clearForMedia()/forgetClip()`; `LivePlateProcessor(recognizer, repository?, scope, throttle) { detections, stats, wantsFrame, submit(Frame(bitmap, timestampMs)): Boolean, close() }` (frames must be software bitmaps); `ClipPlateScanner.scan(uri, mediaId, fps = 2, clipStartMs?, onProgress): ScanSummary`; `PlateExport.forMedia(mediaId)` returns empty unless `backupIncludePlateMetadata`. German readings require a listed district code (`GermanDistrictCodes.kt`, KBA list 2026-04-16), a visible boundary and a confidently read digit; joined readings are GENERIC; a dropped seal glyph forces `confidence = null`. **Confidence must never be shown as a percentage** (it does not separate right from wrong readings); the UI may show at most a coarse hint. `AppDatabase` is version 2 (`MIGRATION_1_2`, `MIGRATIONS` array registered in `CoreDataModule`).

Plates UI as implemented in `plates/ui/` (feature/plates-ui): `platesGraph` registers `Plates`, `PlateDetail`, `PlatesSettings`; live slots `LivePlatesToggle` (leading), `LivePlatesOverlay(videoRect)` (overlay; TalkBack summary on the first chip), `LivePlatesList` (below; last 3 plates of the visit, "Verlauf" link, frames/s hint); frames collected only while the toggle is on, the screen is resumed (rotation and the full-screen switch do not count, 500 ms stop delay) and the stream plays; media slots `ClipPlates(item, seekTo, onNavigate)` (sightings with jump links + "Clip auf Kennzeichen prüfen" via the app-scoped `ClipScans` queue, derived items refused) and `ClipPlatesOverlay(item, positionMs)` (±500 ms); `PlatesAutoScan()` after the NavHost starts automatic checks of new local original videos when `platesClips` is on (from switch-on time, never clips downloaded while off; lost on process death; paused by the Android 14+ freezer); confirmed deletions (`PlateRepository.clear/delete/clearForMedia`) run `NonCancellable`; no percentage anywhere, "unsicher gelesen" only when `confidence == null`; the sidecar's `plates[].confidence` is an internal OCR score (documented in DRIVE_FORMAT.md, never a percentage). `FeatureFlags.plates = true`.

## 12. Enhancement (feature/enhance-core → feature/enhance-ui, `enhance/`)

```kotlin
interface FrameEnhancer { suspend fun enhance(src: Bitmap, scale: Int, onProgress: (Float) -> Unit): Bitmap }
interface ClipUpscaler { fun upscale(input: Uri, target: Resolution, onProgress: (UpscaleProgress) -> Unit): Job /* cancellable */ }
data class UpscaleProgress(val fraction: Float, val etaMs: Long? /* only after ≥ 5 % measured */, val outputBytesEstimate: Long?)
```
Outputs are new `MediaItem`s (`ENHANCED_FRAME` / `UPSCALED_CLIP` with `parentId`); originals untouched. UI copy labels outputs "verbessert/hochskaliert – rekonstruiert, kein Beweis". **Enhanced outputs are never scanned for plates**: `ClipPlateScanner`/`LivePlateProcessor` callers (plates-ui) must refuse `ENHANCED_FRAME`/`UPSCALED_CLIP` media, and an enhanced plate crop is never stored as a `PlateSighting`.

As implemented in `enhance/` (LiteRT 1.4.2 CPU + QuickSRNet Medium BSD-3 asset, classical GPU path for clips): `FrameEnhancer.enhance(src, scale 2|4, onProgress)`, `enhanceFrame(src, scale, engine?, onProgress): EnhancedFrame(bitmap, engine, model)`, `capabilities(): EnhancerCapabilities` (engines, limits, `estimateMs` only after a measured run); `ClipUpscaler.upscale(UpscaleRequest(input, target: Resolution /* P1080|P1440|P2160; `ExportQuality.resolution` maps */, sourceMediaId: String /* required */, engine = CLASSICAL, codec = H264), onProgress): Deferred<UpscaleResult>` (+ overload `upscale(input, target, sourceMediaId, onProgress)`) + `awaitResult()`, `estimate(request)`; `UpscaleResult.Done(output: EnhancedOutput, …) | Failed(UpscaleError.Decoder|Encoder|Storage|Memory|TargetNotLarger|Cancelled)`; frame failures throw `EnhanceException(EnhanceError.TooLarge|Memory)`; outputs in `filesDir/enhance/<uuid>.(jpg|mp4)` with `<file>.enhance.json` (`EnhancementInfo`, `reconstructed: true`, `sourceMediaId` required) written before the atomic rename, JPEGs also carry EXIF `ImageDescription` "rekonstruiert, kein Beweis"; leftovers from earlier processes are swept on first access; `saveEnhancedFrame(context, frame, scale, sourceMediaId: String, sourcePositionMs)`; `LiveUpscaleProbe.run(): LiveUpscaleDecision(offer, reason, …)` – live sharpening (`LiveSharpen.effect()`, API 33+ RenderEffect, classical only) is offered only when the probe says so on the actual device; ML is never live. Bitmaps above ~100 MB cannot be drawn by Canvas: enhance-ui shows a downsampled preview. Long jobs survive leaving the screen but not process death; enhance-ui wraps them in a foreground service or WorkManager.

Enhance UI as implemented in `enhance/ui/` (feature/enhance-ui): `enhanceGraph` registers `Enhance`, `Upscale`, `EnhanceSettings`; `EnhanceClipActions(item, positionMs, onNavigate)` fills `clipActions` (video: "Bild verbessern" + "Clip hochskalieren"; photo/screenshot: enhance only; derived items: nothing); Enhance screen extracts the frame (`OPTION_CLOSEST`), filters scale/engine by `capabilities()`, previews ≤ 4096 px, saves via `saveEnhancedFrame` + `registerDerived(ENHANCED_FRAME)` non-cancellably (orphans deleted on failure), refuses derived items; Upscale screen filters targets by `estimate()`/`TargetNotLarger`, `MediaCodecList` encoder capability and the Android 15 `dataSync` budget (> 5 h estimate disabled), runs `UpscaleWorker` (unique work `enhance-upscale`, KEEP, dataSync foreground, cancel from notification, notification cancelled in `finally`, adopts an unregistered output after a restart, registers `UPSCALED_CLIP` exactly once, typed failures incl. `SOURCE_GONE`, `INTERRUPTED`, `NOT_ORIGINAL`); `LiveSharpenControl` fills `trailingControls` and `liveSharpenEffect(videoRect)` fills `renderEffect` (only when `LiveUpscaleProbe` offers and API ≥ 33; wording "geschärft"); `EnhanceSettings` shows export quality, the probe reason and the licence texts from `assets/models/`.

## 13a. Build-version rule

One `kotlin` version key in `gradle/libs.versions.toml` drives `kotlin-jvm`, `kotlin-compose` and `kotlin-serialization` (currently 2.4.10, the Compose-compiler release; AGP 9.3.3 bundles KGP 2.2.10 but the plugin on the root classpath wins). kotlinx libraries must be releases compatible with that Kotlin line (currently coroutines 1.10.2, serialization 1.11.0; move to serialization 1.12.0 once final and re-run the `:recorder` -204 tests). Every AndroidX dependency must accept compileSdk 36 (`checkDebugAarMetadata`); do not raise compileSdk. Emulator (`emulator-5554`, x86_64, API 36) is shared: install with `adb install -r`, keep sessions short, treat performance numbers from it as indicative only.

## 13. Testing conventions

Unit tests: JUnit 4 + kotlinx-coroutines-test + Truth (foundation adds the deps). `:recorder` tests run without Android. Android tests: Robolectric for Room/DataStore where practical; instrumented Compose tests on the emulator only in the integration phase. Simulated recorder results are labelled `[SIM]` in reports.
