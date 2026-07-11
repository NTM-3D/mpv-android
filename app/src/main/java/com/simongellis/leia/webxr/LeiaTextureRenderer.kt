package com.simongellis.leia.webxr

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20.*
import android.opengl.Matrix
import android.opengl.GLUtils
import android.util.Log
import android.util.Size
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class LeiaTextureRenderer {
    private val TAG = "LeiaTextureRenderer"

    private val textureHolders = mutableListOf<TextureHolder>()
    private var size = Size(640, 480)
    private var textureSize = Size(640, 480)
    // Mode mapping:
    // 0 = 2D passthrough, 1 = half-SBS, 2 = half-TAB, 3 = full-SBS
    private var mode = 0
    private var swapImages = false

    private var program = -1
    private var posLocation = -1
    private var texCoordLocation = -1
    private var mvLocation = -1
    private var texLocation = -1
    private var modeLocation = -1
    private var swapImagesLocation = -1
    private var subtitleTexLocation = -1
    private var subtitleEnabledLocation = -1
    private var subtitleDepthLocation = -1
    private var subtitlePositionLocation = -1
    private var subtitleScaleLocation = -1

    private var subtitleTextureId = -1
    @Volatile private var subtitleBitmap: Bitmap? = null
    @Volatile private var subtitleBitmapDirty = false
    @Volatile private var subtitleEnabled = false
    @Volatile private var subtitleDepth = 0f
    @Volatile private var subtitlePosition = 0f
    @Volatile private var subtitleScale = 1f

    // The known aspect ratio of a single eye's content (mpv is configured with
    // keepaspect=no for stereo modes, so this is the only place aspect ratio is
    // enforced). Updated dynamically when the video changes or the stereo mode changes.
    private var contentAspect = 16f / 9f

    private val letterboxMatrix = FloatArray(16)
    private val drawMv = FloatArray(16)

    fun setMode(value: Int) {
        mode = value
    }

    fun getMode(): Int {
        return mode
    }

    fun setSwapImages(value: Boolean) {
        swapImages = value
    }

    fun getSwapImages(): Boolean {
        return swapImages
    }

    fun setContentAspect(aspect: Float) {
        if (aspect > 0f) contentAspect = aspect
    }

    fun addTexture(texture: SurfaceTexture, transform: FloatArray) {
        Log.i(TAG, "addingTexture")
        textureHolders.add(TextureHolder(texture, transform))
    }

    fun setSubtitleBitmap(bitmap: Bitmap?) {
        subtitleBitmap = bitmap
        subtitleBitmapDirty = true
    }

    fun setSubtitleEnabled(enabled: Boolean) {
        subtitleEnabled = enabled
    }

    fun setSubtitleDepth(depth: Float) {
        subtitleDepth = depth
    }

    fun setSubtitlePosition(position: Float) {
        subtitlePosition = position
    }

    fun setSubtitleScale(scale: Float) {
        subtitleScale = scale
    }

    fun onSurfaceCreated() {
        Log.i(TAG, "onSurfaceCreated")
        val textureIds = IntArray(textureHolders.size)
        glGenTextures(textureIds.size, textureIds, 0)
        textureHolders.forEachIndexed { index, textureHolder ->
            val textureId = textureIds[index]
            textureHolder.updateTextureId(textureId)
        }

        program = glCreateProgram()
        val vertexShader = makeShader(GL_VERTEX_SHADER, VERTEX_SHADER)
        glAttachShader(program, vertexShader)
        val fragmentShader = makeShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        glAttachShader(program, fragmentShader)
        glLinkProgram(program)

        posLocation = glGetAttribLocation(program, "a_Pos")
        texCoordLocation = glGetAttribLocation(program, "a_TexCoord")
        mvLocation = glGetUniformLocation(program, "u_MV")
        texLocation = glGetUniformLocation(program, "u_Texture")
        modeLocation = glGetUniformLocation(program, "u_Mode")
        swapImagesLocation = glGetUniformLocation(program, "u_SwapImages")
        subtitleTexLocation = glGetUniformLocation(program, "u_SubtitleTexture")
        subtitleEnabledLocation = glGetUniformLocation(program, "u_SubtitleEnabled")
        subtitleDepthLocation = glGetUniformLocation(program, "u_SubtitleDepth")
        subtitlePositionLocation = glGetUniformLocation(program, "u_SubtitlePosition")
        subtitleScaleLocation = glGetUniformLocation(program, "u_SubtitleScale")

        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        subtitleTextureId = ids[0]
        glBindTexture(GL_TEXTURE_2D, subtitleTextureId)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindTexture(GL_TEXTURE_2D, 0)

        Log.i(TAG, "swapImagesLocation: $swapImagesLocation")
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        Log.i(TAG, "onSurfaceChanged")
        size = Size(width, height)
    }

    fun onDrawFrame() {
        //Log.i(TAG, "onDrawFrame")
        glViewport(0, 0, size.width, size.height)
        logError("glViewport")
        // Clear to black first: the letterbox bars added below to preserve the
        // true 16:9 content aspect on this 16:10 screen are not otherwise painted.
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        glUseProgram(program)
        logError("glUseProgram")
        for (holder in textureHolders) {
            renderTexture(holder)
        }
    }

    private fun makeShader(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        return shader
    }

    /**
     * Build a scale matrix that shrinks the full-screen quad so its displayed
     * aspect ratio matches contentAspect, leaving black bars (via glClear above)
     * on whichever axis the screen's own aspect ratio doesn't match.
     */
    private fun computeLetterboxMatrix(): FloatArray {
        val screenAspect = size.width.toFloat() / size.height.toFloat()
        var scaleX = 1f
        var scaleY = 1f
        if (screenAspect > contentAspect) {
            // Screen is relatively wider than the content: pillarbox (shrink X).
            scaleX = contentAspect / screenAspect
        } else if (screenAspect < contentAspect) {
            // Screen is relatively taller than the content: letterbox (shrink Y).
            scaleY = screenAspect / contentAspect
        }
        Matrix.setIdentityM(letterboxMatrix, 0)
        Matrix.scaleM(letterboxMatrix, 0, scaleX, scaleY, 1f)
        return letterboxMatrix
    }

    private fun renderTexture(holder: TextureHolder) {
        if (subtitleBitmapDirty) {
            glBindTexture(GL_TEXTURE_2D, subtitleTextureId)
            val bmp = subtitleBitmap
            if (bmp != null && !bmp.isRecycled) {
                GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
                glGenerateMipmap(GL_TEXTURE_2D)
            } else {
                val clear = ByteBuffer.allocateDirect(4)
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, clear)
                glGenerateMipmap(GL_TEXTURE_2D)
            }
            glBindTexture(GL_TEXTURE_2D, 0)
            subtitleBitmapDirty = false
        }

        holder.tryUpdateTexImage()
        val textureId = holder.textureId
        Matrix.multiplyMM(drawMv, 0, holder.transform, 0, computeLetterboxMatrix(), 0)

        glActiveTexture(GL_TEXTURE0)
        logError("glActiveTexture")
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId)
        logError("glBindTexture")

        // Set texture parameters to clamp to border and specify black border color
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        // GL_TEXTURE_EXTERNAL_OES textures cannot mipmap; the GLES default MIN_FILTER
        // (NEAREST_MIPMAP_LINEAR) is invalid for them and its behavior is driver-defined
        // if left unset. Explicitly use GL_LINEAR for both filters.
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)


        glUniform1i(texLocation, 0)
        logError("bind tex location")
        glUniformMatrix4fv(mvLocation, 1, false, drawMv, 0)
        logError("bind mv location")
        glUniform1i(modeLocation, mode)
        logError("bind mode location")
        glUniform1i(swapImagesLocation, (if (swapImages) 1 else 0))
        logError("bind swapImages location")
        glUniform1i(subtitleEnabledLocation, if (subtitleEnabled) 1 else 0)
        glUniform1f(subtitleDepthLocation, subtitleDepth)
        glUniform1f(subtitlePositionLocation, subtitlePosition)
        glUniform1f(subtitleScaleLocation, subtitleScale)

        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, subtitleTextureId)
        glUniform1i(subtitleTexLocation, 1)
        glActiveTexture(GL_TEXTURE0)

        glVertexAttribPointer(
                posLocation,
                VERTEX_SIZE,
                GL_FLOAT,
                false,
                VERTEX_STRIDE,
                SQUARE_POS_VERTICES
        )
        logError("bind pos location")
        glVertexAttribPointer(
                texCoordLocation,
                VERTEX_SIZE,
                GL_FLOAT,
                false,
                VERTEX_STRIDE,
                SQUARE_TEX_VERTICES
        )
        logError("bind tex coord location")
        glEnableVertexAttribArray(posLocation)
        logError("enable pos vertex attrib array")
        glEnableVertexAttribArray(texCoordLocation)
        logError("enable tex coord vertex attrib array")
        glDrawElements(GL_TRIANGLES, SQUARE_INDICES_SIZE, GL_UNSIGNED_SHORT, SQUARE_INDICES)
        logError("draw shit")
    }

    private fun logError(message: String) {
        return;
        var error = glGetError()
        while (error != 0) {
            Log.i(TAG, "${error.toString(16)}: $message")
            if (error == GL_INVALID_FRAMEBUFFER_OPERATION) {
                val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
                Log.i(TAG, "\tstatus: ${status.toString(16)}")
            }
            error = glGetError()
        }
    }

    private class TextureHolder(private val texture: SurfaceTexture, val transform: FloatArray) {
        var textureId = -1
        private var stale = true

        init {
            texture.setOnFrameAvailableListener { stale = true }
        }

        fun updateTextureId(textureId: Int) {
            this.textureId = textureId
            texture.attachToGLContext(textureId)
        }

        fun tryUpdateTexImage() {
            if (stale) {
                stale = false
                texture.updateTexImage()
            }
        }
    }

    companion object {
        const val VERTEX_SHADER = """
            attribute vec4 a_Pos;
            attribute vec2 a_TexCoord;
            uniform mat4 u_MV;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = u_MV * a_Pos;
                // leia renders upside down, so flip Y values
                v_TexCoord = vec2(a_TexCoord.x, 1.0f - a_TexCoord.y);
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            uniform sampler2D u_SubtitleTexture;
            uniform int u_Mode;
            uniform int u_SwapImages;
            uniform int u_SubtitleEnabled;
            uniform float u_SubtitleDepth;
            uniform float u_SubtitlePosition;
            uniform float u_SubtitleScale;
            void main() {
                vec2 coord = v_TexCoord;

                if (u_Mode == 0) {
                    // 2D passthrough: fill both eye halves with the full frame so
                    // the Leia interlace hardware sees identical content in both eyes.
                    coord.x = fract(v_TexCoord.x * 2.0);
                } else if (u_Mode == 1) {
                    // Half-SBS: left half of input = left eye, right half = right eye.
                    // No x-remapping needed; mpv lays the content out that way already.
                    // Swap just mirrors the two halves.
                    if (u_SwapImages == 1) {
                        coord.x = mod(coord.x + 0.5, 1.0);
                    }
                } else if (u_Mode == 2) {
                    // Half-TAB: top half of texture = left eye, bottom half = right eye.
                    // mpv is configured with keepaspect=no for stereo modes, so the
                    // composite frame fills the buffer edge-to-edge and the eye
                    // boundary always lands exactly at the halfway point.
                    if (u_SwapImages == 0) {
                        if (v_TexCoord.x < 0.5) {
                            coord.x = v_TexCoord.x * 2.0;
                            coord.y = v_TexCoord.y * 0.5;
                        } else {
                            coord.x = (v_TexCoord.x - 0.5) * 2.0;
                            coord.y = 0.5 + v_TexCoord.y * 0.5;
                        }
                    } else {
                        if (v_TexCoord.x < 0.5) {
                            coord.x = v_TexCoord.x * 2.0;
                            coord.y = 0.5 + v_TexCoord.y * 0.5;
                        } else {
                            coord.x = (v_TexCoord.x - 0.5) * 2.0;
                            coord.y = v_TexCoord.y * 0.5;
                        }
                    }
                } else if (u_Mode == 3) {
                    // Full-SBS: explicit per-eye x remap.
                    // Each eye samples its own half-frame using eye-local x [0..1].
                    float eyeX = fract(v_TexCoord.x * 2.0);
                    if (u_SwapImages == 0) {
                        if (v_TexCoord.x < 0.5) {
                            coord.x = eyeX * 0.5;
                        } else {
                            coord.x = 0.5 + eyeX * 0.5;
                        }
                    } else {
                        if (v_TexCoord.x < 0.5) {
                            coord.x = 0.5 + eyeX * 0.5;
                        } else {
                            coord.x = eyeX * 0.5;
                        }
                    }
                }

                gl_FragColor = texture2D(u_Texture, coord);
                // Subtitles work for all SBS-output modes (1=HALF_SBS, 2=HALF_TAB, 3=FULL_SBS)
                // Mode 2 outputs SBS after TAB transformation
                if (u_SubtitleEnabled == 1 && (u_Mode == 1 || u_Mode == 2 || u_Mode == 3)) {
                    float eyeX = fract(v_TexCoord.x * 2.0);
                    float depth = u_SubtitleDepth;
                    // Position: shift independently of scale (positive = move up in screen space = subtract in UV Y)
                    float posY = v_TexCoord.y - u_SubtitlePosition;
                    // Scale: zoom around the subtitle anchor (bottom-center = 0.85 in UV Y),
                    // keeping position independent. Divide distance from anchor by scale so
                    // text grows/shrinks without distorting aspect ratio or moving the baseline.
                    float anchor = 0.85;
                    float scaleY = (posY - anchor) / u_SubtitleScale + anchor;
                    vec2 subCoord;
                    if (v_TexCoord.x < 0.5) {
                        subCoord = vec2(eyeX + depth, scaleY);
                    } else {
                        subCoord = vec2(eyeX - depth, scaleY);
                    }
                    vec4 sub = texture2D(u_SubtitleTexture, subCoord);
                    gl_FragColor = vec4(
                        mix(gl_FragColor.rgb, sub.rgb, sub.a),
                        max(gl_FragColor.a, sub.a)
                    );
                }
                if (gl_FragColor.a < 0.1) {
                    discard;
                }
            }
        """

        const val VERTEX_SIZE = 2
        const val VERTEX_STRIDE = 0

        val SQUARE_POS_VERTICES = floatBufferOf(
                -1f, +1f,
                -1f, -1f,
                +1f, -1f,
                +1f, +1f
        )
        val SQUARE_TEX_VERTICES = floatBufferOf(
                0f, 0f,
                0f, 1f,
                1f, 1f,
                1f, 0f
        )
        val SQUARE_INDICES = shortBufferOf(0, 1, 2, 0, 2, 3)
        val SQUARE_INDICES_SIZE = 6

        private fun floatBufferOf(vararg elements: Float): FloatBuffer {
            val buffer = ByteBuffer.allocateDirect(elements.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
            buffer.put(elements)
            buffer.position(0)
            return buffer
        }

        private fun shortBufferOf(vararg elements: Short): ShortBuffer {
            val buffer = ByteBuffer.allocateDirect(elements.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asShortBuffer()
            buffer.put(elements)
            buffer.position(0)
            return buffer
        }

    }
}