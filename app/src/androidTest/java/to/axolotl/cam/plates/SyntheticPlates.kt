package to.axolotl.cam.plates

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministic synthetic evaluation set: dashcam-like 1920×1080 scenes with a rendered plate (or street text for
 * negatives). Fictional plates; system fonts stand in for the FE-Schrift, so results are indicative only.
 */
object SyntheticPlates {
    const val WIDTH = 1920
    const val HEIGHT = 1080

    /** [expected] = normalized plate (null for negatives); [format] = expected filter format. */
    class Sample(val name: String, val condition: String, val expected: String?, val format: PlateFormat?, val render: () -> Bitmap)

    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val sans = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val serif = Typeface.create(Typeface.SERIF, Typeface.BOLD)

    /** A plate: groups as printed, EU band code (null = none), German seals between group 0 and 1. */
    class Spec(val groups: List<String>, val band: String? = "D", val seals: Boolean = true, val font: Typeface = condensed) {
        val normalized get() = PlateText.normalize(groups.joinToString(""))
    }

    private fun de(city: String, letters: String, digits: String, font: Typeface = condensed) =
        Spec(listOf(city, letters, digits), font = font)

    class Look(
        val plateWidth: Int = 360,
        val skew: Float = 0f, // right edge height relative to left (perspective), e.g. 0.8
        val rotate: Float = 0f,
        val blur: Int = 0, // downscale factor for defocus
        val motion: Int = 0, // horizontal motion blur in px
        val noise: Int = 0, // noise amplitude 0..255
        val night: Boolean = false,
        val occlude: Float = 0f, // share of the plate text width covered by a bar
        val twoLine: Boolean = false,
        val x: Float = 0.5f,
        val y: Float = 0.62f,
    )

    fun positives(): List<Sample> = buildList {
        fun add(condition: String, spec: Spec, look: Look = Look(), format: PlateFormat = PlateFormat.GERMAN) =
            add(Sample("$condition ${spec.groups.joinToString(" ")}", condition, spec.normalized, format) { scene(spec, look) })

        add("clean", de("B", "MK", "4821"))
        add("clean", de("HH", "JK", "553", sans))
        add("clean", de("M", "AB", "9042", mono))
        add("clean", de("K", "LT", "207", medium))
        add("clean", de("F", "ZO", "1586", condensed), Look(plateWidth = 420))
        add("clean", de("TF", "GH", "64", sans), Look(x = 0.3f))
        add("clean", de("DO", "RS", "1193", serif))
        add("clean", de("HB", "X", "7", condensed), Look(x = 0.7f, y = 0.55f))

        add("small", de("B", "AE", "7730"), Look(plateWidth = 140))
        add("small", de("S", "KL", "318", sans), Look(plateWidth = 170))
        add("small", de("N", "PQ", "45", mono), Look(plateWidth = 200))
        add("small", de("HD", "UV", "2201"), Look(plateWidth = 240, x = 0.25f))

        add("skew", de("B", "MK", "4821"), Look(skew = 0.8f))
        add("skew", de("WI", "CD", "88", sans), Look(skew = 1.2f))
        add("skew", de("KA", "FE", "3310"), Look(rotate = 6f))
        add("skew", de("OF", "NB", "512", medium), Look(rotate = -8f, skew = 0.85f))
        add("skew", de("L", "XY", "9001"), Look(skew = 0.7f, plateWidth = 300))

        add("blur", de("B", "MK", "4821"), Look(blur = 3))
        add("blur", de("D", "AL", "42", sans), Look(blur = 4))
        add("blur", de("MZ", "TT", "6006"), Look(motion = 12))
        add("blur", de("GI", "R", "730", mono), Look(motion = 24))

        add("noise", de("B", "MK", "4821"), Look(noise = 60))
        add("noise", de("HN", "EK", "117", sans), Look(noise = 90))
        add("noise", de("R", "SU", "2468", medium), Look(noise = 120))
        add("noise", de("PB", "WZ", "35"), Look(noise = 90, plateWidth = 220))

        add("night", de("B", "MK", "4821"), Look(night = true))
        add("night", de("H", "AN", "9050", sans), Look(night = true, noise = 30))
        add("night", de("KS", "OL", "73", condensed), Look(night = true, plateWidth = 260))
        add("night", de("E", "GT", "1984", mono), Look(night = true, noise = 50, blur = 2))

        add("occlusion", de("B", "MK", "4821"), Look(occlude = 0.2f))
        add("occlusion", de("BN", "CR", "640", sans), Look(occlude = 0.35f))
        add("occlusion", de("MS", "EL", "1250"), Look(occlude = 0.5f))

        add("eu-generic", Spec(listOf("AB-123-CD"), band = "F", seals = false), format = PlateFormat.GENERIC)
        add("eu-generic", Spec(listOf("WA", "12345"), band = "PL", seals = false, font = sans), format = PlateFormat.GENERIC)
        add("eu-generic", Spec(listOf("AB", "123CD"), band = "I", seals = false), format = PlateFormat.GENERIC)
        add("eu-generic", Spec(listOf("1234", "BCD"), band = "E", seals = false, font = sans), format = PlateFormat.GENERIC)

        add("suffix-HE", Spec(listOf("B", "MK", "482E")))
        add("suffix-HE", Spec(listOf("M", "AB", "123H"), font = sans))

        add("umlaut", de("TÜ", "AB", "123"))
        add("umlaut", de("MÜ", "X", "77", sans))

        add("two-line", de("B", "MK", "482"), Look(twoLine = true, plateWidth = 200))
        add("two-line", de("HH", "A", "99", sans), Look(twoLine = true, plateWidth = 200))
    }

