# Plates (feature/plates-core)

On-device plate recognition for live frames and saved clips, a local searchable history linked to recording + position, and the sidecar hook for the backup. The screens (feature/plates-ui) are described in the section "UI (feature/plates-ui)" at the end; `FeatureFlags.plates` is `true`.

## API for the UI phase (`me.ri3d.dashcam.plates`)

```kotlin
data class PlateDetection(val text: String, val normalized: String, val confidence: Float?, val box: RectF, val frameTimestampMs: Long, val format: PlateFormat)
enum class PlateFormat { GERMAN, GENERIC }
interface PlateRecognizer { suspend fun recognize(frame: Bitmap, timestampMs: Long): List<PlateDetection>; fun close() }
class MlKitPlateRecognizer(maxWidth: Int = 1280) : PlateRecognizer          // Hilt: unscoped PlateRecognizer, caller closes

@Singleton class PlateRepository {                                           // Hilt
  fun history(): Flow<List<Plate>>                                           // newest first
  fun search(query: String): Flow<List<Plate>>                               // partial match on normalized, e.g. "BMK" or "4821"
  fun plate(id: Long): Flow<PlateWithSightings?>                             // sightings unordered, each with its own display
  suspend fun recordSightings(detections, source: SightingSource, mediaId: String? = null, positionMs: Long? = null,
                              frame: Bitmap? = null, seenAt: Long = now, frameScale: Float = 1f): Int // sightings written
  suspend fun forgetClip(mediaId: String)                                    // drop in-memory dedupe state after a scan
  suspend fun clear()                                                        // rows + every crop
  suspend fun clearForMedia(mediaId: String)                                 // sightings of one recording + their crops
}
class Frame(val bitmap: Bitmap, val timestampMs: Long)                       // software ARGB_8888 bitmap
data class ProcessingStats(val processedFps: Float, val avgMs: Float, val busyFraction: Float, val dropped: Long)
class AdaptiveThrottle(budget: Float = 0.3f)
class LivePlateProcessor(recognizer, repository: PlateRepository?, scope, throttle = AdaptiveThrottle()) {
  val detections: StateFlow<List<PlateDetection>>; val stats: StateFlow<ProcessingStats>
  val wantsFrame: Boolean                                                    // check before grabbing a bitmap from the player
  fun submit(frame: Frame): Boolean                                          // false = dropped (busy, throttled, or closed)
  fun close()
}
class ClipPlateScanner {                                                     // Hilt
  suspend fun scan(uri: Uri, mediaId: String, fps: Int = 2, clipStartMs: Long? = null, onProgress: (ScanProgress) -> Unit = {}): ScanSummary
}                                                                            // boxes in video pixels; last progress = 1.0
class PlateExport { suspend fun forMedia(mediaId: String): List<SidecarPlate> }  // Hilt; empty unless backupIncludePlateMetadata
```

Room (`AppDatabase` v2, migration 1→2 in `AppDatabase.MIGRATION_1_2`, registered through `AppDatabase.MIGRATIONS`, schema `app/schemas/…/2.json`):
`plate(id, normalized UNIQUE, display, firstSeen, lastSeen, count)`, `plate_sighting(id, plateId → plate ON DELETE CASCADE, display, mediaId, positionMs, source LIVE|CLIP, seenAt, confidence, cropPath, boxLeft/Top/Right/Bottom)`, indices on `normalized`, `plateId`, `mediaId`, `seenAt`.

Additions to CONTRACTS §11 (documented deviations): `PlateDetection.format`; `PlateSighting.display` (each sighting keeps its own reading; the sidecar `text` is that reading) and the box columns (sidecar `box`); `recordSightings(…, frame, seenAt, frameScale)` (crop source, the road time for clips: `seenAt = clipStart + positionMs` when known, and the crop scale when a clip frame was decoded smaller than the video); `confidence` is averaged over the plate's **characters** (ML Kit symbols), not its elements. Crops: `filesDir/plates/<uuid>.jpg`, ≤ 320 px wide, JPEG 85, `cropPath` relative to `filesDir` (same convention as `LocalProfile.avatarPath`); a failed or cancelled write deletes its crop.

