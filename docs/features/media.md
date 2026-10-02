# Media (feature/media)

Package `me.ri3d.dashcam.media`. Implements CONTRACTS §8 on top of the connection manager (§7) and `:recorder` (§6).

| File | Contents |
| --- | --- |
| `MediaItem.kt` | Room entity `media_item` (§8 fields exactly), `MediaKind`, `MediaCategory`, `BackupState`, `MediaDao` |
| `MediaRepository.kt` | Library: observe, `upsertFromRecorderListing`, `reconcileRecorderListing`, `registerDerived`, `importScreenshots` / `watchScreenshots`, `markDownloaded`, `deleteLocalCopy`, `deleteOnRecorder`, `markRecorderDeleted`, `markDriveDeleted`, `update`; local thumbnails |
| `RecorderListing.kt` | `Listing.append` (cursor paging fold), `RecorderBrowser` (pages one type, registers every page) |
| `Downloads.kt` | `MediaDownloader` (Range/206 resume, `.part` + atomic rename), `DownloadQueue` (WorkManager), `DownloadWorker` (Hilt, foreground), `TransferProgress` |
| `MediaModule.kt` | `RecorderHttp` (bound client, URL, Coil `ImageLoader` for recorder thumbnails), Hilt module |
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
again (the recorder may page in smaller batches), and a short page holding only already listed entries (inclusive
cursor) completes. It stops (note "Die Liste endet hier …") when the last `fileName` of a page is missing or was already listed,
which catches a recorder that ignores the cursor or cycles. Entries already listed are dropped (cursor inclusivity is
unknown), entries without `fileName` are skipped. A failed page shows the raw code and waits for "Erneut versuchen"
(no polling). The next page is requested when the list is scrolled to within 8 items of its end.

**Order and grouping.** The recorder's order is kept (not established); consecutive entries of one day form a group
("Heute", "Gestern", localized date; "Datum unbekannt" for an unparsable `fileTime`). Rows show `HH:mm:ss` from the raw
time and the raw file name; details (tap on a file not on the phone) show raw type, raw time, the phone-zone reading,
path and thumbnail path. `totalFileNum` / `totalFileSize` are shown raw "laut Recorder".

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

**Recorder HTTP only with a Ready session.** `RecorderHttp.client()` hands out `connectionManager.httpClient()` only while
the state is `Ready` and its network is the bound one (simulator mode: Ready only); otherwise
`RecorderNotReadyException` and nothing is requested, so another device answering at 192.168.42.1 on some Wi-Fi is never
asked. **Thumbnails** load through a media-only Coil `ImageLoader`: `OkHttpNetworkFetcherFactory` whose call factory asks
`client()` per request (a new or lost session applies at once; no session shows the placeholder), Coil's connectivity check off (the recorder Wi-Fi has no internet), service-loaded fetchers off
(nothing may load recorder URLs unbound). URL: `connectionManager.mediaUrl(path)`; in the debug simulator mode
`http://10.0.2.2:8080/<path>` (`MediaModule.SIMULATOR_BASE_URL`).

