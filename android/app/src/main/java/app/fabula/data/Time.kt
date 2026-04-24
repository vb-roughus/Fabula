package app.fabula.data

/** Parses .NET TimeSpan strings like "HH:MM:SS", "HH:MM:SS.fffffff" or "D.HH:MM:SS" into seconds. */
fun parseTimeSpan(ts: String?): Double {
    if (ts.isNullOrBlank()) return 0.0
    var rest = ts
    var days = 0.0
    val firstDot = rest.indexOf('.')
    val firstColon = rest.indexOf(':')
    if (firstDot in 0 until firstColon) {
        days = rest.substring(0, firstDot).toDoubleOrNull() ?: 0.0
        rest = rest.substring(firstDot + 1)
    }
    val parts = rest.split(':')
    if (parts.size < 3) return days * 86400.0
    val h = parts[0].toDoubleOrNull() ?: 0.0
    val m = parts[1].toDoubleOrNull() ?: 0.0
    val s = parts[2].toDoubleOrNull() ?: 0.0
    return days * 86400.0 + h * 3600.0 + m * 60.0 + s
}

/** Formats seconds back to a .NET TimeSpan literal. */
fun toTimeSpanString(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0)
    val h = (total / 3600.0).toInt()
    val m = ((total % 3600.0) / 60.0).toInt()
    val s = total - h * 3600 - m * 60
    return "%02d:%02d:%06.3f".format(h, m, s)
}

fun formatClock(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatDurationHuman(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        h == 0L -> "$m min"
        m == 0L -> "$h h"
        else -> "$h h $m min"
    }
}
