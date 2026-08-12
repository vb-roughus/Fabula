package app.fabula.data

/**
 * How much a set of tracks "weighs" for progress purposes.
 *
 * Byte size when the server reported it for *every* file, otherwise seconds of
 * audio. Both are only weights, so the percentage works out either way -- but
 * requiring all sizes matters: mixing real bytes with zeros would understate the
 * total and make the bar jump past 100 %. That happens against an older server
 * or a partially rescanned library.
 */
internal fun downloadWeightOf(files: List<AudioFileDto>): Long =
    if (files.isNotEmpty() && files.all { it.sizeBytes > 0 }) files.sumOf { it.sizeBytes }
    else files.sumOf { parseTimeSpan(it.duration).toLong() }
