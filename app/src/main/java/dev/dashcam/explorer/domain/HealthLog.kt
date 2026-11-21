package dev.dashcam.explorer.domain

/**
 * Reader for the camera's DeviceHealth/<day>/Health-<day>.log files (DESIGN.md 2.4, 10).
 *
 * Privacy rule: a Setting row can hold the camera's Wi-Fi password in plain text.
 * Its value is replaced here, at parse time, so it never reaches the screen or logs.
 */
sealed interface HealthEvent {
    val time: String // "HH:MM:SS" as written by the camera

    data class Boot(override val time: String, val counter: Int?) : HealthEvent
    data class Reboot(override val time: String, val kind: Kind, val raw: String) : HealthEvent {
        enum class Kind { COLD, HARD, OTHER }
    }
    data class Setting(override val time: String, val name: String, val value: String, val masked: Boolean) : HealthEvent
    data class SdCard(override val time: String, val message: String) : HealthEvent
    data class Unknown(override val time: String, val raw: String) : HealthEvent
}

data class HealthDay(val day: String, val events: List<HealthEvent>) {
    val cold get() = events.count { it is HealthEvent.Reboot && it.kind == HealthEvent.Reboot.Kind.COLD }
    val hard get() = events.count { it is HealthEvent.Reboot && it.kind == HealthEvent.Reboot.Kind.HARD }
    val restarts get() = events.count { it is HealthEvent.Reboot }
    val settings get() = events.filterIsInstance<HealthEvent.Setting>()
    val sdCard get() = events.filterIsInstance<HealthEvent.SdCard>()
}

object HealthLogParser {
    const val MASK = "••••••"
    private val SECRET = Regex("password|passwd|pwd|key|secret|token", RegexOption.IGNORE_CASE)
    private val TIME = Regex("""\d{2}-\d{2} (\d{2}:\d{2}:\d{2})""")

    fun parse(day: String, text: String): HealthDay {
        val events = ArrayList<HealthEvent>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("S.No")) continue
            val cols = line.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
            if (cols.size < 4) continue
            val module = cols[2]
            val time = TIME.find(cols[3])?.groupValues?.get(1) ?: cols[3]
            val data = cols.drop(4).joinToString(" ")
            events += when (module.uppercase()) {
                "BOOT" -> HealthEvent.Boot(time, data.toIntOrNull())
                "REBOOT" -> {
                    val what = data.substringAfter("::", data).trim()
                    val kind = when {
                        what.startsWith("cold", true) -> HealthEvent.Reboot.Kind.COLD
                        what.startsWith("hard", true) -> HealthEvent.Reboot.Kind.HARD
                        else -> HealthEvent.Reboot.Kind.OTHER
                    }
                    HealthEvent.Reboot(time, kind, what)
                }
                "SETTING" -> {
                    // "Local :: rotation::180"
                    val body = data.substringAfter(" :: ", data).trim()
                    val name = body.substringBefore("::").trim()
                    val value = body.substringAfter("::", "").trim()
                    val secret = SECRET.containsMatchIn(name)
                    HealthEvent.Setting(time, name, if (secret) MASK else value, secret)
                }
                "SDCARD" -> HealthEvent.SdCard(time, data)
                else -> HealthEvent.Unknown(time, "$module $data".trim())
            }
        }
        events.sortBy { it.time }
        return HealthDay(day, events)
    }

    /** Forced restarts are "frequent" above 5 in a day or above a fifth of all restarts. */
    fun frequentForced(days: List<HealthDay>): Boolean {
        val hard = days.sumOf { it.hard }
        val all = days.sumOf { it.restarts }
        return days.any { it.hard > 5 } || (all > 0 && hard * 5 > all)
    }

    /** True when most days have a restart at about 02:00 (the camera's nightly restart). */
    fun nightlyRestart(days: List<HealthDay>): Boolean {
        if (days.size < 3) return false
        val n = days.count { d -> d.events.any { it is HealthEvent.Reboot && it.time.startsWith("02:0") } }
        return n * 2 > days.size
    }
}
