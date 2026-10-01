package to.axolotl.cam.plates

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.ceil

private const val TAG = "PlateEval"

/** Outcome of one sample. */
private class Scored(val sample: SyntheticPlates.Sample, val detections: List<PlateDetection>, val ms: Double) {
    /** Consistent with the truth: every character matches, a `?` matching any one character. */
    private fun consistent(text: String): Boolean {
        val expected = sample.expected ?: return false
        val pattern = text.filter { it == '?' || PlateText.normalize(it.toString()).isNotEmpty() }
        return pattern.length == expected.length && pattern.zip(expected).all { (p, e) -> p == '?' || p == e }
    }

    /** The seal shown as `?`: consistent once one `?` is removed (that `?` stands for no plate character). */
    private fun sealMarked(text: String) =
        !consistent(text) && text.indices.any { text[it] == '?' && consistent(text.removeRange(it, it + 1)) }

    val correct = detections.filter { consistent(it.text) }
    val seal = detections.filter { sealMarked(it.text) }
    val wrong = detections.filterNot { consistent(it.text) || sealMarked(it.text) }
    val exact = correct.any { !it.text.contains('?') }
    val uncertain = !exact && correct.isNotEmpty()
    val sealOnly = correct.isEmpty() && seal.isNotEmpty()
}

private fun p95(values: List<Double>) = values.sorted()[(ceil(values.size * 0.95) - 1).toInt().coerceAtLeast(0)]

private fun evaluate(recognizer: PlateRecognizer, samples: List<SyntheticPlates.Sample>): List<Scored> = runBlocking {
    samples.map { sample ->
        val frame = sample.render()
        val t0 = SystemClock.elapsedRealtimeNanos()
        val found = recognizer.recognize(frame, 0)
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        Scored(sample, found, ms)
    }
}

/**
 * Recall/precision of recognizer + candidate filter on the synthetic set (emulator: numbers are indicative only).
 * Run: adb shell am instrument -w -e class to.axolotl.cam.plates.PlateEvaluationTest to.axolotl.cam.test/androidx.test.runner.AndroidJUnitRunner
 * then: adb logcat -d -s PlateEval
 */
@RunWith(AndroidJUnit4::class)
class PlateEvaluationTest {
    @Test
    fun syntheticSet() {
        val recognizer = MlKitPlateRecognizer()
        val warmStart = SystemClock.elapsedRealtime()
        runBlocking { recognizer.recognize(SyntheticPlates.positives().first().render(), 0) }
        Log.i(TAG, "first call incl. model init: ${SystemClock.elapsedRealtime() - warmStart} ms")

        val positives = SyntheticPlates.positives()
        val negatives = SyntheticPlates.negatives()
        val scored = evaluate(recognizer, positives + negatives)
        recognizer.close()
        dumpImagesIfAsked(positives + negatives)

        scored.forEach { s ->
            val outcome = when {
                s.sample.expected == null -> if (s.detections.isEmpty()) "ok" else "FALSE POSITIVE"
                s.exact -> "exact"
                s.uncertain -> "uncertain"
                s.sealOnly -> "seal as ?"
                else -> "MISSED"
            }
            Log.i(TAG, "%-10s %-28s %-15s %6.0f ms  %s".format(
                s.sample.condition, s.sample.name.substringAfter(' '), outcome, s.ms,
                s.detections.joinToString { "${it.text} [${it.format}, conf=${it.confidence?.let { c -> "%.2f".format(c) }}]" },
            ))
        }
        if (scored.any { it.sample.expected != null && !it.exact || it.sample.expected == null && it.detections.isNotEmpty() }) {
            logRawOcr(scored.filter { it.sample.expected != null && !it.exact || it.sample.expected == null && it.detections.isNotEmpty() })
        }

        Log.i(TAG, "| condition | n | exact | ?-consistent | seal shown as ? | missed | wrong detections | mean ms |")
        Log.i(TAG, "| --- | --- | --- | --- | --- | --- | --- | --- |")
        for ((condition, group) in scored.groupBy { it.sample.condition }) {
            Log.i(TAG, "| $condition | ${group.size} | ${group.count { it.exact }} | ${group.count { it.uncertain }} | " +
                "${group.count { it.sealOnly }} | ${group.count { it.sample.expected != null && it.correct.isEmpty() && it.seal.isEmpty() }} | " +
                "${group.sumOf { it.wrong.size }} | %.0f |".format(group.map { it.ms }.average()))
        }
        val pos = scored.filter { it.sample.expected != null }
        val found = pos.count { it.correct.isNotEmpty() }
        val foundOrSeal = pos.count { it.correct.isNotEmpty() || it.seal.isNotEmpty() }
        val correctDetections = scored.sumOf { it.correct.size }
        val sealDetections = scored.sumOf { it.seal.size }
        val allDetections = scored.sumOf { it.detections.size }
        val formatOk = pos.count { s -> s.correct.any { it.format == s.sample.format } }
        val withConfidence = scored.flatMap { it.detections }.filter { it.confidence != null }
        Log.i(TAG, "recall strict (exact or ?-consistent) = $found/${pos.size} = %.2f; exact only = ${pos.count { it.exact }}; incl. seal shown as ? = $foundOrSeal/${pos.size} = %.2f"
            .format(found.toFloat() / pos.size, foundOrSeal.toFloat() / pos.size))
        Log.i(TAG, "precision strict = $correctDetections/$allDetections = %.2f; counting seal-as-? readings = ${correctDetections + sealDetections}/$allDetections = %.2f; negatives with a detection = ${scored.count { it.sample.expected == null && it.detections.isNotEmpty() }}/${scored.count { it.sample.expected == null }}"
            .format(correctDetections.toFloat() / allDetections, (correctDetections + sealDetections).toFloat() / allDetections))
        Log.i(TAG, "expected format among found = $formatOk/$found; detections with confidence = ${withConfidence.size}/$allDetections; " +
            "confidence of right ones ${scored.flatMap { s -> s.correct.mapNotNull { it.confidence } }.let { if (it.isEmpty()) "-" else "${it.min()}..${it.max()}" }}, " +
            "of wrong ones ${scored.flatMap { s -> s.wrong.mapNotNull { it.confidence } }.let { if (it.isEmpty()) "-" else "${it.min()}..${it.max()}" }}")
        Log.i(TAG, "ms/frame (1920x1080 in, OCR at 1280): mean %.0f, p95 %.0f".format(scored.map { it.ms }.average(), p95(scored.map { it.ms })))

        // Honesty: a reading with '?' never carries a confidence.
        assertTrue("? with confidence", scored.flatMap { it.detections }.none { '?' in it.text && it.confidence != null })

        // Regression guards, a little below the measured run in docs/features/plates.md.
        assertTrue("clean plates", scored.filter { it.sample.condition == "clean" }.count { it.exact } >= 6)
        assertTrue("recall incl. seal-as-?", foundOrSeal >= pos.size * 0.75)
        assertTrue("negatives", scored.count { it.sample.expected == null && it.detections.isNotEmpty() } <= 3)
    }

