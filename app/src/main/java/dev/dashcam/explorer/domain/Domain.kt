package dev.dashcam.explorer.domain

/**
 * Pure rules about the dashcam card: how names are read, how files are sorted and
 * how a Front file finds its Rear partner. No Android imports, so all of this is
 * covered by plain JVM unit tests. See DESIGN.md section 2.
 */

enum class FileKind { VIDEO, PHOTO, LOG, OTHER }

enum class Camera { FRONT, REAR }

fun kindOf(name: String): FileKind = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "mov" -> FileKind.VIDEO
    "jpeg", "jpg", "png" -> FileKind.PHOTO
    "log", "txt", "trip" -> FileKind.LOG
    else -> FileKind.OTHER
}

/** What a Qubo file name tells us. Timestamps are Unix milliseconds. */
data class QuboName(val prefix: String, val rideMs: Long?, val clipMs: Long, val ext: String) {
    companion object {
        // <anything><13 digits>_<13 digits>.<ext>; the first number is optional context.
        private val TS = Regex("""^(.*?)(\d{13})_(\d{13})(\.\w+)$""")

        fun parse(name: String): QuboName? {
            val m = TS.matchEntire(name) ?: return null
            return QuboName(m.groupValues[1], m.groupValues[2].toLongOrNull(), m.groupValues[3].toLong(), m.groupValues[4])
        }
    }

    /** Everything except the clip time; two files "match loosely" when these are equal. */
    val stem: String get() = prefix + (rideMs ?: "") + "_"
}

/** Recording time from the name; file dates on the card are unreliable. */
fun clipTimeMs(name: String): Long? = QuboName.parse(name)?.clipMs

/** True for clip names like H1725_… / L1725_… (one letter, four digits, underscore). */
private fun isClipName(name: String, letter: Char? = null): Boolean =
    name.length > 5 && name[5] == '_' && (if (letter == null) name[0] == 'H' || name[0] == 'L' else name[0] == letter) &&
        name[1].isDigit() && name[2].isDigit() && name[3].isDigit() && name[4].isDigit()

/** "front" / "rear" / "event" tag shown next to a file, from its name alone. Called for every visible row. */
fun tagOf(name: String): String? = when {
    isClipName(name, 'H') -> "front"
    isClipName(name, 'L') -> "rear"
    name.startsWith("EVENT_") -> "event"
    else -> null
}

/**
 * Ascending by name, case-insensitive, digit runs compared as numbers (2 before 10).
 * Sorting a 13,000-file card calls this a few hundred thousand times, so it walks the
 * two strings in place and allocates nothing.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        val la = a.length
        val lb = b.length
        while (i < la && j < lb) {
            val ca = a[i]
            val cb = b[j]
            val da = ca.isDigit()
            val db = cb.isDigit()
            if (da && db) {
                // skip leading zeros, then the longer run of digits is the larger number
                while (i < la && a[i] == '0') i++
                while (j < lb && b[j] == '0') j++
                var ei = i
                while (ei < la && a[ei].isDigit()) ei++
                var ej = j
                while (ej < lb && b[ej].isDigit()) ej++
                val lenA = ei - i
                val lenB = ej - j
                if (lenA != lenB) return lenA.compareTo(lenB)
                while (i < ei) {
                    val d = a[i].compareTo(b[j])
                    if (d != 0) return d
                    i++; j++
                }
            } else if (da != db) {
                return if (da) -1 else 1 // numbers sort before text, like the web version
            } else {
                val d = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (d != 0) return d
                i++; j++
            }
        }
        return (la - i).compareTo(lb - j)
    }
}

enum class Match { EXACT, NEAREST, NONE }

/** Result of looking for the other camera's file. Paths are "/"-separated, relative to the source root. */
data class Partner(val side: Camera?, val otherPath: String?, val match: Match)

object Pairing {
    const val NEAREST_WINDOW_MS = 5_000L

    /**
     * Find the same moment from the other camera.
     *
     * Swap the nearest Front/Rear folder in the path, then look there for the same
     * file name, the H<->L video prefix swap, or (for photos the two cameras take a
     * moment apart) the nearest timestamp within 5 seconds.
     *
     * @param listDir names of the files in a folder, or null when the folder does not exist
     */
    fun counterpart(path: String, listDir: (String) -> Collection<String>?): Partner {
        val parts = path.trim('/').split('/')
        val idx = (parts.size - 2 downTo 0).firstOrNull { parts[it] == "Front" || parts[it] == "Rear" }
            ?: return Partner(null, null, Match.NONE)
        val side = if (parts[idx] == "Front") Camera.FRONT else Camera.REAR
        val otherDirParts = parts.toMutableList().also {
            it[idx] = if (side == Camera.FRONT) "Rear" else "Front"
            it.removeAt(it.size - 1)
        }
        val otherDir = otherDirParts.joinToString("/")
        val names = listDir(otherDir) ?: return Partner(side, null, Match.NONE)
        val set = names as? Set<String> ?: names.toHashSet()
        val name = parts.last()

        val candidates = buildList {
            add(name)
            if (isClipName(name)) add((if (name[0] == 'H') "L" else "H") + name.substring(1))
        }
        candidates.firstOrNull { it in set }?.let { return Partner(side, "$otherDir/$it", Match.EXACT) }

        val mine = QuboName.parse(name)
        if (mine != null) {
            val best = set.asSequence()
                .mapNotNull { n -> QuboName.parse(n)?.let { n to it } }
                .filter { (_, q) -> q.stem == mine.stem && q.ext == mine.ext }
                .map { (n, q) -> n to kotlin.math.abs(q.clipMs - mine.clipMs) }
                .filter { it.second <= NEAREST_WINDOW_MS }
                .minByOrNull { it.second }
            if (best != null) return Partner(side, "$otherDir/${best.first}", Match.NEAREST)
        }
        return Partner(side, null, Match.NONE)
    }
}

/** Formatting shared by the screens. */
object Fmt {
    fun size(bytes: Long): String {
        var b = bytes.toDouble()
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var i = 0
        while (b >= 1024 && i < units.size - 1) {
            b /= 1024; i++
        }
        return when {
            i == 0 -> "${bytes} B"
            b >= 100 -> "%.0f %s".format(b, units[i])
            else -> "%.1f %s".format(b, units[i])
        }
    }

    fun duration(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Dates before 2000 mean the card had no real date (FAT default or unset clock). */
    fun isRealDate(ms: Long?): Boolean = ms != null && ms >= 946_684_800_000L
}
