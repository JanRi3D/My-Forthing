# Media (feature/media)

Package `me.ri3d.dashcam.media`. Implements CONTRACTS §8 on top of the connection manager (§7) and `:recorder` (§6).

| File | Contents |
| --- | --- |
| `MediaItem.kt` | Room entity `media_item` (§8 fields exactly), `MediaKind`, `MediaCategory`, `BackupState`, `MediaDao` |
| `MediaRepository.kt` | Library: observe, `upsertFromRecorderListing`, `reconcileRecorderListing`, `registerDerived`, `importScreenshots` / `watchScreenshots`, `markDownloaded`, `deleteLocalCopy`, `deleteOnRecorder`, `markRecorderDeleted`, `markDriveDeleted`, `update`; local thumbnails |
| `RecorderListing.kt` | `Listing.append` (cursor paging fold), `RecorderBrowser` (pages one type, registers every page) |
| `Downloads.kt` | `MediaDownloader` (Range/206 resume, `.part` + atomic rename), `DownloadQueue` (WorkManager), `DownloadWorker` (Hilt, foreground, stall resume), `TransferProgress`, `SpeedMeter` |
| `MediaModule.kt` | `RecorderHttp` (bound client, URL, Coil `ImageLoader` for recorder thumbnails), `ThmDecoderFactory`, Hilt module |
| `RecordingsViewModel.kt` / `RecordingsScreen.kt` | Routes `Recordings(tab)` and `SdFiles(category)`, selection, details and "Übertragungen" sheets |
| `ClipScreen.kt` | Route `Clip(mediaId, positionMs)`: Media3 player / image viewer, share (`MediaFileProvider`), delete targets, parent/children |
| `StorageScreen.kt` | Route `Storage` (Settings → Speicher) |
| `MediaGraph.kt` | `mediaGraph(navController, selectionActions, clipActions, clipExtras, clipDeleteTargets)` |
| `MediaUi.kt` | Thumbnail, tag, not-connected card, time/day/kind labels, notice texts |

## Decisions

**Library rows come from the listing.** Every 4100 page is registered (`upsertFromRecorderListing`) before it is shown, so
each listed file has a stable UUID (unique index on `recorderPath`) that downloads, the clip route and Phase 4 backup
use. Rows store the raw `fileTime`, `fileThm` and type; `recorderTimeEpochGuess` reads `fileTime` with the phone's zone
and is shown as a guess ("Zeiten laut Recorder, Zeitzone unbekannt"). Only a listing that ended *and* holds
`totalFileNum` files forgets recorder copies it no longer lists (`reconcileRecorderListing`: loop overwrite, deleted
elsewhere); rows whose download is queued or running are never touched; a stopped or short listing forgets nothing.
**Path reuse** (format, clock reset): a known path listed with another `fileTime` whose row has a phone or Drive copy is
another recording – the old row is detached (`recorderPath = null`, its copies stay) and a new row inserted. A listed
path without a row re-links a detached row with the same type, name and time.

**Three copies, never cascading.** `recorderPath` / `localUri` / `driveFileId` mark the three copies. `deleteLocalCopy`
deletes the phone file, its thumbnail and its companion file (screenshot JSON, `*.enhance.json`); `deleteOnRecorder`
sends 4101 with device paths and forgets only the recorder copy on rval 0; `markRecorderDeleted` (4101, `fileDel`,
reconcile) and `markDriveDeleted` (for drive-backup) do the same for their copy. A row is removed only when no copy is
left. Originals are never touched when derived outputs are registered or deleted, and deleting an original leaves its
children (their `parentId` stays; the clip screen says "Original nicht mehr vorhanden").

