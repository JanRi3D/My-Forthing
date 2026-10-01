# Plates (feature/plates-core)

On-device plate recognition for live frames and saved clips, a local searchable history linked to recording + position, and the sidecar hook for the backup. No screens: the UI phase (feature/plates-ui) builds on the API below. `FeatureFlags.plates` stays `false` until then.

## API for the UI phase (`to.axolotl.cam.plates`)

```kotlin
data class PlateDetection(val text: String, val normalized: String, val confidence: Float?, val box: RectF, val frameTimestampMs: Long, val format: PlateFormat)
enum class PlateFormat { GERMAN, GENERIC }
interface PlateRecognizer { suspend fun recognize(frame: Bitmap, timestampMs: Long): List<PlateDetection>; fun close() }
class MlKitPlateRecognizer(maxWidth: Int = 1280) : PlateRecognizer          // Hilt: unscoped PlateRecognizer, caller closes

@Singleton class PlateRepository {                                           // Hilt
  fun history(): Flow<List<Plate>>                                           // newest first
  fun search(query: String): Flow<List<Plate>>                               // partial match on normalized, e.g. "BMK" or "4821"
  fun plate(id: Long): Flow<PlateWithSightings?>                             // sightings unordered
  suspend fun recordSightings(detections, source: SightingSource, mediaId: String? = null, positionMs: Long? = null,
                              frame: Bitmap? = null, seenAt: Long = now): Int // returns sightings written
  suspend fun clear()                                                        // rows + every crop
  suspend fun clearForMedia(mediaId: String)                                 // sightings of one recording + their crops
}
class Frame(val bitmap: Bitmap, val timestampMs: Long)
data class ProcessingStats(val processedFps: Float, val avgMs: Float, val busyFraction: Float, val dropped: Long)
class AdaptiveThrottle(budget: Float = 0.3f)
class LivePlateProcessor(recognizer, repository: PlateRepository?, scope, throttle = AdaptiveThrottle()) {
  val detections: StateFlow<List<PlateDetection>>; val stats: StateFlow<ProcessingStats>
  val wantsFrame: Boolean                                                    // check before grabbing a bitmap from the player
  fun submit(frame: Frame): Boolean                                          // false = dropped (busy or throttled)
  fun close()
}
class ClipPlateScanner {                                                     // Hilt
  suspend fun scan(uri: Uri, mediaId: String, fps: Int = 2, clipStartMs: Long? = null, onProgress: (ScanProgress) -> Unit = {}): ScanSummary
}
class PlateExport { suspend fun forMedia(mediaId: String): List<SidecarPlate> }  // Hilt; @Serializable SidecarPlate(text, normalized, positionMs, confidence, box)
```

Room (`AppDatabase` v2, migration 1→2 in `AppDatabase.MIGRATION_1_2`, schema `app/schemas/…/2.json`):
`plate(id, normalized UNIQUE, display, firstSeen, lastSeen, count)`, `plate_sighting(id, plateId → plate ON DELETE CASCADE, mediaId, positionMs, source LIVE|CLIP, seenAt, confidence, cropPath, boxLeft/Top/Right/Bottom)`, indices on `normalized`, `plateId`, `mediaId`, `seenAt`.

Additions to CONTRACTS §11 (documented deviations): `PlateDetection.format`; sighting box columns (needed for the sidecar `box`); `recordSightings(…, frame, seenAt)` (crop source, and the road time for clips: `seenAt = clipStart + positionMs` when the clip start is known); `confidence` is averaged over the plate's **characters** (ML Kit symbols) instead of its elements, because an element often contains the seal glyph whose low score is not about the plate. Crops: `filesDir/plates/<uuid>.jpg`, ≤ 320 px wide, JPEG 85, `cropPath` relative to `filesDir` (same convention as `LocalProfile.avatarPath`).

Dedupe: a plate detected again within 2 s of its previous detection is one sighting (sliding: a plate in view for 10 s live is one sighting, not five). LIVE compares wall time; CLIP compares the position inside the same clip and additionally skips a position within 2 s of an existing sighting of that plate in that clip, so scanning a clip again (any fps) adds nothing.

