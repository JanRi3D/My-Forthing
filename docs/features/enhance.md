# Enhancement (feature/enhance-core)

Package `me.ri3d.cam.enhance`: frame enhancement, clip upscaling, live-upscale probe. No screens (enhance-ui).
Everything runs on the phone; nothing is downloaded at runtime. Every output is a reconstruction, never evidence,
and carries a sidecar saying so.

## Decision

| Path | Engine | Why |
| --- | --- | --- |
| Single frame ("Bild verbessern") | **ML: QuickSRNet Medium ×4** (LiteRT CPU/XNNPACK), classical fallback | Best PSNR/SSIM of all candidates on the synthetic reference, 244 KiB, ≈ 6 s per 1080p frame on the emulator (quiet host) |
| Clip ("Clip hochskalieren") | **Classical on the GPU** (sharpened cubic, GLES 2 shader) by default; ML per frame selectable | ML per frame costs seconds per 1080p frame → hours per minute of video; the GPU path runs at encoder speed |
| Live view | **Do not offer ML.** Offer the GPU sharpen effect only where `LiveUpscaleProbe` says so (Android 13+, ≤ 8 ms per 720p×2 frame) | ML ≈ 0.6–1.2 s per 360p frame on the emulator: not real time |

Runtime: **LiteRT 1.4.2** (`com.google.ai.edge.litert:litert`, Apache-2.0, minSdk 21, native libraries 16 KB
page-aligned, checked with `llvm-readelf`). LiteRT 2.x (2.2.0, `CompiledModel`, GPU/NPU) was considered: its AAR
merges `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` permissions (AI-pack downloads) and ships a 3–4 MB GPU
accelerator per ABI that the chosen model does not need on CPU. Google documents the 1.4.x Interpreter API as
"maintained for backward compatibility"; upgrade path in *Limits*.

## Evaluation

Setup: `emulator-5556`, AVD Pixel_10_Pro_XL, Android 16 (API 36) x86_64, 4 vCPUs on an i9-14900K host, 4 GB RAM,
GLES = SwiftShader (software), codecs = `c2.android.*` software. **Indicative only**: no phone CPU, no GPU/NPU, and the
host was shared with four other agents – the same benchmark ran 2–3× slower when the host was busy, so both runs are
listed. Benchmark: `EnhanceBenchmark` (opt-in, see *Measure on a phone*).

Quality proxy: a drawn 1024×576 "dashcam" scene (sky/road gradients, plate text 13–44 px, thin lines, rings, noise
patch) is area-downscaled ×4 (256×144) and ×2 (512×288), upscaled back and compared with the original on luma
(PSNR; mean SSIM over 8×8 windows, stride 4; 16/8 px border ignored). Synthetic and bicubic-friendly: real
compressed footage will rank differently (see *Limits*). ML speed is per model call (128×128 input, 4 threads,
median of 5 after warm-up); the 1080p figure is that × the tile count of the real tiling (180 tiles at 128 px, overlap
16, margin 4; ×2 costs the same as ×4 because the model always runs at ×4). Native heap = growth after loading and one
inference (interpreter arena), excluding the output bitmap (1080p×4 = 7680×4320 ARGB = 133 MB, ×2 = 33 MB).

