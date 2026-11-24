package com.oroplex.floorplan.ar

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the ARCore camera image as a full-screen quad from an OES external texture (GLES 2.0).
 * All methods run on the GL thread. If shader compilation fails the camera feed is not shown, but the
 * texture still exists so ARCore tracking (and measuring) keeps working.
 */
internal class BackgroundRenderer {
    /** GL name of the OES texture ARCore writes the camera image into; 0 until created. */
    var textureId: Int = 0
        private set

    private var program = 0
    private var positionAttrib = -1
    private var texCoordAttrib = -1
    private var textureUniform = -1

    private val quadCoords: FloatBuffer = directFloats(QUAD_NDC.size).apply { put(QUAD_NDC).position(0) }
    private val quadTexCoords: FloatBuffer = directFloats(QUAD_NDC.size).apply { put(IDENTITY_UV).position(0) }
    private val texCoordScratch = FloatArray(QUAD_NDC.size)

    /**
     * Creates the texture and shader program. Called from onSurfaceCreated, i.e. again after the EGL
     * context was lost, because all previous GL names are then invalid.
     */
    fun createOnGlThread() {
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        textureId = names[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program != 0) {
            positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
            texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
            textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")
        }
        // quadTexCoords is a Java buffer, not a GL object: it survives a context loss and keeps the last
        // computed (still correct) mapping. The renderer also forces a recompute on the next frame.
    }

    /**
     * Recomputes how the camera texture maps onto the screen quad. Call whenever
     * [Frame.hasDisplayGeometryChanged] (rotation, viewport size, first frame) and after the GL context or
     * surface was recreated.
     */
    fun updateDisplayGeometry(frame: Frame) {
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            QUAD_NDC,
            Coordinates2d.TEXTURE_NORMALIZED,
            texCoordScratch,
        )
        quadTexCoords.put(texCoordScratch).position(0)
    }

    /** Draws the camera image behind everything (depth test and depth writes disabled). */
    fun draw() {
        if (program == 0 || textureId == 0 || positionAttrib < 0 || texCoordAttrib < 0) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniform, 0)

        GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glEnableVertexAttribArray(texCoordAttrib)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glUseProgram(0)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private companion object {
        const val TAG = "BackgroundRenderer"

        /** Full-screen quad as a triangle strip, in normalized device coordinates (x, y pairs). */
        val QUAD_NDC = floatArrayOf(-1f, -1f, +1f, -1f, -1f, +1f, +1f, +1f)

        /** Unrotated texture coordinates for [QUAD_NDC] (texture v runs top-down). */
        val IDENTITY_UV = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

        const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """

        fun directFloats(count: Int): FloatBuffer =
            ByteBuffer.allocateDirect(count * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()

        /** Compiles and links a program; returns 0 (and logs) on any failure. */
        fun linkProgram(vertexSource: String, fragmentSource: String): Int {
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            if (vertex == 0 || fragment == 0) {
                if (vertex != 0) GLES20.glDeleteShader(vertex)
                if (fragment != 0) GLES20.glDeleteShader(fragment)
                return 0
            }
            val program = GLES20.glCreateProgram()
            if (program == 0) {
                Log.e(TAG, "glCreateProgram failed: 0x${Integer.toHexString(GLES20.glGetError())}")
                return 0
            }
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            // Shaders can be flagged for deletion once attached; they live as long as the program.
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Camera background program failed to link: ${GLES20.glGetProgramInfoLog(program)}")
                GLES20.glDeleteProgram(program)
                return 0
            }
            return program
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            if (shader == 0) return 0
            // The `#extension` directive must be the first non-whitespace token: strip the raw-string indent.
            GLES20.glShaderSource(shader, source.trimIndent())
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Camera background shader failed to compile: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }
    }
}
