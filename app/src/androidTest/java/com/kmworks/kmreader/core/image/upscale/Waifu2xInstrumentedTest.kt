package com.kmworks.kmreader.core.image.upscale

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 0 PoC verification (docs/waifu2x-upscaling-plan.md §10):
 * proves the ncnn+Vulkan build chain end-to-end on a real device — model extraction,
 * engine init, tiled 2x upscale, and per-run timing in logcat (tag Waifu2xJNI).
 *
 * Output PNGs are written to the app's external files dir for visual inspection:
 *   /sdcard/Android/data/com.kmworks.kmreader/files/waifu2x-poc/
 *
 * Run: ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.kmworks.kmreader.core.image.upscale.Waifu2xInstrumentedTest
 */
@RunWith(AndroidJUnit4::class)
class Waifu2xInstrumentedTest {

    @Test
    fun upscaleTestPattern2x() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = makeTestPattern(720, 540)

        assertTrue("engine init failed", Waifu2xBridge.ensureInitialized(context, Waifu2xBridge.DenoiseModel.LOW))
        Log.i(TAG, "backend: ${if (Waifu2xBridge.isVulkan) "vulkan" else "cpu"}")

        val t0 = System.currentTimeMillis()
        val output = Waifu2xBridge.process(input)
        val elapsed = System.currentTimeMillis() - t0

        assertNotNull("process returned null", output)
        output!!
        assertEquals(input.width * Waifu2xBridge.SCALE, output.width)
        assertEquals(input.height * Waifu2xBridge.SCALE, output.height)
        assertEquals(Bitmap.Config.ARGB_8888, output.config)
        Log.i(TAG, "upscale 720x540 -> 1440x1080 in ${elapsed}ms")

        val outDir = File(context.getExternalFilesDir(null), "waifu2x-poc").apply { mkdirs() }
        savePng(input, File(outDir, "poc_input.png"))
        savePng(output, File(outDir, "poc_output_2x.png"))
        Log.i(TAG, "wrote ${outDir.absolutePath}")

        Waifu2xBridge.release()
        Unit
    }

    @Test
    fun allDenoiseModelsLoadAndRun() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = makeTestPattern(128, 96)

        for (model in Waifu2xBridge.DenoiseModel.entries) {
            assertTrue("init failed for $model", Waifu2xBridge.ensureInitialized(context, model))
            val output = Waifu2xBridge.process(input)
            assertNotNull("process failed for $model", output)
            assertEquals(256, output!!.width)
            Log.i(TAG, "$model ok")
        }

        Waifu2xBridge.release()
        Unit
    }

    private fun makeTestPattern(w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Grid lines
        paint.color = Color.LTGRAY
        paint.strokeWidth = 1f
        val step = w / 12f
        var x = 0f
        while (x <= w) { canvas.drawLine(x, 0f, x, h.toFloat(), paint); x += step }
        var y = 0f
        while (y <= h) { canvas.drawLine(0f, y, w.toFloat(), y, paint); y += step }

        // Shapes with hard edges
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(220, 40, 40)
        canvas.drawCircle(w * 0.3f, h * 0.4f, h * 0.2f, paint)
        paint.color = Color.rgb(40, 40, 200)
        canvas.drawRect(w * 0.55f, h * 0.25f, w * 0.85f, h * 0.6f, paint)

        // Text (line-art like content)
        paint.color = Color.BLACK
        paint.textSize = h * 0.12f
        canvas.drawText("waifu2x PoC テスト", w * 0.08f, h * 0.85f, paint)
        return bitmap
    }

    private fun savePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val TAG = "Waifu2xPoC"
    }
}