**History merging:** sightings merge into a plate by `normalized` (the OCR's own uppercase alphanumerics, separators and `?` marks do not count). A reading with `?` keeps its own `display` on the sighting; the plate's `display` is the first reading and is replaced by the first later reading without `?`, never the other way round. Because `normalized` keeps the OCR's guess for an unsure glyph, an unsure reading only merges with the clean one when that guess was right: `B ?K 4821` (guess M) joins `B MK 4821`, but `HH? JK 553` (seal guessed as S, key `HHSJK553`) and `?-MK 4821` (key `8MK4821`) are separate entries from `HH JK 553` / `B-MK 4821`.

Dedupe: a plate detected again within 2 s of its previous detection is one sighting (sliding: a plate in view for 10 s live is one sighting, not five). LIVE compares wall time; CLIP compares the position inside the same clip and additionally skips a position within 2 s of an existing sighting of that plate in that clip, so scanning a clip again (any fps) adds nothing.

## Library and licence

- `com.google.mlkit:text-recognition:16.0.1` (Text Recognition v2, Latin, **bundled** model). 16.0.1 is the newest release on Google Maven (Aug 2024). Works offline from the first launch; the unbundled Play-services variant would download the model on first use, so it was not chosen. Passes `checkDebugAarMetadata` with compileSdk 36.
- Licence: [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) (Google proprietary SDK, free of charge, not open source).
- Privacy note: frames and recognised text never leave the phone through this code. ML Kit itself merges `INTERNET`, `ACCESS_NETWORK_STATE` and Google `datatransport` (CCT) services into the manifest; per its terms ML Kit sends API usage/performance metrics to Google (no image content). Whether to suppress that (manifest `tools:node="remove"` of the transport services) is an owner decision, not done here.
- **APK size (for the release phase):** the OCR native library `libmlkit_google_ocr_pipeline.so` is 11.1 MB (arm64-v8a), 6.8 MB (armeabi-v7a), 11.6 MB (x86) and 11.6 MB (x86_64) uncompressed, **41 MB across the four ABIs**, plus ~1.5 MB of models; the debug APK is 58 MB. The release build should set `abiFilters` to arm64-v8a/armeabi-v7a or ship an AAB.
- API facts checked in the AAR (`play-services-mlkit-text-recognition-common` 19.1.0): `Text.Line/Element/Symbol.getConfidence()` return a primitive `float`, never null. Measured: v2 Latin fills them (single glyphs 0.07–0.98).
- District codes: `GermanDistrictCodes.kt`, the 769 Unterscheidungszeichen of Kraftfahrt-Bundesamt, ["Kfz-Kennzeichen und auslaufende Kennzeichen in Deutschland", Stand 16.04.2026](https://www.kba.de/SharedDocs/Downloads/DE/Presse/kfz_kennzeichenliste_faltblatt.pdf) (sections "Festgelegte" and "Aufgehobene Unterscheidungszeichen"), extracted with `pdftotext -layout`. Special series without a letter group (Y, THW, BP, diplomatic 0) never match the pattern and are not listed. Update the set when the KBA publishes a new list.

## Recognition rules (`PlateText`, `MlKitPlateRecognizer`)

1. Frames wider than 1280 px are downscaled before OCR; boxes are mapped back to the caller's frame (clip boxes to video pixels).
2. Per ML Kit symbol: a glyph scored **< 0.4** is unsure and shown as `?` (its OCR guess is kept for `normalized`). An unsure glyph whose **ink** is coloured (EU band, coloured stickers; plate characters are black) becomes a separator instead, unless most glyphs of the line are coloured (green/red plates). Ink = pixels darker than 0.8 × the glyph box's median brightness, or the darkest 10 % for thin glyphs and the evenly blue band, so black characters on yellow plates are not coloured. A letter at least as wide as it is high (merged seal, measured 1.09–2.09 vs ≤ 0.91 for plate letters) adds a possible seal gap that is tried as a separator and as nothing.
3. A confidently read **lowercase** letter rejects the text (plates are uppercase; signs and ads are mostly mixed case).
4. Text is uppercased, separators unified (`-` if a dash was read, else space); `normalized` = uppercase A–Z, 0–9, ÄÖÜ of the OCR reading.
5. German: an official **district code** (1–3 letters, `?` as wildcard), a **visible boundary** (separators and at most one `?` between letters), 1–2 letters, optional separator, 1–4 digits with **at least one read digit**, optional H/E; at most 8 characters before the suffix, a boundary `?` not counted. Joined text such as `BMK 4821` is not German (indistinguishable from `BUS 42` = B-US 42, `RAST 500` = RA-ST 500); it can still pass as generic, and the recognizer splits a merged seal (rule 2).
6. **Seal rule:** an unsure glyph with only letters before it and a letter after it is left out as the seal (registration/inspection stickers between city code and letters) **only if its OCR guess is not a letter** (seals come out as `8`, `3`, `&`). A glyph guessed as a letter stays `?` and the reading stays uncertain (`K?LT 207`, `OF? NB 512`). Any reading that left out a glyph (seal or coloured) has no confidence.
7. Ambiguity: if the reading fits only after O↔0 / I↔1 / B↔8 swaps (at most 2 unsure or swapped characters, each swap must fit its block), the swapped positions are shown as `?` and nothing is corrected: `8-MK 4821` → display `?-MK 4821`, normalized `8MK4821`.
8. Generic EU fallback (`format = GENERIC`): 2–3 blocks, letters and digits, 5–9 characters (two-block readings ≥ 7: the two-block EU formats PL, I, E, UK have 7), no unreadable glyph, letter-only blocks ≤ 3 characters.
9. Per OCR line, every window of up to 4 elements is tried; overlaps resolve German > generic, then more elements, then certain > uncertain.

**What `confidence` means:** ML Kit's own per-character recognition score (model output, not calibrated, not a probability), averaged over the plate's characters, present only when every character of the reading was read with a score and nothing was left out. It is **null** when any character is `?`, a glyph was left out (seal, coloured glyph), or ML Kit reports no score (0 or NaN). **It does not separate right from wrong readings:** in the evaluation wrong readings scored 0.76–0.90 (before the review fixes) and 0.84 (after), right ones 0.68–0.93 / 0.84–0.93. The UI must not show it as a percentage or probability; at most use "has confidence" vs "uncertain (`?`)".

## Synthetic evaluation (no real footage yet)

Instrumented `PlateEvaluationTest` renders a deterministic set with `Canvas`: 44 plates on 1920×1080 dashcam-like scenes (EU band with stars, the two seals, fictional German plates in five system fonts, widths 140–420 px, one yellow NL plate, one green-ink German plate) and 16 negatives (12 street/shop/ad texts, 4 uppercase hard negatives incl. `TAXI 4711` on yellow and on white). System fonts stand in for the FE-Schrift; the renderer is mine, so these numbers show the filter works as designed, not real-world accuracy.

Categories per positive: **exact**; **`?`-consistent** (every `?` stands for one character, the rest matches); **seal shown as `?`** (the `?` stands where the seal is, i.e. for no plate character: consistent once that `?` is removed; uncertain, no confidence, but `normalized` carries the OCR's guess for the seal); **missed**. Wrong detections are readings that fit none of these.

Result after the review fixes (identical in both runs; emulator `Pixel_10_Pro_XL` AVD, API 36, x86_64, 4 vCPU on an i9-14900K host, shared with another agent):

| condition | n | exact | `?`-consistent | seal shown as `?` | missed | wrong detections |
| --- | --- | --- | --- | --- | --- | --- |
| clean | 8 | 7 | 0 | 1 | 0 | 0 |
| small (140–240 px wide) | 4 | 1 | 1 | 2 | 0 | 0 |
| skew / perspective / rotation | 5 | 2 | 0 | 2 | 1 | 1 (`L006 AX 1` generic, no confidence) |
| blur (defocus ×3/×4, motion 12/24 px) | 4 | 1 | 0 | 1 | 2 | 0 |
| noise | 4 | 3 | 0 | 1 | 0 | 0 |
| night (contrast 0.3, noise) | 4 | 4 | 0 | 0 | 0 | 0 |
| occlusion (20–50 % covered) | 3 | 0 | 0 | 0 | 3 | 1 (`B MK 48`, no confidence) |
| EU generic (F, PL, I, E, NL yellow) | 5 | 5 | 0 | 0 | 0 | 0 |
| green ink | 1 | 1 | 0 | 0 | 0 | 0 |
| H/E suffix | 2 | 1 | 1 | 0 | 0 | 0 |
| umlaut city code (TÜ, MÜ) | 2 | 1 | 0 | 1 | 0 | 0 |
| two-line (motorbike) | 2 | 0 | 0 | 0 | 2 | 0 |
| negatives | 16 | – | – | – | – | 1 (`TGX 18 510` generic) |

| metric | before review (42 + 15) | after review (44 + 16) |
| --- | --- | --- |
| recall strict (exact or `?`-consistent) | 35/42 = 0.83 (34 exact) | 28/44 = 0.64 (26 exact) |
| recall incl. seal shown as `?` | – | 36/44 = 0.82 |
| precision strict | 35/42 = 0.83 | 28/39 = 0.72 |
| precision counting seal-as-`?` readings | – | 36/39 = 0.92 |
| negatives with a detection | 2/15 (`ZONE 30`, `TGX 18 510`) | 1/16 (`TGX 18 510`) |
| detections with a confidence | 37/42 | 13/39 |

- The strict recall drop is the price of the stricter seal rule: 8 plates whose seal ML Kit guessed as a letter (`S`, `O`, `E`) are now shown as `K?LT 207`, `N?PQ 45`, `TÜ? AB 123` … instead of being silently cleaned up. They are found and marked, but their history key contains the guess (see History merging). Most clean readings now have no confidence because their seal glyph was left out.
- Before the glyph rules the very first run gave recall 15/42 and precision 15/46: OCR read the seal as a character on every clean plate (`B8MK`, `FSZO`, `HH3JK`) and 6/12 mixed-case negatives were accepted. The 0.4 threshold comes from that run's symbol scores: seal/band glyphs 0.07–0.36, plate characters mostly ≥ 0.5 (lowest 0.30–0.43 on small/skewed plates).
- Test guards: no reading with `?` has a confidence; clean ≥ 6/8 exact; recall incl. seal-as-`?` ≥ 0.75; ≤ 3 negatives with a detection.

## Measured timings (emulator, indicative only)

x86_64 emulator numbers run on a desktop CPU and say nothing reliable about a phone; they only compare settings with each other. The emulator instance was restarted during the review fixes; the post-review numbers below are from that instance and are higher than the earlier runs on the previous instance (1280 px: avg 47–101 ms), so compare within a row only.

| measurement | result (2 runs after the review fixes) |
| --- | --- |
| `PlateBenchmark.widths`, OCR + filter per 1920×1080 frame | 1280 px: avg 93–95 ms, p95 152–176 ms, recall strict 28/44 + 8 seal-as-`?` · 960 px: avg 63–66 ms, p95 97–124 ms, 27/44 + 8 · 640 px: avg 73–93 ms, p95 143–260 ms, 29/44 + 6 |
| first `recognize` in a fresh process (model init included) | 164–1127 ms |
| `PlateBenchmark.liveThrottle` (30 fps offered for 6 s, budget 0.30, 1920×1080 frames) | 3.6 frames/s processed, 75–78 ms/frame, busy share 0.27–0.28, 155 of 176 frames dropped |
| `ClipPlateScanner` on the generated 3 s 1280×720 H.264 clip at 2 fps | decode (`OPTION_CLOSEST`) 71–90 ms/frame, OCR 158–191 ms/frame, whole clip 1.5–1.8 s |

## Adaptive policy

- Live: drop-if-busy (one frame in flight) plus `AdaptiveThrottle`: after a frame that took d ms (exponentially smoothed, α = 0.3) the next frame is accepted d × (1/budget − 1) ms later, so processing occupies ≈ budget (default 30 %) of wall time on any device; a slow phone simply processes fewer frames. `wantsFrame` lets the live view skip the bitmap copy for frames that would be dropped. `stats` exposes processed fps, mean ms, busy share and dropped frames over the last 5 s. After `close()` frames are dropped silently.
- Resolution stays at 1280 px: on the emulator 960/640 px gave no consistent saving and recall moved by ±1. Re-check on a phone; if 640 px is clearly faster there, step down when the throttled rate falls below ~1 frame/s.
- Clips: 2 fps by default via `MediaMetadataRetriever` (`getScaledFrameAtTime` to 1280 px on API 27+, full-size `getFrameAtTime` on API 26), `OPTION_CLOSEST`; every sample decodes from the previous key frame, which at 2 fps is about one decode of the clip. A sequential `MediaCodec` decode is only worth it for higher fps.

## Known failure modes

- **Seal guessed as a letter** (`S`, `O`, `E`): shown as `?`, uncertain, no confidence, and filed under a key containing the guess (`HHSJK553` next to `HHJK553`); in the generated clip one of three frames did this.
- **Seal merged into a letter, or a real character after the city code with a non-letter guess below 0.4:** the seal rule can still drop a real character (`BX-MK` read as `B MK`), but such readings carry no confidence. Not seen in the synthetic set.
- **Partial occlusion** (tow bar, bike rack, dirt) can leave a shorter but valid plate (`B MK 48` for `B MK 4821`). Future work: an occlusion check that marks the reading uncertain when the strip right of the last glyph is darker than the plate background. Not done: a fixed strip width crosses the plate border on long plates, so it needs the plate outline first.
- **Two-line plates** (motorbikes, some imports, square rear plates) are not joined across OCR lines: missed.
- **Umlaut city codes** (TÜ, MÜ, FÜ, LÖ …) work when OCR keeps the dots; if it reads `TU`, the plate fails the district check or is stored as `TU…` (search for the digits instead).
- **Joined readings** without seal evidence (`BMK 4821`, blur sample) are reported as GENERIC; joined readings that are not valid generic plates (`MZTT 6006`) are missed.
- **Uppercase text shaped like a generic plate** (truck model `TGX 18.510`) passes as GENERIC.
- **EU band glyph without colour** (night, IR, washed-out) shows up as a leading `?`.
- **Lowercase OCR on real plates** (small or skewed text read as `s`, `o`, `x` with a confident score) rejects the reading.
- Swap readings can produce an odd split while keeping the right key: `MAB 123H` (joined) → `MAB ?23H` (MAB is a district code).
- Generic plates with an unreadable glyph are dropped.
- Night/IR frames, real motion blur, compression artefacts, glare and real plate fonts are not covered by the synthetic set.

## Owner measurement procedure (phone, for the hardware checklist)

Needs a USB-debuggable phone and the debug + test APKs (`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`).

1. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk` and `adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
2. Speed on the phone: `adb logcat -c`, then `adb shell am instrument -w -e class me.ri3d.dashcam.plates.PlateBenchmark me.ri3d.dashcam.test/androidx.test.runner.AndroidJUnitRunner`, then `adb logcat -d -s PlateEval`. Note avg/p95 per width and the live-throttle line (processed fps, busy share). Repeat 3×.
3. Accuracy sanity on the phone: same with `-e class me.ri3d.dashcam.plates.PlateEvaluationTest`. ARM and x86 builds of the ML Kit model may differ slightly, so the recall/precision lines should be close to the table above, not necessarily identical; a large difference means a different ML Kit build or a bug.
4. CPU while live (after feature/plates-ui; until then step 2's `liveThrottle` is the proxy): live view with plates **off** for 10 min, then **on** for 10 min, phone on USB. Per block: `adb shell dumpsys battery unplug` (USB charging otherwise stops battery stats) and `adb shell dumpsys batterystats --reset` at the start; at the end `adb shell dumpsys batterystats me.ri3d.dashcam > plates-off.txt` (or `plates-on.txt`) and `adb shell dumpsys battery reset`. Compare the `Proc me.ri3d.dashcam: CPU: … usr + … krn` lines. During the block sample `adb shell top -b -d 5 -n 12 | grep me.ri3d.dashcam` for %CPU.
5. Thermal: `adb shell dumpsys thermalservice` before and after each 10-min block; record "Thermal Status" and the CPU/skin temperatures. Plates on should not raise the thermal status above the plates-off block.
6. Real footage, once available: copy 3–5 clips with readable plates (day, night, rain, motorway) to the phone, scan them through the app (plates-ui) or a one-off instrumented test calling `ClipPlateScanner.scan`, and write down per clip: plates truly visible, found exact, found with `?`, wrong, scan time. Those numbers replace the synthetic table as the reference.

## Hardware / owner verification items

- Measurements 2–6 above on the owner's phone (synthetic numbers are emulator-only).
- Real dashcam footage evaluation (recall, `?` rate, false positives), especially the seal rule (how often real seals are guessed as letters), the wide-glyph seal gap and night/IR.
- Decide on ML Kit usage metrics (keep, or remove the transport services from the merged manifest).
- Release: `abiFilters` arm64-v8a/armeabi-v7a or an AAB (41 MB of OCR libraries across four ABIs otherwise).

## UI (feature/plates-ui)

Package `me.ri3d.dashcam.plates.ui`. `FeatureFlags.plates = true`: the Home search bar opens `Plates()`, Settings → App has the row "Kennzeichenerkennung".

| File | Contents |
| --- | --- |
| `PlatesGraph.kt` | `platesGraph(navController)`: routes `Plates(query)`, `PlateDetail(plateId)`, `PlatesSettings` (new route) |
| `LivePlates.kt` | `LivePlatesViewModel`, slots `LivePlatesToggle` (leadingControls), `LivePlatesOverlay` (overlay), `LivePlatesList` (belowControls) |
| `ClipScans.kt` | `ClipScans` (singleton): app-wide clip check queue, progress, cancel, automatic check of new downloads |
| `ClipPlates.kt` | `ClipPlatesViewModel`, slots `ClipPlates` (clipExtras) and `ClipPlatesOverlay` (clipOverlay), `PlatesAutoScan()` |
| `PlatesScreen.kt` / `PlateDetailScreen.kt` / `PlatesSettingsScreen.kt` | search, history of one plate, settings (+ `PlatesSettingsRow`) |
| `PlateUi.kt` | `fitRect`, `mapBox`, `PlateOverlay` (boxes + chips), `PlateChip`, `seenText` |

Wiring (`AxoNavHost`): `liveGraph(navController, leadingControls, belowControls, overlay)`, `mediaGraph(navController, clipExtras, clipOverlay)`, `platesGraph(navController)` and one `PlatesAutoScan()` next to the NavHost (starts the automatic clip check once per process). Additions in `plates/`: `PlateDao.sightingRows(plateId)` (sightings joined with their `media_item`: kind, category, raw recorder time, phone copy), `observeForMedia(mediaId)`, `incidentPlateIds()` (join on `media_item.category = 'EVENT'`), `PlateRepository.delete(plateId)` (plate, sightings by cascade, crops). Queries only, no schema change.

### Behaviour

**Live view.** The "Kennzeichen" toggle (left of Screenshot) is the `platesLive` preference. Frames are collected only while the toggle is on, the Live entry is resumed (reported by the overlay, which exists in normal and full screen) and the stream plays (`LiveFrameSource.videoSize != null`): `frames(fps = 5, wanted = processor.wantsFrame)` into a `LivePlateProcessor` with the repository, so sightings are recorded as LIVE (2 s dedupe). Each activation gets its own processor and recognizer; both are closed when a condition ends, but only after `STOP_DELAY_MS` (500 ms): the overlay moves between normal and full screen in one frame, and a rotation (`isChangingConfigurations`) does not count as a pause, so neither restarts the processor. Overlay: the boxes of the last processed frame, mapped from the pixels of the frame handed to the processor (≤ 1280 px wide, the stream's display aspect) into `videoRect` — `x' = videoRect.left + x · videoRect.width / frameWidth`, same for y — so letterboxing and full screen line up. Below the controls: "Erkannte Kennzeichen" = the last three distinct plates of this visit with their history count ("4-mal gesehen") and phone time ("17:42:05"), "Verlauf" (→ `Plates()`), the off text from the artboard, and "Läuft auf diesem Handy · 3,6 Bilder/s" from `stats.processedFps` while processing.

**Clip.** "Kennzeichen in diesem Clip" (original videos only): the sightings grouped by plate ("2-mal in diesem Clip", row → detail) with one jump button per sighting (`seekTo(positionMs)`), and "Clip auf Kennzeichen prüfen" for a phone copy (else "Erst herunterladen, dann prüfen."). The check runs in `ClipScans` (app scope, one clip at a time) with a progress bar and "Abbrechen" on the clip screen and the Plates screen; the outcome is shown per clip for the life of the process: "Keine Kennzeichen gefunden.", "n Kennzeichen gefunden.", "Prüfung abgebrochen. Bis dahin gefundene Kennzeichen bleiben gespeichert." or "Prüfung fehlgeschlagen …". `clipStartMs` is the item's `recorderTimeEpochGuess`, so a clip sighting's time is the recorder-time guess plus the position (scan time when the recorder time is unknown). Overlay: sightings within ± 500 ms of the playback position, fitted into the 16:9 box like the player (`RESIZE_MODE_FIT`, video size from `MediaMetadataRetriever`). Enhanced frames and upscaled clips show "Verbesserte und hochskalierte Dateien werden nicht auf Kennzeichen geprüft: Sie sind rekonstruiert, kein Beweis. Prüfe das Original." and are refused by `ClipScans.enqueue` as well.

**Automatic clip check** (`platesClips`): while on, every ORIGINAL_VIDEO with a phone copy whose `downloadedAt` is at or after the start (process start when it was on at start, else the moment it was switched on) is queued once per process. Photos, screenshots, derived outputs and recordings without a phone copy are never queued.

**Search** (`Plates(query)`, Home search bar): partial match on `normalized` ("bmk", "4821", "B-"), chips "Alle" / "Vorfälle" (plates with a sighting in a recording of category EVENT), count, rows with the reading (`?` as read), "x-mal gesehen", "Zuletzt heute 17:42", "Vorfall" badge. Empty states: no match ("Keine Kennzeichen passen zu „…“" + "Probiere einen Teil des Kennzeichens, etwa die Städtekennung oder die letzten Ziffern."), no incidents, no history. The field gets the focus once when opened without a query; the query is kept uppercase (plates are); query and filter survive process death (SavedStateHandle).

**Detail** (`PlateDetail(plateId)`): sightings count, first/last seen, "Vorfall" badge, sightings newest first: time, source ("Live-Ansicht" or "Vorfall 00:53:00 · bei 00:02" with the raw recorder clock), crop, "Gelesen als …" when the sighting's reading differs, "unsicher gelesen" when `confidence == null`; a sighting whose recording is on the phone opens `Clip(mediaId, positionMs)`, otherwise "Aufnahme nicht auf dem Handy". "Verlauf dieses Kennzeichens löschen" asks first.

**Settings** (`PlatesSettings`): "In der Live-Ansicht" (`platesLive`), "In gespeicherten Clips" (`platesClips`), "Kennzeichen-Daten in Drive-Sicherung einschließen" (`backupIncludePlateMetadata`, explained as local by default; the text says the sidecar also carries "einen internen Lesewert, der keine Trefferquote ist" – `confidence` is kept in the sidecar), "Verlauf löschen" (danger confirmation → `PlateRepository.clear()`), and the note "Die Erkennung läuft auf diesem Handy. Unsichere Zeichen werden als ? angezeigt. Auch Lesungen ohne ? können falsch sein – prüfe im Zweifel die Aufnahme. Es gibt keine Trefferquote."

### Honesty rules as rendered

- No confidence figure anywhere (no percentage, no bar, no score). The only signal is binary: `confidence == null` → "unsicher" (live/clip overlay: dashed amber box, amber chip with the word) and "unsicher gelesen" (detail).
- Readings are shown as read: `?` stays in chips, lists, overlay and "Gelesen als …"; nothing is filled in.
- Enhanced/upscaled outputs are never scanned (UI refusal, `ClipScans.scannable`, the automatic check only takes ORIGINAL_VIDEO), so no sighting comes from a reconstruction.
- Times: live sightings carry phone time; clip sightings the recorder-time guess plus position (the clip screen labels the recorder time as "laut Recorder, Zeitzone unbekannt").
- Accessibility: TalkBack reads one summary on the first overlay chip ("Erkannte Kennzeichen: B-MK 4821; K?LT 207, unsicher"), the other chips are silent and the video stays explorable; the search placeholder is silent (the field carries the label); toggle, jump buttons ("Zu 00:02 springen"), switches (whole row) and rows are ≥ 48 dp.
- Nothing is logged beyond exception class names; plate text never goes to the log.

### Limitations

- The live overlay was not exercised: the simulator has no RTSP stream. Boxes are those of the last processed frame (one every ~0.3–1 s with the 30 % budget), so they trail moving plates.
- Background: clip checks run in the app process without a foreground service. On Android 14+ the cached-app freezer suspends a check while the app sits in the background (it continues when the app comes back), and Android may kill the process (the check is lost, see below). The app does not look at battery saver: checks and the automatic check run as usual (Android may restrict a background process further; not measured). Live processing runs only while the Live screen is resumed.
- Dedupe keeps one sighting per continuous appearance (2 s window), so the clip overlay shows a box only ± 500 ms around where each appearance was first detected.
- Clip checks survive leaving the screen but not process death; outcomes are kept in memory only. A download that finished in a process that died before the check is not checked automatically ("Clip auf Kennzeichen prüfen" covers it). A queued clip whose phone copy is deleted meanwhile is dropped without an outcome.
- The "Vorfall" badge needs the recording's library row; when its last copy is deleted (row removed), the badge goes, the sightings stay.
- Deleting (one plate, the whole history, one recording's sightings) is not cancellable once confirmed (`NonCancellable`, crops included).
- Clip overlay ignores rotation and pixel aspect (dashcam clips have none).
- Lint (AGP 9.3 K2 UAST) crashes in `ExperimentalDetector` on a bound callable reference to a constructor parameter in a property initializer (`repository::search`); use a lambda there.

### Validation

- Unit tests (Robolectric, seeded in-memory Room): `PlatesViewModelsTest` (search by city letters/digits, uppercase query, route query, incident filter, badge gone with the media row, detail rows with source/copy/unsure reading, delete with crops, settings switches and clear, live list = newest three of the visit), `PlateRepositoryTest` (a cancelled deletion still completes and resets the live dedupe), `PlateOverlayMathTest` (fit and mapping: same aspect, pillarbox, portrait letterbox, full screen, clip player; ± 500 ms window), `ClipScansTest` (progress, one at a time, cancel running/queued incl. before the scan started, failure, derived/photo/no-copy refused, automatic check only for originals downloaded since start, nothing while off, only later downloads after switching on, downloads made while off not checked at the next switch-on, vanished phone copy skipped), `LivePlatesViewModelTest` (frames only while on + resumed + playing, a pause shorter than `STOP_DELAY_MS` keeps the processor, recognizer closed on each stop, LIVE sighting recorded and listed; it uses virtual time for the frame loop and real time for the processor thread, whose dispatcher and clock the view model does not expose).
- Emulator (`emulator-5556`, API 36) [SIM]: simulator session with "In gespeicherten Clips" on; the incident clip `E20261001_005300.mp4` (the simulator's fixture, 320 × 176, 4 s) downloaded → the automatic check ran → "Keine Kennzeichen gefunden." (expected: the fixture shows no plates); "Clip auf Kennzeichen prüfen" again showed the progress bar with "Abbrechen" and the same result. Plates screens with a fictional history written through `run-as` into the debug app's database (no seeding code in the app): list (5 plates, "Vorfall" on two, `K?LT 207` shown as read), "Vorfälle" (2), search "4821" (1), "XY9" empty state, detail of B-MK 4821 (3 sightings, "Gelesen als B ?K 4821" + "unsicher gelesen", clip sighting "Vorfall 00:53:00 · bei 00:02") → clip opened at 00:02 with box and chip at the seeded spot. Live view: off text, and after the toggle "Noch keine Kennzeichen erkannt …". The live overlay and live processing could not be exercised: the simulator has no RTSP server (stream error 2000).

### Hardware checklist (owner)

1. Live overlay on the phone with the real stream: box position on the plate in normal and full screen (letterboxed), lag behind moving plates, the "x Bilder/s" figure; CPU and thermal with plates on (procedure steps 4–5 above).
2. Real footage through the app: "Clip auf Kennzeichen prüfen" on 3–5 real clips (procedure step 6): time per clip, found exact / with `?` / wrong, boxes at the jump positions.
3. Automatic check after real downloads (full-length clips): queue behaviour and scan time per minute of video.
4. Whether `fileTime` is the clip start and in which time zone (clip sighting times are based on it).