| Candidate (publisher) | Licence | File | ms/call quiet / busy | 1080p frame (quiet) | Native heap | ×4 PSNR / SSIM | ×2 PSNR / SSIM | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Bilinear (`Bitmap.createScaledBitmap`) | platform | – | – | – | – | 23.61 / 0.85 | 27.36 / 0.93 | reference only |
| Bicubic Catmull-Rom (ours, a = 0, no denoise) | ours | – | – | – | – | 24.05 / 0.86 | 28.32 / 0.94 | reference only |
| **Classical (ours: denoise + sharpened cubic, a = 0.5)** | ours | – | – | ×2 0.67–0.72 s, ×4 1.4–1.5 s measured (busy 1.4–2.5 / 2.5–4.2 s) | – | 24.34 / 0.87 | 28.82 / 0.95 | **fallback, clips, live** |
| Real-ESRGAN-General-x4v3 float (Qualcomm AI Hub) | BSD-3-Clause | 4.65 MiB | 275 / 397–946 | ≈ 50 s | +113 MB | 25.37 / 0.89 | 31.28 / 0.95 | rejected: 8× slower than QuickSRNet, lower score here |
| Real-ESRGAN-General-x4v3 w8a8 (Qualcomm AI Hub) | BSD-3-Clause | 1.25 MiB | 294 / 429–1016 | ≈ 53 s | +46 MB | 25.26 / 0.86 | 31.20 / 0.93 | rejected (int8 not faster on x86) |
| ESRGAN-tf2, 50×50 TFLite (Kaggle/TF Hub, captain-pool) | MIT per Kaggle `licenseName`; the former TF Hub listing said Apache-2.0 (unresolved, not shipped) | 4.76 MiB | 60 / 153–163 | ≈ 72 s (1196 tiles) | +50 MB | 25.58 / 0.88 | 32.15 / 0.96 | rejected: tiny fixed input → many calls |
| XLSR float (Qualcomm AI Hub) | BSD-3-Clause | 115 KiB | 27 / 55–78 | ≈ 4.9 s | +19 MB | 25.76 / 0.89 | 32.59 / 0.97 | close second |
| QuickSRNetSmall float (Qualcomm AI Hub) | BSD-3-Clause | 133 KiB | 27 / 58–76 | ≈ 4.8 s | +15 MB | 25.75 / 0.91 | 32.66 / 0.97 | close second |
| QuickSRNetSmall w8a8 (Qualcomm AI Hub) | BSD-3-Clause | 42 KiB | 20 / 42–61 | ≈ 3.6 s | +8 MB | 25.25 / 0.89 | 30.89 / 0.97 | quantisation costs 0.5–1.8 dB |
| **QuickSRNetMedium float (Qualcomm AI Hub)** | **BSD-3-Clause** | **244 KiB** | **33 / 67–72** | **≈ 5.9 s** | **+15–18 MB** | **26.30 / 0.91** | **33.38 / 0.97** | **shipped** |
| QuickSRNetLarge float (Qualcomm AI Hub) | BSD-3-Clause | 1.67 MiB | 112 / 218–220 | ≈ 20 s | +41 MB | 25.89 / 0.91 | 32.93 / 0.97 | slower, not better |
| SESR-M5 float (Qualcomm AI Hub) | BSD-3-Clause | 1.32 MiB | 96 / 156–193 | ≈ 17 s | +37 MB | 25.75 / 0.90 | 32.65 / 0.97 | slower, not better |

Shipped model through the real `FrameEnhancer` on a 1080p frame: ×4 6.6 s (quiet; busy 10.2–12.3 s), ×2 5.5 s
(busy 9.1–11.0 s); the first-run estimate was within −15 %…+18 % of the measured time.

Not evaluated, with reason: Real-ESRGAN-x4plus / ESRGAN (Qualcomm AI Hub, 64 MB float, 16.7 MB w8a8: over the
≈ 10 MB budget); FSRCNN/ESPCN (only OpenCV `.pb` graphs are published, no official TFLite – would need a conversion
pipeline; XLSR/QuickSRNet/SESR are the officially published members of that lightweight family).
Note: the Qualcomm model cards of the QuickSRNet/XLSR/SESR assets name a `…_3x_checkpoint`, but the exported files
are ×4 (output 512×512 for 128×128, checked in `metadata.json` and at runtime: `SrModel.nativeScale` is read from the
tensors).

### Shipped model provenance