**Phone storage.** Downloads: `files/media/<id>/<recorder file name>` (the name is what share targets see; reduced to
`[A-Za-z0-9._-]`). Screenshots stay in `files/screenshots/` (feature/live-view), enhanced outputs in `files/enhance/`
(feature/enhance-core), thumbnails (≤ 320 px JPEG, video frame 0 or sampled image) in `files/thumbs/<id>.jpg`.
`localUri` is `File.toURI()` text (`file:/data/…`); read it with `MediaItem.localFile`. App-private storage only, no
gallery export (Android backup is off app-wide, so nothing leaves the phone).

**Paging** (report "Cursor-based browsing"): `ListFiles(type, lastFileName, 50)`, empty cursor on refresh, the next
cursor is the exact `fileName` of the last entry of the last page. The listing completes on an empty page, or on a page
shorter than 50 once the listed count reaches `totalFileNum` (or none is reported); a short page below the total asks
again, and a short page holding only already listed entries (inclusive cursor) completes. **Hardware 2026-10-02:**
the recorder answers `pageNum 50` with pages of **20** entries (`totalFileNum` 144 loop clips, 1997 events), so a
listing simply takes more requests; nothing to change. It stops (note "Die Liste endet hier …") when the last `fileName` of a page is missing or was already listed,
which catches a recorder that ignores the cursor or cycles. Entries already listed are dropped (cursor inclusivity is
unknown), entries without `fileName` are skipped. A failed page shows the raw code and waits for "Erneut versuchen"
(no polling). The next page is requested when the list is scrolled to within 8 items of its end.

**Order and grouping.** The recorder's order is kept (not established); consecutive entries of one day form a group
("Heute", "Gestern", localized date; "Datum unbekannt" for an unparsable `fileTime`). Rows show `HH:mm:ss` from the raw
time and the raw file name; details (tap on a file not on the phone) show raw type, raw time, the phone-zone reading,
path and thumbnail path. `totalFileNum` / `totalFileSize` are shown raw "laut Recorder"; the recorder sends no
`totalFileSize` (shown as "–").

**Tabs.** "Schleife" (type 0), "Vorfälle" (1), "Fotos" (2, the report's "user data", 3-column grid; `recorderType` stays
raw), plus **"Handy"** (addition): everything with a phone copy (downloads, screenshots, enhanced outputs), which works
without the recorder. Without a session the recorder tabs show the not-connected card with "Zur Verbindung". A type is
listed once per session when its tab is first shown; "Aktualisieren", `fileNew` / `fileDel` / `updateFileList` of that
type (or any type if `fileType` is unknown) and a reconnect list it again; listings are cleared on disconnect.
`fileDel` also forgets the recorder copy. A listed file whose row lost its recorder copy meanwhile (e.g. deleted from the
clip screen) disappears at once. `SdFiles(category)` (`NORMAL` / `EVENT` / `USER`) is the flat list of one type with the
recorder's counts; Recordings links to it ("Rohliste"); the SD card screen can link with `navigate(SdFiles("NORMAL"))`.

**Selection** (long press; back or ✕ ends it): recorder tabs → "Herunterladen", "Recorder-Kopie löschen" (4101,
`ConfirmDialog(danger)`, outcome-unknown errors refresh the listing); phone tab → "Handy-Kopie löschen" (danger when an
item has no other copy). Phase 4 adds actions through the `selectionActions` slot.

**One recorder request at a time (hardware 2026-10-02).** A 132 MB loop clip stalled after 3,276,800 bytes
(`SocketTimeoutException: timeout`, then again on the `Range` resume) while a second download and six `.thm` requests
of the Vorfälle tab (ended with `SocketException: Socket closed`) ran next to it and the recorder kept recording.
Working hypothesis: its HTTP server serves one connection at a time and slows down while writing the card. So: the
manager's OkHttp client (shared by downloads and thumbnails) has a `Dispatcher` with `maxRequests = 1` and
`maxRequestsPerHost = 1` (this limits enqueued calls, i.e. Coil's thumbnails; downloads use `execute()` and are kept
to one by their queue), `connectTimeout` 10 s, `readTimeout` 90 s, `writeTimeout` 30 s, no `callTimeout` (a 5-minute
clip takes minutes); thumbnails use a derived client with a 15 s read timeout (same dispatcher; `ponytail:` knob), so
one unanswered `.thm` does not block the others for 90 s. **Downloads first:** while any download runs (WorkManager
RUNNING, including the wait after a stall) the recorder rows and photo cells show their placeholder instead of
requesting a `.thm` (`RecorderEntry.thumb` is null; local thumbnails still show); they load when it ends. Keep-alive
is left on (no `Connection: close`; no keep-alive problem seen).

