package io.github.burakgon.stop.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Preinstalled-package audit shipped with the app.
 *
 * The list was produced on a Sony XQ-FS72 (Android 15) by inspecting every system package for a
 * launcher entry, the PERSISTENT flag, the APK path, provider/telephony/GMS components and actual
 * usage history, and is split into three tiers:
 *
 * - [Tier.A] backend-only extras nobody depends on: safe to freeze;
 * - [Tier.B] has an icon or a coupled feature: freeze only if you do not use it;
 * - [Tier.C] framework, persistent processes, telephony, camera, GMS: never touch.
 *
 * Packages in the file that are not installed are dropped by the caller, so the same list is
 * harmless on other devices.
 */
object BloatwareCatalog {

    enum class Tier { A, B, C }

    data class Entry(val packageName: String, val tier: Tier, val note: String, val vendor: String)

    @Volatile
    private var cached: List<Entry>? = null

    suspend fun load(context: Context): List<Entry> = cached ?: withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(ASSET).bufferedReader().use { parse(it.readText()) }
        }.getOrDefault(emptyList())
    }.also { cached = it }

    private fun parse(text: String): List<Entry> = buildList {
        var header = true
        for (line in text.lineSequence()) {
            if (header) {
                header = false
                continue
            }
            if (line.isBlank()) continue
            val fields = split(line)
            if (fields.size < 4 || fields[0].isBlank()) continue
            val tier = when (fields[1].trim()) {
                "A" -> Tier.A
                "B" -> Tier.B
                "C" -> Tier.C
                else -> continue
            }
            add(Entry(fields[0].trim(), tier, fields[2], fields[3]))
        }
    }

    /** Minimal CSV splitter: our file only quotes the description and vendor columns. */
    private fun split(line: String): List<String> {
        val out = ArrayList<String>(4)
        val current = StringBuilder()
        var quoted = false
        for (c in line) {
            when {
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    out += current.toString()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
        }
        out += current.toString()
        return out
    }

    private const val ASSET = "bloatware/tiers.csv"
}