| | |
| --- | --- |
| Model | QuickSRNet Medium ×4 (arXiv:2303.04336), weights from the AIMET Model Zoo, exported by Qualcomm AI Hub Models v0.63.0 |
| URL | https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/quicksrnetmedium/releases/v0.63.0/quicksrnetmedium-tflite-float.zip |
| Model card | https://huggingface.co/qualcomm/QuickSRNetMedium (`license: bsd-3-clause`), revision `428a6f66e34662459586850c6905d7807095dfb4` (commit "v0.63.0", https://huggingface.co/qualcomm/QuickSRNetMedium/tree/428a6f6) |
| Zip SHA-256 | `6b8be955281035ba916c5e4ecec4bd16b114996eead7331fe9f4d31c9a450a54` |
| File in the app | `app/src/main/assets/models/quicksrnetmedium_float.tflite` (= `quicksrnetmedium.tflite` from the zip, unmodified, 249 452 bytes) |
| File SHA-256 | `9310ebbad930378d24b560cd25705c1b57b778fcbcd9d2a17406ff5c1378bf1c` |
| I/O | float32 NHWC, 1×128×128×3 in, 1×512×512×3 out, values 0…1 |
| Licence | BSD 3-Clause (AIMET Model Zoo weights; Qualcomm AI Hub Models export), full texts in `app/src/main/assets/models/LICENSE-quicksrnetmedium.txt`. LiteRT runtime notices (Apache-2.0; XNNPACK BSD-3; cpuinfo, pthreadpool BSD-2; FP16, FXdiv MIT; Abseil, FlatBuffers, ruy Apache-2.0) in `app/src/main/assets/models/NOTICE-litert.txt`; the release phase still generates the app-wide open-source notices |

Check: `sha256sum app/src/main/assets/models/quicksrnetmedium_float.tflite`. Changing the file means changing
`ShippedModel.ID` (cached measurements are keyed by it).

Other downloaded candidates (not shipped; for the benchmark put them into `app/src/androidTest/assets/candidates/`,
which its own `.gitignore` keeps out of git): same S3 path pattern
`…/models/<id>/releases/v0.63.0/<id>-tflite-<float|w8a8>.zip` for `real_esrgan_general_x4v3`, `xlsr`,
`quicksrnetsmall`, `quicksrnetlarge`, `sesr_m5`; ESRGAN-tf2 from
`https://www.kaggle.com/api/v1/models/kaggle/esrgan-tf2/tfLite/esrgan-tf2/1/download` (file `1.tflite`, SHA-256
`1a380d3744103e11ef343534aaff54815cae40769dcd00c023652a7e5bc47f4b`). Benchmark file names: see `EnhanceBenchmark`.

## Pipelines

### FrameEnhancer (stills)

`enhance(src, scale ∈ {2, 4}, onProgress)` → new Bitmap; `enhanceFrame(…, engine?)` also says which engine produced
it. Runs on `Dispatchers.Default`, one job at a time (mutex), original bitmap untouched.

- **Tiling** (`Tiling.kt`): tiles of 128 px (ML, the model input) or 256 px (classical), overlap 16 / 8 px. Each engine
  declares an unreliable edge **margin** (ML 4 px: the CNN's zero padding produced steps of up to 12 levels in the
  outermost ≈ 2 px; classical 3 px: cubic + denoise reach). At the image border the image is virtually extended by
  `margin` replicated pixels; at interior edges the margin is dropped and the rest of the overlap is blended with a
  linear ramp. Result: no seams (instrumented test on a 1080p ramp ×4: max neighbour step ≤ 3 levels; JVM test:
  classical tiled output equals untiled within rounding).
- **Memory**: source + output bitmap (native heap) plus a few tile buffers. `EnhancerCapabilities.maxOutputPixels`
  = min(1/16 of `ActivityManager.MemoryInfo.totalMem` ÷ 4 bytes, 7680×4320 = 1080p×4, 133 MB; 3840×2160 on low-RAM
  devices): 2 GB phones keep 1080p×4, a 1.5 GB phone gets ≈ 25 MP. HARDWARE bitmaps are copied once.
- **Errors**: `EnhanceException(EnhanceError.TooLarge)` above that limit, `EnhanceException(EnhanceError.Memory)` when
  an allocation fails (`OutOfMemoryError` is caught, the half-built output recycled). Results above ~100 MB (1080p×4
  is 133 MB) cannot be drawn by a `Canvas` ("trying to draw too large bitmap"): **enhance-ui shows a downsampled
  preview or the saved JPEG, never the full bitmap.**