## Library and licence

- `com.google.mlkit:text-recognition:16.0.1` (Text Recognition v2, Latin, **bundled** model). 16.0.1 is the newest release on Google Maven (Aug 2024). Works offline from the first launch; the unbundled Play-services variant would download the model on first use, so it was not chosen. Passes `checkDebugAarMetadata` with compileSdk 36.
- Licence: [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) (Google proprietary SDK, free of charge, not open source).
- Privacy note: frames and recognised text never leave the phone through this code. ML Kit itself merges `INTERNET`, `ACCESS_NETWORK_STATE` and Google `datatransport` (CCT) services into the manifest; per its terms ML Kit sends API usage/performance metrics to Google (no image content). Whether to suppress that (manifest `tools:node="remove"` of the transport services) is an owner decision, not done here.
- Size: `libmlkit_google_ocr_pipeline.so` is 11.1 MB (arm64-v8a), 6.8 MB (armeabi-v7a), 11.6 MB (x86/x86_64) uncompressed, models ~1.5 MB; the debug APK with all four ABIs is 58 MB. The release build should ship an AAB or set `abiFilters` (arm64-v8a, armeabi-v7a).
- API facts checked in the AAR (`play-services-mlkit-text-recognition-common` 19.1.0): `Text.Line/Element/Symbol.getConfidence()` return a primitive `float`, never null. Measured: v2 Latin fills them (plates: 0.68–0.93 per plate after averaging; single glyphs 0.07–0.98).

## Recognition rules (`PlateText`, `MlKitPlateRecognizer`)

1. Frames wider than 1280 px are downscaled before OCR; boxes are mapped back to the caller's frame.
2. Per ML Kit symbol: a **coloured** glyph (≥ 40 % clearly saturated pixels: EU band, coloured stickers; plate characters are black on white) becomes a separator; a glyph scored **< 0.4** is shown as `?` (its OCR guess is kept for `normalized`).
3. A confidently read **lowercase** letter rejects the text (plates are uppercase; signs and ads are mostly mixed case).
4. Text is uppercased, separators unified (`-` if a dash was read, else space); `normalized` = uppercase A–Z, 0–9, ÄÖÜ of the OCR reading.
5. German: `^[A-ZÄÖÜ]{1,3}[- ]?[A-Z]{1,2}[- ]?[0-9]{1,4}[HE]?$` with `?` standing for one unreadable character, at most 8 characters before the H/E suffix. One `?` with only letters before it and a letter right after it is first tried as the **seal** (registration/inspection stickers between city code and letters, which OCR reads as "8", "S", "&", "3" …) and then becomes a separator.
6. Ambiguity: if the reading fits only after O↔0 / I↔1 / B↔8 swaps (at most 2, each swap must fit its block: a digit in an all-letter block or vice versa), the swapped positions are shown as `?` and nothing is corrected: `8-MK 4821` → display `?-MK 4821`, normalized `8MK4821`.
7. Generic EU fallback (`format = GENERIC`): 2–3 blocks, letters and digits, 5–9 characters, no unreadable glyph, letter-only blocks ≤ 3 characters.
8. Per OCR line, every window of up to 4 elements is tried; overlaps resolve German > generic, then more elements, then certain > uncertain.

**What `confidence` means:** ML Kit's own per-character recognition score (model output, not calibrated, not a probability that the plate is right), averaged over the plate's characters. It is **null** when any character is `?` (unreadable or ambiguous) or when ML Kit reports no score (0 or NaN). The UI should show it as a coarse quality hint at most and never for `?` readings.

## Synthetic evaluation (no real footage yet)

Instrumented `PlateEvaluationTest` renders a deterministic set with `Canvas`: 42 plates on 1920×1080 dashcam-like scenes (EU band with stars, the two seals, fictional German plates in five system fonts, widths 140–420 px) and 15 negatives (12 street/shop/ad texts + 3 uppercase hard negatives). System fonts stand in for the FE-Schrift; the renderer is mine, so these numbers show the filter works as designed, not real-world accuracy.

Result (identical in every run; emulator `Pixel_10_Pro_XL` AVD, API 36, x86_64, 4 vCPU on an i9-14900K host, shared with another agent):