**Downloads.** `DownloadQueue.enqueue(id)`: WorkManager unique work `media-download-<id>` (KEEP), no constraints (the
recorder Wi-Fi has no internet), linear backoff 15 s. At most 2 downloads are runnable; more are enqueued *held* (tag
`media-held`, initial delay 10 years) and promoted (REPLACE without delay) when a slot frees: by the finishing worker
(success, failure, user cancel – a retried work keeps its slot), on queue start, on Ready and after a cancel. So no
worker ever runs waiting for a slot. Duplicates: a file already on the phone is never queued again (and `download()`
returns it without a request); the same recorder path maps to the same row. `MediaDownloader` writes `<name>.part` and
the announced size to `<name>.part.size`; with a part it asks `Range: bytes=<n>-` and appends only on a 206 whose
Content-Range starts at n and announces the same size (`If-Range` is not assumed); a 200 rewrites from 0; 416 or a 206
that does not fit restarts. A response with a Content-Type other than `video/*`, `image/*` or `application/octet-stream`
(a missing type is accepted: unverified) is never saved ("keine Aufnahme", permanent); an empty body is incomplete. The
length is checked against Content-Length / Content-Range, then fsync, atomic rename, thumbnail, `markDownloaded`. 4xx
(except 408/429) are permanent. **Attempts:** without a Ready session (`RecorderNotReadyException`,
`RecorderNotBoundException`) the worker returns `retry` without counting; only real failures count (SharedPreferences
`media_download_attempts`), the 10th fails the work. After process death or reboot WorkManager runs the work again; it
waits ("Wartet auf die Dashcam-Verbindung") and every waiting work still ENQUEUED is restarted as soon as a session is
Ready. **Cancel** ("Abbrechen" in the sheet or the notification) drops the part: the worker checks its own state in both
catch paths (CANCELLED and no successor, so a resume REPLACE keeps the part), on every Android version. The worker runs
as a `dataSync` foreground service with a German notification ("Download: <name>", "1,9 MB von 6,3 MB", progress,
"Abbrechen"); Android 13+ asks for the notification permission at the first download, transfers run without it.
`DownloadQueue.progress: StateFlow<Map<String, TransferProgress>>` feeds the row indicators and the "Übertragungen"
sheet (top bar of Recordings; queued, running with bytes, waiting, done → "Öffnen", failed with a German reason
(`DownloadFailure`: nicht mehr in der Bibliothek / nicht mehr auf der Dashcam / nicht geliefert / keine Aufnahme /
unvollständig / Verbindung) and the raw HTTP code as a second line, cancelled → "Erneut versuchen").

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
`SimulatedFiles` is the fictional card: 120 loop clips (3 pages, across midnight), 3 incidents, 12 photos, newest first,
exclusive cursor, unknown cursor → empty page, 4101 removes files (rval 107 if none existed), `totalFileSize` in KiB.
Videos are the committed 3-second `recorder/src/testFixtures/resources/sim/clip.mp4` (16.6 KB, recorded on the emulator
with `screenrecord`) padded with an MP4 `free` box to 6 MiB / 3 MiB, so they play and take time to download; JPEGs
(thumbnails 320×180, photos 1280×720) are drawn with `java.awt` at runtime. `RecorderSimulator.handlers[msgId]`
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

Emulator (`emulator-5554`, API 36) against `:recorder:runSimulator` [SIM], throttled to 256 KiB/s: all three recorder
tabs with thumbnails (loop paged in 3 requests with the exact cursors, "Heute"/"Gestern", incidents tagged, photos grid);
not-connected state; download with notification ("Download: …", "1.9 MB von 6.3 MB"); `am force-stop` at 2 031 616 bytes
→ relaunch → work rescheduled, "Wartet auf die Dashcam-Verbindung" → simulator connected → `Range: bytes=2031616-` →
206 → file byte-identical; player (4 s clip plays); share sheet opened and dismissed; "Recorder-Kopie löschen" → 4101
with the device path, phone copy kept; selection download (2 parallel); phone tab with local thumbnails; Storage
"Freigeben" for downloads (files and thumbnails gone, recorder rows kept, row of a file without other copies removed).

## Simulated vs needs the real recorder

Verified only against the simulator [SIM]; to check on the car (read-only first: browse, then one download, one delete):
1. Paging: order of entries, whether `lastFileName` is exclusive or inclusive, behaviour for an unknown cursor, maximum
   page size, whether short pages really mean the end, duplicates across pages.
2. Totals: `totalFileNum` per type, unit of `totalFileSize` (shown raw).
3. `fileName` / `fileThm` paths: real directory layout, whether `fileThm` is always set and served, JPEG size.
4. HTTP on port 80: `Range` support (206 with Content-Range) or 200 only (then every resume restarts), `Content-Length`
   present, `If-Range` / ETag, authentication (none traced), parallel downloads (2 at once), throughput, keep-alive.
5. Type 2 ("user data"): photos only, or videos too (kind is decided by the file extension).
6. `fileTime` format and the recorder's time zone (phone zone assumed and labelled).
7. 4101: rval for success and for a missing file (107 assumed meaningful), multiple paths in one request, whether the
   recorder sends `fileDel` afterwards.
8. `fileNew` / `fileDel` / `updateFileList` notifications: whether they arrive, `fileType` values (assumed = listing
   type), `pathType`, frequency while loop recording (each one re-lists that type).
9. Loop overwrite while browsing: a listed file that vanishes before it is downloaded (download fails with HTTP 404,
   permanent).

## Limits

- `ponytail:` notes in code: held downloads start in WorkManager's order, not strictly first in, first out; no share
  target is not reported.
- Phone copies live under app storage; uninstalling the app deletes them (by design, as the offline profile).
- A notification re-lists the whole type from the first page; the scroll position resets.
- Streaming directly from the recorder (without download) is not offered.
- Downloads keep the row of a file that the recorder overwrote between listing and download; the 404 marks it failed.