**Recorder HTTP only with a Ready session.** `RecorderHttp.client()` hands out `connectionManager.httpClient()` only while
the state is `Ready` and its network is the bound one (simulator mode: Ready only); otherwise
`RecorderNotReadyException` and nothing is requested, so another device answering at 192.168.42.1 on some Wi-Fi is never
asked. **Thumbnails** load through a media-only Coil `ImageLoader`: `OkHttpNetworkFetcherFactory` whose call factory asks
`client()` per request (a new or lost session applies at once; no session shows the placeholder), Coil's connectivity check off (the recorder Wi-Fi has no internet), service-loaded fetchers off
(nothing may load recorder URLs unbound). URL: `connectionManager.mediaUrl(path)`; in the debug simulator mode
`http://10.0.2.2:8080/<path>` (`MediaModule.SIMULATOR_BASE_URL`). The physical recorder's thumbnails are **`.thm`**
files next to the clip (`/sd/DCIM/ch1_20261002_091128_0782.thm`), served as `Content-Type: application/binary`, 3–6 KB. Coil decodes by
content (`BitmapFactory` / `ImageDecoder` sniff the bytes; its network fetcher takes the MIME type from the header
or extension but never rejects one) and nothing in `media/` filters on extension or MIME type, so a `.thm` that is a
JPEG loads as is. `ThmDecoderFactory` (first in the media loader) covers a JPEG behind a container header: a body
that does not start with `FF D8 FF` is searched for that marker and decoded from there; a body without any JPEG is
noted once in `notes.http` (`thumbnail: no JPEG in N bytes (application/binary), starts xx xx …`) and left to Coil
(placeholder). Rows and photo cells prefer the local thumbnail (`files/thumbs/<id>.jpg`, generated after the
download) when there is one: that is also the fallback should a `.thm` turn out not to be an image.

**HTTP request log.** The manager's OkHttp client (downloads and thumbnails) logs every request into the dashcam
diagnostics notes (`notes.http` of the Diagnose export, last 50, redacted): `GET /sd/DCIM/x.mp4 Range: bytes=1000-
-> 206 Content-Type: video/mp4 Content-Length: 5000`, or `-> SocketTimeoutException: timeout`. Only the path is
logged (no host, no query). A failed download adds `download <path>: <Exception>: <message>` (e.g.
`DownloadException: NOT_MEDIA (HTTP 200) Content-Type: text/html`); a stall adds `download <name>: stalled at <bytes>
bytes (SocketTimeoutException), attempt 1 of 10, resuming in 5 s`, a finished download `download <name>: done at
<bytes> bytes after <s> s, <n> stalls`.