- **Fallback**: model asset missing, LiteRT load failure or an exception during ML → classical for the whole frame
  (never mixed within one image); `EnhancedFrame.engine` reports what actually ran.
- **Cancellation**: coroutine cancellation is checked before every tile; the half-built output is recycled.
- **Classical** (`Classical.kt`): 3×3 sigma filter (centre weight 4, neighbours whose luma differs ≤ 12) as mild
  denoise, then separable resampling with the kernel `(1 + a)·CatmullRom − a·BSpline`, a = 0.5 – an unsharp mask
  folded into bicubic interpolation (weights sum to 1).
- **ML** (`SrModel.kt`): LiteRT `Interpreter`, `min(cores, 4)` threads, float or int8/uint8 tensors (quantisation
  parameters read from the model), ×2 = ×4 output box-averaged 2×2.

### ClipUpscaler (video)

`upscale(UpscaleRequest(input, target = P1080|P1440|P2160, sourceMediaId, engine = CLASSICAL, codec = H264),
onProgress)` → `Deferred<UpscaleResult>` (a cancellable `Job`, contract §12). `ExportQuality.resolution` maps the
preference (Q1080 → P1080 exists for 720p sources such as a rear camera). Path chosen as the simplest that keeps exact
timestamps:

```
MediaExtractor ─▶ MediaCodec decoder ─▶ SurfaceTexture (OES) ─▶ GLES 2 on the encoder input surface ─▶ MediaCodec encoder ─▶ MediaMuxer (MP4)
     └─ audio track ───────────────────────────── copied sample by sample, interleaved by timestamp ──────────────────────────▶
```

- Classical: one fragment shader samples the decoded frame with the same sharpened-cubic kernel (4×4 taps, no
  denoise on video) straight to the target size. ML: frame read back from an FBO, `FrameEnhancer` ×2 (×4 if the
  target is more than twice the source), uploaded and drawn bilinear to the target size. Engines are never mixed:
  if the model fails on a frame, the job fails (`Encoder`) instead of continuing with classical frames.
- Presentation timestamps are the decoder's (`eglPresentationTimeANDROID`), so copied audio stays in sync.
- Rotation: everything stays in coded orientation. The decoder is configured with `rotation-degrees = 0` (a surface
  decoder would otherwise rotate the frame itself), the output size is computed from the coded size, and the
  source's rotation is written once as the MP4 orientation hint (`setOrientationHint`). Tested with 90° and 180°
  remuxed inputs for both engines. Nothing else from the source's metadata is copied.
- Output size: shorter side = 1080/1440/2160, aspect kept, even sides; a target not above the source's shorter side
  fails with `TargetNotLarger` (also from `estimate`). H.264 by default, HEVC on request (typed `Encoder`
  error if the device has no encoder for that size). Bitrate ≈ 0.12 bit/pixel (1440p30 ≈ 13 Mbit/s, 2160p30 ≈ 30
  Mbit/s; HEVC 60 %), clamped to the encoder's range; I-frame every second.
- Audio: passthrough; a track MP4 cannot carry is dropped and reported (`Done.audioCopied = false`), not fatal.
- Files: `filesDir/enhance/<uuid>.mp4.tmp` → sidecar `<uuid>.mp4.enhance.json` → atomic rename to `<uuid>.mp4`
  (an `.mp4` never exists without its sidecar). Cancellation or failure deletes temp file and sidecar; a cancellation
  that arrives after the rename (the caller then never sees `Done`) deletes output and sidecar too – the job either
  delivers `Done` or leaves nothing. The first access to the directory in a process deletes what a killed process
  left behind (files older than the process start: `*.tmp`, outputs without sidecar, sidecars without output).
  A free-space check (allocatable bytes ≥ estimate + 20 % + 50 MB) runs before decoding.
- Errors: `UpscaleError.Decoder` (unreadable input, no video track, decoder failure, frame timeout 2.5 s),
  `.Encoder` (no encoder for size/codec, encoder/GL failure, anything unattributed while producing frames),
  `.Storage` (space, muxer, file I/O), `.Memory` (`OutOfMemoryError`, or the ML frame enhancer ran out of memory),
  `.TargetNotLarger`, `.Cancelled` (via `awaitResult()`); `detail` is technical text for logs only.