    fun negatives(): List<Sample> = listOf(
        neg("stop-sign") { c -> stopSign(c) },
        neg("tempo-30-banner") { c -> panel(c, listOf("Tempo 30"), Color.WHITE, Color.BLACK, 0.5f) },
        neg("zone-30") { c -> panel(c, listOf("Zone", "30"), Color.WHITE, Color.BLACK, 0.5f) },
        neg("autobahn") { c -> panel(c, listOf("A 7", "Hamburg"), Color.rgb(0, 80, 160), Color.WHITE, 0.4f) },
        neg("bundesstrasse") { c -> panel(c, listOf("B 27"), Color.rgb(250, 200, 0), Color.BLACK, 0.6f) },
        neg("exit") { c -> panel(c, listOf("Ausfahrt 12", "Kassel-Ost"), Color.rgb(0, 80, 160), Color.WHITE, 0.45f) },
        neg("shop") { c -> panel(c, listOf("Bäckerei Müller", "seit 1952"), Color.rgb(240, 230, 210), Color.rgb(90, 40, 20), 0.35f) },
        neg("parking") { c -> panel(c, listOf("P", "max. 2 Std."), Color.rgb(0, 80, 160), Color.WHITE, 0.55f) },
        neg("phone-ad") { c -> panel(c, listOf("Tel. 0800 123456"), Color.WHITE, Color.rgb(200, 0, 0), 0.6f) },
        neg("random-text") { c -> panel(c, listOf("Lorem ipsum dolor", "sit amet 2024"), Color.WHITE, Color.DKGRAY, 0.4f) },
        neg("street-name") { c -> panel(c, listOf("Hauptstraße 12"), Color.WHITE, Color.BLACK, 0.55f) },
        neg("km-marker") { c -> panel(c, listOf("km 125,5"), Color.WHITE, Color.BLACK, 0.6f) },
        // uppercase hard negatives
        neg("zone-30-upper") { c -> panel(c, listOf("ZONE 30"), Color.WHITE, Color.BLACK, 0.5f) },
        neg("truck") { c -> panel(c, listOf("MAN TGX 18.510"), Color.rgb(200, 200, 205), Color.rgb(20, 20, 60), 0.45f) },
        neg("taxi") { c -> panel(c, listOf("TAXI 4711"), Color.rgb(250, 230, 120), Color.BLACK, 0.55f) },
    )

    private fun neg(name: String, draw: (Canvas) -> Unit) = Sample(name, "negative", null, null) {
        background().also { draw(Canvas(it)) }
    }

    // --- rendering ---

