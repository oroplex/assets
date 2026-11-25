package com.oroplex.floorplan.walkthrough

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.media.ImageReader
import android.os.Looper
import android.view.View
import android.view.inspector.WindowInspector
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Saves what the screen shows as PNG files under `app/build/ui-walkthrough/` so a person (or a reviewer
 * agent) can look at every screen without a device. Every window root (the screen, plus any dialog, menu
 * or sheet on top of it) is saved as its own file: `group-NN-name.png`, `group-NN-name-popup1.png`, ...
 *
 * Each root view is recorded into a RenderNode and drawn by a real HardwareRenderer (Robolectric native
 * graphics), i.e. the same drawing path as on a device; Compose's own captureToImage waits for a window
 * draw pass that never happens on the JVM.
 */
internal object Screenshots {
    private val dir: File by lazy {
        File(System.getProperty("floorplan.walkthrough.dir") ?: "build/ui-walkthrough").apply { mkdirs() }
    }
    private val counters = HashMap<String, Int>()

    /**
     * Saves every attached window root as `<group>-NN-<name>.png` (NN counts shots within [group]);
     * returns the files written.
     */
    fun shoot(group: String, name: String): List<File> {
        shadowOf(Looper.getMainLooper()).idle()
        val n = (counters[group] ?: 0) + 1
        counters[group] = n
        val prefix = "%s-%02d-%s".format(group, n, name)
        val roots = WindowInspector.getGlobalWindowViews().filter { it.width > 0 && it.height > 0 && it.isShown }
        return roots.mapIndexedNotNull { i, root ->
            val bitmap = try {
                render(root)
            } catch (e: Throwable) {
                System.err.println("walkthrough: could not capture $prefix root $i: $e")
                return@mapIndexedNotNull null
            }
            File(dir, if (i == 0) "$prefix.png" else "$prefix-popup$i.png").also { file ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    private fun render(view: View): Bitmap {
        val width = view.width
        val height = view.height
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1).use { reader ->
            val renderer = HardwareRenderer()
            try {
                renderer.setSurface(reader.surface)
                val node = RenderNode("walkthrough-root")
                node.setPosition(0, 0, width, height)
                val canvas = node.beginRecording()
                canvas.drawColor(android.graphics.Color.WHITE)
                view.draw(canvas)
                node.endRecording()
                renderer.setContentRoot(node)
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                reader.acquireNextImage().use { image ->
                    val plane = image.planes[0]
                    val rowPixels = plane.rowStride / plane.pixelStride
                    val padded = Bitmap.createBitmap(rowPixels, height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    return if (rowPixels == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
                }
            } finally {
                renderer.destroy()
            }
        }
    }
}