- Threading: one dedicated thread per job (EGL is thread-bound), one job at a time (hardware codecs are scarce), jobs
  live in a process-wide scope so leaving the screen does not cancel them. `onProgress` is called on that thread.
  Once decoding is finished the loop waits on the encoder (10 ms) instead of spinning; a cancel stops the job within
  one frame (tested: < 3 s including the 2.5 s frame wait bound, progress < 1).

Emulator throughput of the classical clip path (SwiftShader + software H.264, 120 frames incl. decode/encode/mux):
960×540 → 1080p 33 ms/frame (busy 56), 480×270 → 1080p 28 (46), 640×360 → 720p 15 (37). The emulator's only
H.264 encoder stops at 2048×2048 / 8192 macroblocks, so 1440p and 2160p fail there with `UpscaleError.Encoder` –
on purpose covered by a test; real 1440p/2160p numbers need a phone. ML per frame for a 1080p source costs the ×2
frame time above (5.5–11 s) → ≈ 3–5.5 h per minute of 30 fps video on the emulator: offered only as an explicit option.
Incident: emulator-5556 restarted twice while the opt-in clip benchmark was writing 1920×1080 synthetic sources
(host heavily loaded by parallel builds; cause not established, the 12 functional tests never triggered it). The
benchmark now checks for a target encoder before writing any source, so the emulator skips the 1440p/2160p cases.

## Estimates (only when measurable)

- **Frames**: `capabilities()` measures once per device and model id (one warm-up call, then a 2×2-tile image through
  the real tiling path) and caches ms per source megapixel in the `enhance` DataStore (`@Named("enhance")`).
  `estimateMs(w, h, scale, engine) = megapixels × cost`; `null` when not measured. The first call takes ≈ 1 s on the
  emulator.
- **Clips before starting**: `estimate(request)` → `outputBytes` from the configured bitrates × duration (always
  calculable), `etaMs` only after one clip has completed on this device (ms per output megapixel-frame, cached per
  engine), else `null`.
- **Clips while running**: `UpscaleProgress.etaMs = elapsed × (1 − f) / f` and `outputBytesEstimate = bytes written / f`,
  both `null` until f ≥ 5 % (`MIN_MEASURED_FRACTION`); f = presentation time ÷ duration.

## Live upscaling assessment

`LiveUpscaleProbe.run()` → `LiveUpscaleDecision(offer, reason, classicalMs360p, classicalMs720p, mlMs360p, mlMs720p)`.
Classical is measured exactly as the live feature would run it: a 640×360 / 1280×720 bitmap drawn ×2 into a
`RenderNode` with `LiveSharpen.effect()` (AGSL unsharp mask, radius = scale) through `HardwareRenderer`, median of 30
frames until the GPU fence signals. ML numbers come from the measured FrameEnhancer cost. `offer` requires Android 13+
(RuntimeShader) and ≤ 8 ms for the 720p×2 case (a quarter of a 30 fps frame).

Emulator result: classical 4.8 ms (360p) / 16.6 ms (720p) quiet, 7.7–8.8 / 20.8–22.2 ms busy → `offer = false,
GPU_TOO_SLOW` (SwiftShader renders on the CPU); ML 616 ms / 2.5 s quiet → never real time.

**Recommendation for feature/live-view**: keep ML out of the live path. Use the stream in a `TextureView` (not
`SurfaceView`, RenderEffect cannot reach it) and, when the probe offers it and the user turns on
`AppPreferences.liveUpscale`, apply `LiveSharpen.effect(displayedWidth / streamWidth)` with
`Modifier.graphicsLayer { renderEffect = effect.asComposeRenderEffect() }` (or `View.setRenderEffect`). Scaling stays
the compositor's bilinear filter; the effect only restores edge contrast – label it "geschärft", not more detailed.
Run the probe once per app start (≈ 1 s on the emulator) and hide/disable the toggle with the existing note when
`offer` is false. No phone has been measured yet (procedure below).