| condition | n | exact | `?`-marked, consistent | missed | wrong detections |
| --- | --- | --- | --- | --- | --- |
| clean | 8 | 8 | 0 | 0 | 0 |
| small (140–240 px wide) | 4 | 3 | 1 | 0 | 0 |
| skew / perspective / rotation | 5 | 4 | 0 | 1 | 2 (both `?`, no confidence) |
| blur (defocus ×3/×4, motion 12/24 px) | 4 | 3 | 0 | 1 | 0 |
| noise | 4 | 4 | 0 | 0 | 0 |
| night (contrast 0.3, noise) | 4 | 4 | 0 | 0 | 0 |
| occlusion (20–50 % covered) | 3 | 0 | 0 | 3 | 3 (`B MK 48` certain; 2 with `?`) |
| EU generic (F, PL, I, E) | 4 | 4 | 0 | 0 | 0 |
| H/E suffix | 2 | 2 | 0 | 0 | 0 |
| umlaut city code (TÜ, MÜ) | 2 | 2 | 0 | 0 | 0 |
| two-line (motorbike) | 2 | 0 | 0 | 2 | 0 |
| negatives | 15 | – | – | – | 2 (`ZONE 30` German, `TGX 18 510` generic) |

- Recall (exact or `?`-marked and consistent with the truth): **35/42 = 0.83** (exact 34/42). Precision (correct / all detections): **35/42 = 0.83**. Negatives with a detection: 2/15. Expected format among found: 35/35. Detections with a confidence: 37/42 (range 0.68–0.93).
- Before the glyph rules (2, 3, 5) the same set gave recall 15/42 and precision 15/46: OCR read the seal as a character on every clean plate (`B8MK`, `FSZO`, `HH3JK`) and 6/12 mixed-case negatives were accepted. The 0.4 threshold comes from that run's symbol scores: seal/band glyphs 0.07–0.36, plate characters mostly ≥ 0.5 (lowest 0.30–0.43 on small/skewed plates).
- Regression guards in the test: clean ≥ 7/8 exact, recall ≥ 0.75, ≤ 3 negatives with a detection.

## Measured timings (emulator, indicative only)

x86_64 emulator numbers run on a desktop CPU and say nothing reliable about a phone; they only compare settings with each other.

| measurement | result |
| --- | --- |
| `PlateBenchmark.widths`, OCR + filter per 1920×1080 frame, 4 runs | 1280 px: avg 47–101 ms, p95 98–174 ms, recall 35/42 · 960 px: avg 44–62 ms, p95 84–126 ms, recall 34/42 · 640 px: avg 50–63 ms, p95 81–138 ms, recall 33/42 |
| first `recognize` in a fresh process (model init included) | 263–282 ms |
| `PlateBenchmark.liveThrottle` (30 fps offered for 6 s, budget 0.30, 1920×1080 frames) | 3.2–5.2 frames/s processed, 52–73 ms/frame, busy share 0.23–0.27, 146–157 of ~175 frames dropped |
| `ClipPlateScanner` on the generated 3 s 1280×720 H.264 clip at 2 fps | `getFrameAtTime(OPTION_CLOSEST)` 44–58 ms/frame, OCR 94–101 ms/frame, whole clip 0.9–1.0 s |

## Adaptive policy

- Live: drop-if-busy (one frame in flight) plus `AdaptiveThrottle`: after a frame that took d ms (exponentially smoothed, α = 0.3) the next frame is accepted d × (1/budget − 1) ms later, so processing occupies ≈ budget (default 30 %) of wall time on any device; a slow phone simply processes fewer frames. `wantsFrame` lets the live view skip the bitmap copy for frames that would be dropped. `stats` exposes processed fps, mean ms, busy share and dropped frames over the last 5 s.
- Resolution stays at 1280 px: on the emulator 960/640 px saved little and inconsistent time and lost recall (34, 33 of 42). Re-check on a phone; if 640 px is clearly faster there, step down when the throttled rate falls below ~1 frame/s.
- Clips: 2 fps by default, `MediaMetadataRetriever.getFrameAtTime(OPTION_CLOSEST)`; every sample decodes from the previous key frame, which at 2 fps is about one decode of the clip. A sequential `MediaCodec` decode is only worth it for higher fps.