**Downloads.** `DownloadQueue.enqueue(id)`: WorkManager unique work `media-download-<id>` (KEEP), no constraints (the
recorder Wi-Fi has no internet), linear backoff 15 s. At most **1** download is runnable on the recorder
(`MAX_PARALLEL`; 2 in the debug simulator mode, `SIMULATOR_PARALLEL`); more are enqueued *held* (tag
`media-held`, initial delay 10 years) and promoted (REPLACE without delay) when a slot frees: by the finishing worker
(success, failure, user cancel – a retried work keeps its slot), on queue start, on Ready and after a cancel. So no
worker ever runs waiting for a slot. Duplicates: a file already on the phone is never queued again (and `download()`
returns it without a request); the same recorder path maps to the same row. `MediaDownloader` writes `<name>.part` and
the announced size to `<name>.part.size`; with a part it asks `Range: bytes=<n>-` and appends only on a 206 whose
Content-Range starts at n and announces the same size (`If-Range` is not assumed); a 200 rewrites from 0; 416 or a 206
that does not fit restarts (a 200 to a range request restarts cleanly, `DownloadsTest`). **Lenient by design (the
recorder's headers are unverified):** any Content-Type except `text/html` is accepted, also none (an HTML page means
another web server answered, e.g. a captive portal: never saved, "Statt der Aufnahme kam eine Webseite", permanent);
Content-Length is optional: without it there is no length check and no `.part.size` (on a resume without
Content-Range total the remaining Content-Length gives the total, otherwise the part is appended unchecked). A
present `Content-Length: 0` or an empty body is incomplete. When sizes are known the length is checked against
Content-Length / Content-Range, then fsync, atomic rename, thumbnail, `markDownloaded`. 4xx (except 408/429) are
permanent. **Stalls** (`SocketTimeoutException`, `SocketException`: read timeout, socket closed or reset) resume
within the same worker run from the `.part` size after 5 s, 15 s, then 45 s (`DownloadWorker.STALL_BACKOFF_S`); the
sheet and the notification show "Übertragung ins Stocken geraten, neuer Versuch in N s · bisher 3,3 MB von 138 MB".
Each stall counts as a real failure; a stall after progress starts the count (and the backoff) afresh, so a long clip
that keeps moving is never given up, while 10 stalls in a row without a byte fail it. While data flows the sheet and
the notification show the speed over the last 5 s ("3,3 MB von 138 MB · 412 KB/s", `SpeedMeter`). **Attempts:** without a Ready session (`RecorderNotReadyException`,
`RecorderNotBoundException`) the worker returns `retry` without counting; only real failures count (SharedPreferences
`media_download_attempts`), the 10th fails the work. After process death or reboot WorkManager runs the work again; it
waits ("Wartet auf die Dashcam-Verbindung") and every waiting work still ENQUEUED is restarted as soon as a session is
Ready. **Cancel** ("Abbrechen" in the sheet or the notification) drops the part: the worker checks its own state in both
catch paths (CANCELLED and no successor, so a resume REPLACE keeps the part), on every Android version. The worker runs
as a `dataSync` foreground service with a German notification ("Download: <name>", "1,9 MB von 6,3 MB", progress,
"Abbrechen"); Android 13+ asks for the notification permission at the first download, transfers run without it.
`DownloadQueue.progress: StateFlow<Map<String, TransferProgress>>` feeds the row indicators and the "Übertragungen"
sheet (top bar of Recordings; queued, running with bytes, waiting, done → "Öffnen", failed with a German reason
(`DownloadFailure`: nicht mehr in der Bibliothek / nicht mehr auf der Dashcam / nicht geliefert / Webseite statt
Aufnahme / unvollständig / Verbindung) and a raw second line: "HTTP 404", "HTTP 200 · Content-Type: text/html" or
the exception "SocketTimeoutException: timeout" (`failureDetail()`, worker output `detail`), cancelled → "Erneut
versuchen"). A download waiting for its next attempt after a real failure shows that reason too ("Fehlgeschlagen: …
Neuer Versuch folgt." + the second line; `DownloadQueue.retryReason`, in memory, `ponytail:` gone after process
death until the next attempt); waiting only for a session still reads "Wartet auf die Dashcam-Verbindung".

