package com.kwaris.shootcam.gl

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the camera frame (external OES texture) rotated/cropped to an output
 * frame, then alpha-blends the overlay bitmap on top. Must be used on the GL thread.
 */
class FrameRenderer {
    private var camProgram = 0
    private var ovProgram = 0
    var cameraTexture = 0
        private set
    private var overlayTexture = 0
    private var overlayW = 0
    private var overlayH = 0
    private var hasOverlay = false

    private val quad: FloatBuffer = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val camTex: FloatBuffer = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    // Bitmaps are uploaded top row first: flip V so the overlay is upright.
    private val ovTex: FloatBuffer = floatBuffer(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

    private val texMatrix = FloatArray(16)
    private val tmp = FloatArray(16)

    fun init() {
        camProgram = program(VS, FS_OES)
        ovProgram = program(VS, FS_2D)
        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        cameraTexture = ids[0]
        overlayTexture = ids[1]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
        texParams(GLES20.GL_TEXTURE_2D)
    }

    /** Uploads a new overlay image (same size as the output frame, transparent background). */
    fun setOverlay(bitmap: Bitmap?) {
        if (bitmap == null) {
            hasOverlay = false
            return
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
        if (bitmap.width != overlayW || bitmap.height != overlayH) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            overlayW = bitmap.width
            overlayH = bitmap.height
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
        hasOverlay = true
    }

    /**
     * @param stMatrix   SurfaceTexture transform
     * @param rotation   clockwise rotation (0/90/180/270) that makes the camera image upright
     * @param srcAspect  camera buffer width / height
     * @param outAspect  output frame width / height
     */
    fun draw(
        stMatrix: FloatArray, rotation: Int, srcAspect: Float, outAspect: Float,
        vx: Int, vy: Int, vw: Int, vh: Int,
    ) {
        GLES20.glViewport(vx, vy, vw, vh)
        GLES20.glDisable(GLES20.GL_BLEND)

        // Texture-space transform: rotate around the centre, then crop to fill the output aspect.
        val rotated = rotation % 180 != 0
        val effSrc = if (rotated) 1f / srcAspect else srcAspect
        var sx = 1f
        var sy = 1f
        if (effSrc > outAspect) sx = outAspect / effSrc else sy = effSrc / outAspect
        Matrix.setIdentityM(tmp, 0)
        Matrix.translateM(tmp, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(tmp, 0, rotation.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(tmp, 0, sx, sy, 1f) // scale in output space: crops instead of stretching
        Matrix.translateM(tmp, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, tmp, 0)

        GLES20.glUseProgram(camProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(camProgram, "uTexMatrix"), 1, false, texMatrix, 0)
        drawQuad(camProgram, camTex)

        if (hasOverlay) {
            GLES20.glEnable(GLES20.GL_BLEND)
            // Bitmaps are premultiplied.
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(ovProgram)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
            Matrix.setIdentityM(tmp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ovProgram, "uTexMatrix"), 1, false, tmp, 0)
            drawQuad(ovProgram, ovTex)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    fun clear() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    fun release() {
        GLES20.glDeleteTextures(2, intArrayOf(cameraTexture, overlayTexture), 0)
        GLES20.glDeleteProgram(camProgram)
        GLES20.glDeleteProgram(ovProgram)
    }

    private fun drawQuad(prog: Int, tex: FloatBuffer) {
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aTex = GLES20.glGetAttribLocation(prog, "aTex")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    private fun texParams(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun program(vs: String, fs: String): Int {
        val v = shader(GLES20.GL_VERTEX_SHADER, vs)
        val f = shader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "GL link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "GL shader: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    companion object {
        private const val VS = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * aTex).xy;
            }
        """
        private const val FS_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """
        private const val FS_2D = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """

        private fun floatBuffer(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(v); position(0)
            }
    }
}