    private fun background(night: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint()
        val (sky, road) = if (night) Color.rgb(8, 10, 18) to Color.rgb(20, 20, 22) else Color.rgb(170, 190, 210) to Color.rgb(95, 95, 100)
        paint.shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), sky, road, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        return bmp
    }

    fun scene(spec: Spec, look: Look): Bitmap {
        val bmp = background(look.night)
        val c = Canvas(bmp)
        val plate = plateBitmap(spec, look.plateWidth, look.twoLine)
        val cx = WIDTH * look.x
        val cy = HEIGHT * look.y
        // car rear around the plate
        val car = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (look.night) Color.rgb(25, 25, 30) else Color.rgb(60, 70, 85) }
        c.drawRoundRect(RectF(cx - plate.width * 1.6f, cy - plate.height * 3.2f, cx + plate.width * 1.6f, cy + plate.height * 1.6f), 40f, 40f, car)

        val w = plate.width.toFloat()
        val h = plate.height.toFloat()
        val m = Matrix()
        val rightH = h * (if (look.skew == 0f) 1f else look.skew)
        val src = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        val dst = floatArrayOf(-w / 2, -h / 2, w / 2, -rightH / 2, w / 2, rightH / 2, -w / 2, h / 2)
        m.setPolyToPoly(src, 0, dst, 0, 4)
        m.postRotate(look.rotate)
        m.postTranslate(cx, cy)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (look.motion > 0) {
            val steps = 8
            for (i in 0 until steps) {
                paint.alpha = 255 / (i + 1) // running average of the shifted copies
                val shifted = Matrix(m).apply { postTranslate(look.motion * (i.toFloat() / (steps - 1) - 0.5f), 0f) }
                c.drawBitmap(plate, shifted, paint)
            }
        } else {
            c.drawBitmap(plate, m, paint)
        }
        if (look.occlude > 0f) {
            // a bike-rack bar across the right part of the text
            val bar = Paint().apply { color = Color.rgb(30, 30, 30) }
            c.drawRect(cx + w / 2 - w * look.occlude - w * 0.04f, cy - h, cx + w / 2 - w * 0.04f, cy + h, bar)
        }

        var out = bmp
        if (look.night) out = dim(out)
        if (look.blur > 1) {
            val small = Bitmap.createScaledBitmap(out, WIDTH / look.blur, HEIGHT / look.blur, true)
            out = Bitmap.createScaledBitmap(small, WIDTH, HEIGHT, true)
        }
        if (look.noise > 0) addNoise(out, look.noise, seed = spec.normalized.hashCode())
        return out
    }

    /** Plate artwork, 520:110 mm proportions (or a 280:200 two-line motorbike plate). */
    fun plateBitmap(spec: Spec, width: Int, twoLine: Boolean = false): Bitmap {
        val height = if (twoLine) (width * 200f / 280f).roundToInt() else (width * 110f / 520f).roundToInt()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val unit = if (twoLine) height / 2f else height.toFloat()
        p.color = Color.rgb(245, 245, 242)
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), unit * 0.08f, unit * 0.08f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = unit * 0.035f
        p.color = Color.BLACK
        c.drawRoundRect(RectF(unit * 0.03f, unit * 0.03f, width - unit * 0.03f, height - unit * 0.03f), unit * 0.07f, unit * 0.07f, p)
        p.style = Paint.Style.FILL

        var left = unit * 0.08f
        spec.band?.let { code ->
            val bandW = unit * 0.36f
            p.color = Color.rgb(0, 51, 153)
            c.drawRect(unit * 0.05f, unit * 0.05f, unit * 0.05f + bandW, height - unit * 0.05f, p)
            p.color = Color.rgb(255, 204, 0)
            val ringX = unit * 0.05f + bandW / 2
            val ringY = unit * 0.3f
            for (i in 0 until 12) {
                val a = Math.toRadians(i * 30.0)
                c.drawCircle(ringX + (cos(a) * unit * 0.12f).toFloat(), ringY + (sin(a) * unit * 0.12f).toFloat(), unit * 0.022f, p)
            }
            p.color = Color.WHITE
            p.typeface = sans
            p.textSize = unit * 0.3f
            p.textAlign = Paint.Align.CENTER
            c.drawText(code, ringX, height - unit * 0.14f, p)
            left = unit * 0.05f + bandW + unit * 0.1f
        }

        p.color = Color.rgb(15, 15, 15)
        p.typeface = spec.font
        p.textAlign = Paint.Align.LEFT
        p.textSize = unit * 0.72f
        val right = width - unit * 0.1f
        if (twoLine) {
            // motorbike: city and letters on top, digits below
            drawGroups(c, p, listOf(spec.groups[0], spec.groups[1]), spec.seals, left, right, unit * 0.86f, unit)
            drawGroups(c, p, listOf(spec.groups[2]), false, left, right, unit * 1.86f, unit)
        } else {
            drawGroups(c, p, spec.groups, spec.seals, left, right, unit * 0.84f, unit)
        }
        return bmp
    }

    private fun drawGroups(c: Canvas, p: Paint, groups: List<String>, seals: Boolean, left: Float, right: Float, baseline: Float, unit: Float) {
        val sealGap = if (seals) unit * 0.5f else unit * 0.28f
        val space = unit * 0.28f
        val gaps = groups.indices.drop(1).sumOf { (if (it == 1) sealGap else space).toDouble() }.toFloat()
        p.textScaleX = 1f
        val natural = groups.sumOf { p.measureText(it).toDouble() }.toFloat()
        p.textScaleX = ((right - left - gaps) / natural).coerceAtMost(1f)
        var x = left + (right - left - gaps - natural * p.textScaleX) / 2
        groups.forEachIndexed { i, g ->
            if (i == 1 && seals) {
                val sx = x - sealGap / 2
                val sp = Paint(Paint.ANTI_ALIAS_FLAG)
                sp.color = Color.rgb(230, 120, 40) // inspection sticker
                c.drawCircle(sx, baseline - unit * 0.5f, unit * 0.14f, sp)
                sp.color = Color.rgb(150, 150, 160) // state seal
                c.drawCircle(sx, baseline - unit * 0.17f, unit * 0.14f, sp)
            }
            c.drawText(g, x, baseline, p)
            x += p.measureText(g) + if (i == 0) sealGap else space
        }
        p.textScaleX = 1f
    }

    private fun dim(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            // contrast 0.3, brightness down: night without headlight on the plate
            colorFilter = android.graphics.ColorMatrixColorFilter(
                floatArrayOf(0.3f, 0f, 0f, 0f, 12f, 0f, 0.3f, 0f, 0f, 12f, 0f, 0f, 0.32f, 0f, 16f, 0f, 0f, 0f, 1f, 0f),
            )
        }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun addNoise(bmp: Bitmap, amplitude: Int, seed: Int) {
        val r = Random(seed)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (i in px.indices) {
            val n = r.nextInt(-amplitude / 2, amplitude / 2 + 1)
            val c = px[i]
            px[i] = Color.rgb(
                (Color.red(c) + n).coerceIn(0, 255),
                (Color.green(c) + n).coerceIn(0, 255),
                (Color.blue(c) + n).coerceIn(0, 255),
            )
        }
        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    }

    private fun stopSign(c: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 20, 30) }
        val cx = WIDTH * 0.6f
        val cy = HEIGHT * 0.4f
        val r = 160f
        val path = Path()
        for (i in 0 until 8) {
            val a = Math.toRadians(22.5 + i * 45.0)
            val x = cx + (cos(a) * r).toFloat()
            val y = cy + (sin(a) * r).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
        p.color = Color.WHITE
        p.typeface = sans
        p.textSize = 95f
        p.textAlign = Paint.Align.CENTER
        c.drawText("STOP", cx, cy + 34f, p)
    }

    private fun panel(c: Canvas, lines: List<String>, bg: Int, fg: Int, x: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; textSize = 70f }
        val w = lines.maxOf { p.measureText(it) } + 80f
        val h = lines.size * 90f + 50f
        val left = WIDTH * x - w / 2
        val top = HEIGHT * 0.3f
        p.color = bg
        c.drawRoundRect(RectF(left, top, left + w, top + h), 20f, 20f, p)
        p.color = fg
        lines.forEachIndexed { i, line -> c.drawText(line, left + 40f, top + 95f + i * 90f, p) }
    }
}