**Clip.** Media3 `ExoPlayer` + `PlayerView` for phone copies of videos, seeking to `positionMs`; the position survives
rotation, playback pauses on `ON_STOP`. Photos, screenshots and enhanced frames use an image view. Without a phone copy:
local thumbnail + "Herunterladen". Header: day · kind ("Schleife", "Vorfall", "Foto", "Screenshot", …), the raw recorder
time "laut Recorder, Zeitzone unbekannt" (phone time for screenshots/outputs), tags "Vorfall", "verbessert –
rekonstruiert, kein Beweis" / "hochskaliert – …" for derived kinds, the copies; for derived items the
`*.enhance.json` sidecar (`EnhancementInfo.read`: engine as "KI-Modell"/"klassisch", model, factor). "Teilen" shares the phone copy through
`MediaFileProvider` (authority `${applicationId}.media.files`; `files/media`, `files/screenshots`, `files/enhance`).
"Löschen" asks which copy (Handy-Kopie / Recorder-Kopie / `DeleteTarget`s from the slot) and then shows that target's own
confirmation (rendered by the clip screen); deleting the last copy leaves the screen. Parent ("Original", opens at `parentPositionMs`) and children ("Daraus erzeugt") are linked.

**Storage** (Settings → "Speicher"): bytes per kind on this phone – Downloads (phone copies + interrupted `.part`),
Screenshots (imported first), Verbesserte Dateien, Cache (only the media image cache – Coil memory + disk; other files in
`cacheDir` such as the diagnostics export or the avatar preview are not touched), Kennzeichen-Ausschnitte
(`files/plates`), free space (`StatFs`). "Freigeben" (confirmation, danger except cache) deletes phone copies through
`deleteLocalCopy` only; recorder and Drive copies stay. When some of them have neither a recorder nor a Drive copy,
the confirmation says how many are then gone for good. Plate crops are only shown: they belong to the plate history
(`PlateRepository.clear()` in plates-ui), deleting the files alone would leave dangling `cropPath`s.

## Interfaces for Phase 4

```kotlin
// AxoNavHost: mediaGraph(navController, selectionActions = …, clipActions = …, clipExtras = …, clipOverlay = …, clipDeleteTargets = …)
typealias SelectionActions = @Composable RowScope.(items: List<MediaItem>, clearSelection: () -> Unit) -> Unit // "Sichern"
typealias ClipActions = @Composable (item: MediaItem, positionMs: Long) -> Unit   // "Bild verbessern", "Clip hochskalieren"
typealias ClipExtras = @Composable (item: MediaItem, positionMs: Long, seekTo: (Long) -> Unit) -> Unit // "Kennzeichen in diesem Clip"
typealias ClipOverlay = @Composable BoxScope.(item: MediaItem, positionMs: Long) -> Unit // over the 16:9 media box: plate boxes
data class DeleteTarget(val label: String, val enabled: Boolean, val title: String, val text: String, val danger: Boolean, val onConfirm: () -> Unit)
typealias ClipDeleteTargets = @Composable (item: MediaItem) -> List<DeleteTarget> // "Drive-Kopie löschen"; the clip screen renders chooser + confirmation

class MediaRepository {
  fun observe(id): Flow<MediaItem?>; fun observe(kind: MediaKind? = null, category: MediaCategory? = null): Flow<List<MediaItem>>
  fun observeLocal(); fun observeChildren(id); suspend fun get(id); suspend fun localItems(kinds)
  suspend fun registerDerived(kind: MediaKind, file: File, parentId: String, parentPositionMs: Long?, info: EnhancementInfo?): MediaItem
  suspend fun deleteLocalCopy(id); suspend fun deleteOnRecorder(paths: List<String>): RecorderResult<Unit>; suspend fun markRecorderDeleted(path)
  suspend fun markDriveDeleted(id)                                    // after deleteOnDrive: Drive columns cleared, row removed if no copy is left
  suspend fun update(id, transform: (MediaItem) -> MediaItem): MediaItem? // backup columns (state, driveFileId, driveMd5, backupError)
}
class DownloadQueue { val progress: StateFlow<Map<String, TransferProgress>>; suspend fun enqueue(mediaId): Boolean; suspend fun cancel(mediaId); suspend fun promote(excluding: UUID? = null) }
```
enhance-ui: register outputs with `registerDerived(ENHANCED_FRAME | UPSCALED_CLIP, output.file, sourceMediaId, positionMs,
output.info)`; the file name UUID becomes the item id. drive-backup: `MediaItem.localFile` is the file to upload;
`BackupQueue` can reuse the WorkManager/Hilt worker setup (`MyForthingApp` is the `Configuration.Provider`).