## Honesty hooks

Every output gets `<output>.enhance.json` (written before the output is renamed into place):

```json
{ "format": 1, "kind": "ENHANCED_FRAME|UPSCALED_CLIP", "engine": "ML|CLASSICAL",
  "model": "quicksrnetmedium-x4-float-qaihub-0.63.0" | null, "scale": 4.0,
  "sourceMediaId": "<MediaItem.id>", "sourcePositionMs": 37000 | null,
  "createdAt": "2026-10-01T17:41:37.123+02:00", "reconstructed": true, "note": "rekonstruiert, kein Beweis" }
```

`EnhancementInfo.read(file)` parses it. The source id is required everywhere (`UpscaleRequest.sourceMediaId`,
`saveEnhancedFrame(context, frame, scale, sourceMediaId, positionMs)`), so no output exists without its link.
`saveEnhancedFrame` writes a JPEG (q 95) + sidecar for stills and stamps the JPEG itself (EXIF `ImageDescription`
= "rekonstruiert, kein Beweis", `UserComment` = note, engine, model, source id, `Software` = app), so the label
survives when the file is shared without its sidecar. MP4 outputs carry the label only in the sidecar. No field
claims verification; no source metadata (time, GPS) is copied into outputs. The UI phase creates
`MediaItem(kind = ENHANCED_FRAME | UPSCALED_CLIP, parentId = sourceMediaId)` and may reuse `EnhancedOutput.id` as
the item id.

**Cross-feature rule:** enhanced outputs (`ENHANCED_FRAME` / `UPSCALED_CLIP`) are never scanned for plates – generated
detail could invent characters. plates-ui refuses them as input, and an enhanced crop is never stored as a sighting.

## Interfaces for enhance-ui

```kotlin
interface FrameEnhancer {
  suspend fun enhance(src: Bitmap, scale: Int, onProgress: (Float) -> Unit): Bitmap            // contract §12
  suspend fun enhanceFrame(src: Bitmap, scale: Int, engine: EnhanceEngine? = null, onProgress: (Float) -> Unit = {}): EnhancedFrame
  suspend fun capabilities(): EnhancerCapabilities
}
interface ClipUpscaler {
  fun upscale(input: Uri, target: Resolution, sourceMediaId: String, onProgress: (UpscaleProgress) -> Unit): Deferred<UpscaleResult>  // contract §12 + required source id (Deferred is a Job)
  fun upscale(request: UpscaleRequest, onProgress: (UpscaleProgress) -> Unit): Deferred<UpscaleResult>
  suspend fun estimate(request: UpscaleRequest): Result<UpscaleEstimate>
}
suspend fun Deferred<UpscaleResult>.awaitResult(): UpscaleResult      // cancellation → Failed(UpscaleError.Cancelled)
suspend fun saveEnhancedFrame(context: Context, frame: EnhancedFrame, scale: Int, sourceMediaId: String, sourcePositionMs: Long?): EnhancedOutput
val ExportQuality.resolution: Resolution                              // Q1080 → P1080, Q1440 → P1440, Q2160 → P2160
class EnhanceException(val error: EnhanceError)                       // EnhanceError.TooLarge | Memory (frames)
class LiveUpscaleProbe @Inject constructor(frameEnhancer: FrameEnhancer) { suspend fun run(): LiveUpscaleDecision }
object LiveSharpen { fun effect(scale: Float, amount: Float = 0.6f): RenderEffect? }   // null below Android 13
```

Hilt: `EnhanceModule` binds `FrameEnhancer` → `DefaultFrameEnhancer`, `ClipUpscaler` → `DefaultClipUpscaler` (both
singletons) and provides `@Named("enhance") DataStore<Preferences>` (`enhance_measurements`, device measurements only).
No strings were added: German messages for `UpscaleError`, `EnhanceError` and `LiveUpscaleDecision.Reason` belong
to enhance-ui. `FrameEnhancer.enhanceFrame` reports progress on a `Dispatchers.Default` thread, `ClipUpscaler` on its
pipeline thread.

## Limits

