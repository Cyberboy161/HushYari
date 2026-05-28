package dev.hushyari.controller

import android.content.Context
import android.graphics.PointF
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implements [DeviceController] via Shizuku shell commands.
 * 🧠 Roubao mechanic: Bypasses AccessibilityService latency for power users
 * with Shizuku installed. Uses raw `input tap/swipe` and `am start` commands.
 *
 * Shizuku is a compileOnly dependency — all calls use reflection to avoid
 * hard-linking against the library at runtime.
 */
@Singleton
class ShizukuController @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceController {

    // ── Shizuku availability ────────────────────────────────────

    private val shizukuAvailable: Boolean by lazy { checkShizukuAvailable() }

    override fun isAvailable(): Boolean = shizukuAvailable

    private fun checkShizukuAvailable(): Boolean = try {
        val cls = Class.forName("rikka.shizuku.Shizuku")
        val method = cls.getMethod("checkSelfPermission")
        val result = method.invoke(null) as Int
        val granted = Class.forName("android.content.pm.PackageManager")
            .getField("PERMISSION_GRANTED")
            .getInt(null)
        result == granted
    } catch (e: Exception) {
        Timber.w("Shizuku not available: ${e.message}")
        false
    }

    // ── Shell execution ─────────────────────────────────────────

    private fun exec(command: String): String {
        if (!isAvailable()) throw IllegalStateException("Shizuku not available")
        return try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val newProcess = cls.getMethod(
                "newProcess",
                Array<String>::class.java,
                String::class.java,
                String::class.java,
            )
            val userServiceArgs = cls.getMethod("getUserServiceArgs", String::class.java)
            val bindUserService = cls.getMethod(
                "bindUserService",
                Class.forName("rikka.shizuku.Shizuku\$UserServiceArgs"),
                Class.forName("android.content.ServiceConnection"),
            )

            val process = newProcess.invoke(
                null,
                arrayOf("sh"),
                null,
                null,
            ) as? java.lang.Process
                ?: throw IllegalStateException("Shizuku failed to create process")

            val writer = process.outputStream.bufferedWriter()
            writer.write("$command 2>&1\n")
            writer.write("echo __DONE__\n")
            writer.flush()

            val reader = process.inputStream.bufferedReader()
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line == "__DONE__") break
                output.appendLine(line)
            }
            process.waitFor()
            output.toString().trim()
        } catch (e: Exception) {
            Timber.e(e, "Shizuku exec failed: $command")
            throw RuntimeException("Shizuku command failed: ${e.message}", e)
        }
    }

    // ── DeviceController impl ───────────────────────────────────

    override suspend fun tap(x: Float, y: Float, delayMs: Long) {
        exec("input tap ${x.toInt()} ${y.toInt()}")
        Timber.d("Shizuku tap: ($x, $y)")
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
    }

    override suspend fun swipe(from: PointF, to: PointF, durationMs: Long) {
        exec("input swipe ${from.x.toInt()} ${from.y.toInt()} ${to.x.toInt()} ${to.y.toInt()} $durationMs")
        Timber.d("Shizuku swipe: $from -> $to")
    }

    override suspend fun longPress(x: Float, y: Float, durationMs: Long) {
        exec("input swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} $durationMs")
        Timber.d("Shizuku longPress: ($x, $y)")
    }

    override suspend fun drag(points: List<PointF>, durationMs: Long) {
        require(points.size >= 2) { "Drag requires at least 2 points" }
        val totalDuration = durationMs
        val segmentDuration = totalDuration / (points.size - 1)
        for (i in 0 until points.size - 1) {
            val from = points[i]
            val to = points[i + 1]
            exec("input swipe ${from.x.toInt()} ${from.y.toInt()} ${to.x.toInt()} ${to.y.toInt()} $segmentDuration")
        }
        Timber.d("Shizuku drag: ${points.size} points")
    }

    override suspend fun typeText(text: String) {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace("\n", " ")
            .replace("\t", " ")
        exec("input text \"$escaped\"")
        Timber.d("Shizuku typeText: ${text.take(40)}")
    }

    override suspend fun pressKey(keyCode: Int) {
        exec("input keyevent $keyCode")
        Timber.d("Shizuku key: $keyCode")
    }

    override suspend fun scroll(direction: ScrollDirection, amount: Float) {
        val (x1, y1, x2, y2) = when (direction) {
            ScrollDirection.UP -> listOf(540f, 1500f, 540f, 500f)
            ScrollDirection.DOWN -> listOf(540f, 500f, 540f, 1500f)
            ScrollDirection.LEFT -> listOf(900f, 1000f, 100f, 1000f)
            ScrollDirection.RIGHT -> listOf(100f, 1000f, 900f, 1000f)
        }
        exec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} 300")
        Timber.d("Shizuku scroll: $direction")
    }

    override suspend fun openApp(packageName: String) {
        exec("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        Timber.d("Shizuku openApp: $packageName")
    }

    override suspend fun goHome() {
        exec("input keyevent KEYCODE_HOME")
        kotlinx.coroutines.delay(300)
    }

    override suspend fun goBack() {
        exec("input keyevent KEYCODE_BACK")
        kotlinx.coroutines.delay(200)
    }
}