## Screenshot folder contract (consumer side)

feature/live-view writes, feature/media imports:
- `files/screenshots/<uuid>.jpg`, complete before the JSON appears (write to a temp name and rename).
- then `files/screenshots/<uuid>.json` (also temp + rename): `{ "id": "<uuid>", "capturedAt": <epoch ms> | "<ISO-8601
  with offset or Z>", "source": "live", "width": 1280, "height": 720 }`. The JSON is the commit marker: a JPEG without
  JSON is ignored. `id` should equal the file name; when blank the file name is used. `width` / `height` / `source` are
  not stored (no §8 field).
- Import: when Recordings opens (`importScreenshots()`) and while the process lives via `FileObserver`
  (`CLOSE_WRITE` / `MOVED_TO` of `*.json`). Known ids are skipped. Item: `kind = SCREENSHOT`, `category = UNKNOWN`,
  `createdAt = capturedAt`, local thumbnail generated.
- Deleting the phone copy deletes the JPEG and the JSON (so it is not imported again). live-view must not rewrite or
  reuse a `<uuid>` once its JSON exists. The gallery copy live-view writes to `Pictures/` (Android 10+) is not part of
  the library and is never touched.
- Matches the producer side as merged in `main` (CONTRACTS §7a, `docs/features/live.md`): `*.tmp` + rename (the JSON's
  rename arrives as `MOVED_TO`), `capturedAt` ISO-8601 with offset.

## Simulator HTTP (debug, emulator)

`./gradlew :recorder:runSimulator` now starts both servers (test fixtures): the TCP recorder on `127.0.0.1:7878` and
`SimulatorHttpServer` on `127.0.0.1:8080` (`com.sun.net.httpserver`, no auth, GET/HEAD, `Range: bytes=n-[m]` → 206 with
Content-Range, past the end → 416, otherwise 200). Slow downloads for resume tests: `-PsimThrottle=262144` (bytes per
second). Other ports: `--args="<local.properties> <tcpPort> <httpPort> <throttle>"`. When the HTTP port is busy the
simulator still starts (listings work, downloads and thumbnails do not) and says so.
`SimulatedFiles` is the fictional card: 120 loop clips (6 pages, across midnight), 3 incidents, 12 photos, newest first,
exclusive cursor, unknown cursor → empty page, at most 20 entries per page whatever `pageNum` asks and no
`totalFileSize` (both as the physical recorder), 4101 removes files (rval 107 if none existed).
Videos are the committed 3-second `recorder/src/testFixtures/resources/sim/clip.mp4` (16.6 KB, recorded on the emulator
with `screenrecord`) padded with an MP4 `free` box to 6 MiB / 3 MiB, so they play and take time to download; JPEGs
(thumbnails 320×180, photos 1280×720) are drawn with `java.awt` at runtime. Thumbnails are `<name>.thm` (JPEG bytes,
served as `application/octet-stream`) like on the physical recorder. `RecorderSimulator.handlers[msgId]`
computes replies from the request (used for 4100/4101).
Debug app: Verbindung → Entwickler → "Simulator (10.0.2.2:7878)"; media then load from `http://10.0.2.2:8080`.
`src/debug/res/xml/network_security_config.xml` allows cleartext to `10.0.2.2` in debug builds only (release keeps the
recorder address only); keep its `192.168.42.1` entry identical to `src/main`.

## Validation