## Known failure modes

- **Seal not read as its own glyph but merged into a letter, or a real character scored < 0.4 right after the city code:** the seal rule can then drop a real character (e.g. `BX-MK` read as `B MK`). Not seen in the synthetic set; watch for it in real footage.
- **Partial occlusion** (tow bar, bike rack, dirt) can leave a shorter but valid plate (`B MK 48` for `B MK 4821`), reported as certain. The filter cannot know characters are missing.
- **Two-line plates** (motorbikes, some imports, square rear plates) are not joined across OCR lines: missed.
- **Umlaut city codes** (TÜ, MÜ, FÜ, LÖ …) work when OCR keeps the dots (both synthetic cases did); if it reads `TU`, the plate is stored as `TU…` and a search for `TÜ` misses it (search for the digits instead).
- **Uppercase street text** shaped like a plate (`ZONE 30`, truck model `TGX 18.510`) passes; an official city-code list (Unterscheidungszeichen) would remove most of these and is the next precision step.
- **EU band glyph without colour** (night, IR, washed-out) is not recognised as the band; it then shows up as a leading `?` and the reading counts as a different plate.
- **Lowercase OCR on real plates** (small or skewed text read as `s`, `o`, `x` with a confident score) rejects the reading.
- `?` readings keep the OCR's characters in `normalized`, so `?-MK 4821` (`8MK4821`) and `B-MK 4821` (`BMK4821`) are separate history entries.
- Generic plates with an unreadable glyph are dropped.
- Night/IR frames, real motion blur, compression artefacts, glare and real plate fonts are not covered by the synthetic set.

## Owner measurement procedure (phone, for the hardware checklist)

Needs a USB-debuggable phone and the debug + test APKs (`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`).

1. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk` and `adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
2. Speed on the phone: `adb logcat -c`, then `adb shell am instrument -w -e class to.axolotl.cam.plates.PlateBenchmark to.axolotl.cam.test/androidx.test.runner.AndroidJUnitRunner`, then `adb logcat -d -s PlateEval`. Note avg/p95 per width and the live-throttle line (processed fps, busy share). Repeat 3×.
3. Accuracy sanity on the phone: same with `-e class to.axolotl.cam.plates.PlateEvaluationTest` (the recall/precision lines must match the table above; a difference means a different ML Kit build).
4. CPU while live (after feature/plates-ui; until then step 2's `liveThrottle` is the proxy): live view with plates **off** for 10 min, then **on** for 10 min, phone on USB. Per block: `adb shell dumpsys battery unplug` (USB charging otherwise stops battery stats) and `adb shell dumpsys batterystats --reset` at the start; at the end `adb shell dumpsys batterystats to.axolotl.cam > plates-off.txt` (or `plates-on.txt`) and `adb shell dumpsys battery reset`. Compare the `Proc to.axolotl.cam: CPU: … usr + … krn` lines. During the block sample `adb shell top -b -d 5 -n 12 | grep to.axolotl.cam` for %CPU.
5. Thermal: `adb shell dumpsys thermalservice` before and after each 10-min block; record "Thermal Status" and the CPU/skin temperatures. Plates on should not raise the thermal status above the plates-off block.
6. Real footage, once available: copy 3–5 clips with readable plates (day, night, rain, motorway) to the phone, scan them through the app (plates-ui) or a one-off instrumented test calling `ClipPlateScanner.scan`, and write down per clip: plates truly visible, found exact, found with `?`, wrong, scan time. Those numbers replace the synthetic table as the reference.

## Hardware / owner verification items

- Measurements 2–6 above on the owner's phone (synthetic numbers are emulator-only).
- Real dashcam footage evaluation (recall, `?` rate, false positives, especially the seal rule and night/IR).
- Decide on ML Kit usage metrics (keep, or remove the transport services from the merged manifest).
- Release: ABI split / AAB to avoid shipping four copies of the 7–12 MB OCR library.
