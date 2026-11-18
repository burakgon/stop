package io.github.burakgon.stop.daemon

import java.io.File

/**
 * Temporary diagnostic sink. The daemon's log lines never reach logcat (it runs inside a Shizuku
 * user service), so during field debugging everything interesting is appended to a file next to
 * restrictions.json, which a shell can read back.
 */
internal class DebugDump(private val dir: File) {

    private val file = File(dir, "debug.log")
    private var pending = 0

    fun write(line: String) {
        try {
            if (!dir.exists()) dir.mkdirs()
            if (file.length() > MAX_BYTES) file.delete()
            file.appendText("${System.currentTimeMillis()} $line\n")
            if (++pending >= 64) {
                pending = 0
            }
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val MAX_BYTES = 256L * 1024L
    }
}