Unit tests (`me.ri3d.dashcam.media.*`, Robolectric, [SIM] = real connection manager + `RecorderSimulator`):
migration 2→3 from the exported v2 schema; `Listing.append` (exact cursor, short/empty page, inclusive cursor, repeated /
cycling / missing cursor); 120 simulated files in 3 requests with exact cursors [SIM]; cursor-ignoring recorder stopped
after 2 requests [SIM]; failed page waits for retry [SIM]; MockWebServer downloads: 206 resume with `Range`, 200 restart,
non-continuing 206 discards the part, interrupted body keeps the part and resumes, 404 permanent; WorkManager queue:
single download, no second copy, cancel drops the part; repository: upsert idempotence, delete targets non-cascading
(phone / recorder / Drive), 4101 body and failure [SIM], reconcile, screenshot import (JSON marker, once, deletion),
derived registration (link, category, idempotent, parent untouched, sidecar deleted); view models: tab listing on Ready /
cleared on disconnect, `fileNew` / `fileDel` refresh, selection + 4101, stale entry hidden, phone tab offline, clip
delete targets, sidecar and parent/children. `:recorder`: simulated paging, delete, HTTP 200/206/416/404.
After the review: no request without a Ready session; a worker without session returns `retry` beyond the attempt
limit; real failures fail after 10 with reason and HTTP code; 416 restarts; an HTML answer is never saved; a resume
announcing another size restarts; short page below `totalFileNum` keeps paging, no reconcile below the total [SIM],
inclusive cursor alone completes; reconcile leaves rows in transfer; path reuse detaches, a listed file re-links;
`markDriveDeleted` keeps the phone file; deleting an original keeps derived children; storage counts last copies.
Since `fix/real-recorder-1`: Content-Type rule (HTML refused, other / none accepted); a chunked download without
Content-Length and with `text/plain` completes and leaves no `.part.size`; the HTTP request log line and the noted
failure with its Content-Type; a waiting download carries the last failure and its detail; worker output `detail`.
Since `fix/real-recorder-2`: a connection reset after 1000 bytes resumes within the same worker run with
`Range: bytes=1000-` after 5 s (virtual time) and notes the stall and the finish; the speed window; 120 simulated
files in 6 pages of 20 (asked for 50) with exact cursors, reaching `totalFileNum` without `totalFileSize` [SIM].

Emulator (`emulator-5554`, API 36) against `:recorder:runSimulator` [SIM], throttled to 256 KiB/s: all three recorder
tabs with thumbnails (loop paged in 3 requests with the exact cursors, "Heute"/"Gestern", incidents tagged, photos grid);
not-connected state; download with notification ("Download: …", "1.9 MB von 6.3 MB"); `am force-stop` at 2 031 616 bytes
→ relaunch → work rescheduled, "Wartet auf die Dashcam-Verbindung" → simulator connected → `Range: bytes=2031616-` →
206 → file byte-identical; player (4 s clip plays); share sheet opened and dismissed; "Recorder-Kopie löschen" → 4101
with the device path, phone copy kept; selection download (2 parallel); phone tab with local thumbnails; Storage
"Freigeben" for downloads (files and thumbnails gone, recorder rows kept, row of a file without other copies removed).

## Simulated vs needs the real recorder