    /** Symbol-level OCR (text, ML Kit score) at the recognizer's 1280 px input, for misses and false positives. */
    private fun logRawOcr(items: List<Scored>) {
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        for (s in items) {
            val frame = s.sample.render()
            val input = Bitmap.createScaledBitmap(frame, 1280, frame.height * 1280 / frame.width, true)
            val text = Tasks.await(client.process(InputImage.fromBitmap(input, 0)))
            val lines = text.textBlocks.flatMap { it.lines }.joinToString(" | ") { line ->
                line.elements.joinToString(" ") { e ->
                    e.symbols.joinToString("") { sym ->
                        val coloured = sym.boundingBox?.let { inkColoredShare(input, it) >= 0.4f } == true
                        "%s%.0f%s".format(sym.text, sym.confidence * 100, if (coloured) "c" else "")
                    }
                }
            }
            Log.i(TAG, "raw OCR ${s.sample.name}: $lines")
        }
        client.close()
    }

    private fun dumpImagesIfAsked(samples: List<SyntheticPlates.Sample>) {
        if (InstrumentationRegistry.getArguments().getString("dumpImages") != "true") return
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "plates-eval").apply { mkdirs() }
        samples.forEachIndexed { i, s ->
            File(dir, "%02d-%s.jpg".format(i, s.name.replace(Regex("[^A-Za-z0-9-]+"), "_"))).outputStream()
                .use { s.render().compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
    }
}

/**
 * Debug-only benchmark: OCR + filter time per frame at 1280/960/640 px OCR width (1920×1080 synthetic frames).
 * Run: adb shell am instrument -w -e class to.axolotl.cam.plates.PlateBenchmark to.axolotl.cam.test/androidx.test.runner.AndroidJUnitRunner
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class PlateBenchmark {
    @Test
    fun widths() {
        val samples = SyntheticPlates.positives()
        for (width in listOf(1280, 960, 640)) {
            val recognizer = MlKitPlateRecognizer(maxWidth = width)
            runBlocking { repeat(3) { recognizer.recognize(samples[it].render(), 0) } } // warm-up
            val scored = evaluate(recognizer, samples)
            recognizer.close()
            val ms = scored.map { it.ms }
            Log.i(TAG, "benchmark width=$width: avg %.0f ms, p95 %.0f ms, recall strict %d/%d (exact %d), seal as ? %d".format(
                ms.average(), p95(ms), scored.count { it.correct.isNotEmpty() }, scored.size, scored.count { it.exact },
                scored.count { it.sealOnly },
            ))
        }
    }

    /** Offers 30 fps for 6 s to the live processor (30 % budget) and logs what the throttle made of it. */
    @Test
    fun liveThrottle() = runBlocking {
        val frames = SyntheticPlates.positives().take(4).map { it.render() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val processor = LivePlateProcessor(MlKitPlateRecognizer(), repository = null, scope = scope)
        val start = SystemClock.elapsedRealtime()
        var offered = 0
        while (SystemClock.elapsedRealtime() - start < 6_000) {
            processor.submit(Frame(frames[offered % frames.size], SystemClock.elapsedRealtime()))
            offered++
            delay(33)
        }
        val stats = processor.stats.value
        processor.close()
        scope.cancel()
        Log.i(TAG, "live throttle (budget 0.30, 30 fps offered for 6 s, $offered frames): $stats")
        assertTrue("busy share ${stats.busyFraction}", stats.busyFraction < 0.45f)
    }
}
