package me.ri3d.cam.core.log

import me.ri3d.cam.BuildConfig

/**
 * App logging. Never log tokens, session keys, passwords or OAuth material; anything that may carry
 * protocol or auth JSON is passed through [redact] first, e.g. `Log.d(TAG, redact(frameJson))`.
 */
object Log {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) android.util.Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        android.util.Log.w(tag, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        android.util.Log.e(tag, message, error)
    }
}

private val sensitiveKey = Regex(
    "\"(token|tokenNum|aescode|passwd|password|key|access_token|refresh_token|idToken|id_token)\"\\s*:\\s*",
    RegexOption.IGNORE_CASE,
)

/**
 * Replaces the value of every sensitive key in a JSON(-like) text with `"***"`, whatever the value's
 * type (string, number, boolean, null, object, array). Tolerates truncated or invalid JSON, so partial frames
 * can be logged. Not covered: JSON that is double-encoded inside a string value (`"param":"{\"token\":1}"`)
 * stays unmasked.
 */
fun redact(json: String): String {
    val out = StringBuilder(json.length)
    var pos = 0
    for (match in sensitiveKey.findAll(json)) {
        if (match.range.first < pos) continue // nested inside a value that is already masked
        out.append(json, pos, match.range.last + 1).append("\"***\"")
        pos = endOfValue(json, match.range.last + 1)
    }
    return out.append(json, pos, json.length).toString()
}

/** Index just past the JSON value starting at [start]. */
private fun endOfValue(s: String, start: Int): Int {
    var depth = 0
    var inString = false
    var i = start
    while (i < s.length) {
        val c = s[i]
        when {
            inString -> when (c) {
                '\\' -> i++
                '"' -> {
                    inString = false
                    if (depth == 0) return i + 1
                }
            }
            c == '"' -> inString = true
            c == '{' || c == '[' -> depth++
            c == '}' || c == ']' -> {
                if (depth == 0) return i
                if (--depth == 0) return i + 1
            }
            depth == 0 && (c == ',' || c.isWhitespace()) -> return i
        }
        i++
    }
    return s.length
}