- Emulator numbers are indicative only (x86, software GL, software codecs, shared host). Phone numbers: procedure below.
- The quality ranking comes from a synthetic, area-downscaled reference. Real dashcam footage (H.264/H.265 blocking,
  motion blur, noise) may favour Real-ESRGAN-style models that were trained on such degradations; re-run the
  comparison on owner recordings (plan input 3) before changing the model.
- CPU only (XNNPACK). GPU/NPU via LiteRT 2.x `CompiledModel` could make ML clips feasible on recent phones – upgrade
  path: swap `SrModel`'s `Interpreter` for `CompiledModel` with `Accelerator.GPU` and accept/strip the merged
  foreground-service permissions.
- APK size: LiteRT adds 4.5 MB (arm64-v8a), 2.6 MB (armeabi-v7a), 6.6 MB (x86), 6.3 MB (x86_64) of uncompressed native
  code; a universal APK grows by ≈ 20 MB. The release phase should consider `ndk.abiFilters` (arm64-v8a +
  armeabi-v7a) or splits. The model itself is 244 KiB.
- 1440p/2160p need a hardware encoder for that size; older phones may only offer 1440p or neither (typed `Encoder`
  error, test `targetBeyondEncoderLimitsFailsTyped`). HEVC output depends on the device encoder.
- `GL_MAX_TEXTURE_SIZE` bounds the ML clip path (≥ 4096 needed for ×2 of 1080p) and output frame size.
- Jobs survive leaving the screen but not process death (leftovers are swept at the next start); long clips should
  run in a foreground service/WorkManager in the UI phase. Progress callbacks arrive on the pipeline thread.
- Interlaced or HDR/10-bit sources are not special-cased (HDR is tone-unaware: GL reads 8-bit).
- Very first `capabilities()` call blocks for the one-off measurement (≈ 1 s emulator; longer on slow phones).

## Measure on a phone (owner procedure)

Prerequisites: USB debugging, phone not charging during battery runs, screen on, ~25 °C room, app installed from this
branch. Replace `<serial>` with the phone's `adb devices` id.

1. Build and install: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`, then
   `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk` and
   `adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
   Optional: candidate models in `app/src/androidTest/assets/candidates/` (names in `EnhanceBenchmark`) before building.
2. Baseline: `adb -s <serial> shell dumpsys thermalservice` (note *Thermal Status* and CPU/skin temperatures),
   `adb -s <serial> shell dumpsys battery unplug` (count battery while USB is attached),
   `adb -s <serial> shell dumpsys batterystats --reset`.
3. Run (times in logcat): `adb -s <serial> logcat -c`, then
   `adb -s <serial> shell am instrument -w -e enhanceBench 1 -e class me.ri3d.cam.enhance.EnhanceBenchmark me.ri3d.cam.test/androidx.test.runner.AndroidJUnitRunner`.
   For a sustained clip run add `-e enhanceClipSeconds 60` and `-e class me.ri3d.cam.enhance.EnhanceBenchmark#clipThroughput`.
   While it runs, every 10 s: `adb -s <serial> shell dumpsys thermalservice | grep -iE "status|temperature"`.
4. Read results: `adb -s <serial> logcat -d -s EnhanceBench:I` (QUALITY / SPEED / FULL / CLIP / LIVE lines; CLIP lines
   for 1080p → 1440p/2160p are the real ones), energy:
   `adb -s <serial> shell dumpsys batterystats me.ri3d.cam` (section "Estimated power use", mAh for the app uid),
   then `adb -s <serial> shell dumpsys battery reset`.
5. Record per phone: model/SoC, Android version, ms per 1080p frame ×2/×4 (FULL), clip ms/frame at 1440p and 2160p,
   LIVE decision and ms, mAh for the run, peak thermal status. Paste into this file under *Phone measurements*.

Hardware/phone verification items: real 1440p/2160p encode on a phone; live probe decision and visual check of
`LiveSharpen` on a real RTSP stream; ML vs classical on real recordings (owner input 3); battery and thermal behaviour
for a 60 s clip; HEVC sources from the recorder (if it records H.265).

## Phone measurements

None yet.
