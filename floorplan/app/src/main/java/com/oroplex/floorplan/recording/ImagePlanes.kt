package com.oroplex.floorplan.recording

import java.nio.ByteBuffer

/**
 * Copies camera and depth image planes into plain arrays. Android hands these out as direct buffers with a
 * row stride that may exceed the width and, for the colour planes, a pixel stride of 1 or 2 depending on the
 * phone; nothing here assumes one layout. The buffers' own positions are never moved.
 */
object ImagePlanes {
    /** Bytes of an NV21 image: the luminance plane, then V and U interleaved at half resolution. */
    fun nv21Size(width: Int, height: Int): Int = width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2)

    /**
     * Writes a YUV_420_888 image as NV21 into [out] (at least [nv21Size] bytes), which is what Android's JPEG
     * encoder takes. [y], [u] and [v] are the three planes; [row] is scratch space of at least
     * max([yRowStride], [uvRowStride]) bytes.
     */
    fun toNv21(
        y: ByteBuffer,
        u: ByteBuffer,
        v: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        out: ByteArray,
        row: ByteArray,
    ) {
        require(width > 0 && height > 0) { "empty image" }
        require(out.size >= nv21Size(width, height)) { "output too small" }
        require(uvPixelStride >= 1) { "bad pixel stride" }
        val luma = y.duplicate()
        var offset = 0
        if (yRowStride == width) {
            luma.position(0)
            luma.get(out, 0, width * height)
            offset = width * height
        } else {
            for (r in 0 until height) {
                luma.position(r * yRowStride)
                luma.get(out, offset, width)
                offset += width
            }
        }

        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        // The last row of a plane is often shorter than its stride: only what is needed is read.
        val needed = (chromaWidth - 1) * uvPixelStride + 1
        val uPlane = u.duplicate()
        val vPlane = v.duplicate()
        val uRow = ByteArray(needed)
        for (r in 0 until chromaHeight) {
            vPlane.position(r * uvRowStride)
            vPlane.get(row, 0, needed)
            uPlane.position(r * uvRowStride)
            uPlane.get(uRow, 0, needed)
            var c = 0
            var k = 0
            while (c < chromaWidth) {
                out[offset++] = row[k]
                out[offset++] = uRow[k]
                k += uvPixelStride
                c++
            }
        }
    }

    /**
     * Copies a single-plane image of [bytesPerPixel]-byte pixels (16-bit depth: 2, confidence: 1) row by row
     * into a new array of exactly width × height × bytesPerPixel bytes, in the plane's own byte order.
     */
    fun copyPlane(plane: ByteBuffer, width: Int, height: Int, rowStride: Int, bytesPerPixel: Int): ByteArray {
        require(width > 0 && height > 0 && bytesPerPixel > 0) { "empty image" }
        val rowBytes = width * bytesPerPixel
        val out = ByteArray(rowBytes * height)
        val source = plane.duplicate()
        if (rowStride == rowBytes) {
            source.position(0)
            source.get(out, 0, out.size)
        } else {
            for (r in 0 until height) {
                source.position(r * rowStride)
                source.get(out, r * rowBytes, rowBytes)
            }
        }
        return out
    }
}