**Verified on hardware 2026-10-02** (owner's Diagnose export, AE-DC2013-LQ2, fw SX5G-3776510A_A): file paths follow
`/sd/DCIM/ch1_YYYYMMDD_HHMMSS_NNNN.mp4` with the thumbnail `/sd/DCIM/ch1_YYYYMMDD_HHMMSS_NNNN.thm` next to it;
`fileNew {fileType 0, fileName, fileThm, fileTime "2026-10-02 09:11:28"}` and `fileDel {fileType 0, fileName}` arrive
unsolicited (frame sequence 0xFFFFFFFF, read as -1) while loop recording; capability 20481 reports
`downloadPath "http://192.168.42.1:80"` (= `mediaUrl`); the recorder clock (4098 `dateTime`) ran ≈ 2.5 min ahead of
the phone, so `fileTime` is the recorder's clock, not the phone's. **Downloads did not work** in that build (exact
error not captured); the lenient checks, the failure line and `notes.http` are the fix and the instrument.

Second test the same day (build from `fix/real-recorder-1`, phone API 37, mobile data off, session `Ready` with
keepalives every 4 s throughout):
- **Listing works.** 4100 answers `pageNum 50` with pages of **20**; `totalFileNum` 144 (type 0) / 1997 (type 1);
  no `totalFileSize`. Loop clips in `/sd/DCIM/`, events in `/sd/EVENT/` named `ch1_YYYYMMDD_HHMMSS_NNNNG.mp4`
  (`G` suffix).
- `.thm` thumbnails: `200 Content-Type: application/binary`, 3–6 KB (presumably JPEG; not yet seen decoded).
- Clip download: `GET /sd/DCIM/ch1_20261002_091628_0783.mp4 -> 200 Content-Type: application/binary
  Content-Length: 138152548` – 132 MB for a 5-minute clip, ≈ 3.7 Mbit/s of video. It **stalled** with
  `SocketTimeoutException: timeout` (then a 10 s read timeout) after 3,276,800 bytes; the resume `Range:
  bytes=3276800-` timed out too; meanwhile six `.thm` requests ended with `SocketException: Socket closed`. The
  recorder kept recording (`recStatus` 1). The owner saw it as "bricht mit WLAN ab". Changes: one request at a time,
  thumbnails paused during downloads, 90 s read timeout, in-run stall resume (above).

Still to check on the car (read-only first: browse, then one download, one delete):
0. **Next test first:** download one loop clip (nothing else running; leave the Recordings screen open), watch the
   Übertragungen sheet (speed, any "ins Stocken geraten" countdown), then Diagnose → `notes.http`: the `GET … Range:
   bytes=…-> 206 …` resumes, `download …: stalled at …` lines (how often, how far apart in bytes), `download …: done
   at … after … s` (throughput = bytes / seconds), and no `.thm` requests between them. Then once with the recorder
   **not** recording (parking mode or SD idle, if possible) for comparison.
1. Paging: order of entries, whether `lastFileName` is exclusive or inclusive, behaviour for an unknown cursor,
   duplicates across pages (page size 20 verified).
2. Totals: `totalFileNum` per type verified; `totalFileSize` is not sent.
3. `fileName` / `fileThm` paths: loop clips and events verified (above); photos (`fileType` 2) not yet seen; whether
   the `.thm` is a JPEG (thumbnail shown; else the `thumbnail: no JPEG …` note).
4. HTTP on port 80: `Range` support (206 with Content-Range) or 200 only (then every resume restarts), `If-Range` /
   ETag, authentication (none traced), throughput with and without recording, whether a second connection is
   refused or slows the first, keep-alive (Content-Type `application/binary` and Content-Length verified).
5. Type 2 ("user data"): photos only, or videos too (kind is decided by the file extension).
6. `fileTime` format (verified `yyyy-MM-dd HH:mm:ss`) and the recorder's time zone (phone zone assumed and labelled;
   the recorder clock drifts, ≈ +2.5 min on 2026-10-02).
7. 4101: rval for success and for a missing file (107 assumed meaningful), multiple paths in one request, whether the
   recorder sends `fileDel` afterwards.
8. `fileNew` / `fileDel` arrive (verified, `fileType` 0 for loop clips); `pathType` and `updateFileList` are
   unverified; frequency while loop recording (each one re-lists that type) and whether `fileType` equals the listing type
   for events and photos.
9. Loop overwrite while browsing: a listed file that vanishes before it is downloaded (download fails with HTTP 404,
   permanent).

## Limits

- `ponytail:` notes in code: held downloads start in WorkManager's order, not strictly first in, first out; no share
  target is not reported.
- Phone copies live under app storage; uninstalling the app deletes them (by design, as the offline profile).
- A notification re-lists the whole type from the first page; the scroll position resets.
- Streaming directly from the recorder (without download) is not offered.
- Downloads keep the row of a file that the recorder overwrote between listing and download; the 404 marks it failed.
