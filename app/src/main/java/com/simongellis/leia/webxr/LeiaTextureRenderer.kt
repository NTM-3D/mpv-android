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
    private var texelSizeYLocation = -1
    private var activeYMinLocation = -1
    private var activeYMaxLocation = -1
    private var videoFractionLocation = -1
    private var subtitleTexLocation = -1
    private var subtitleEnabledLocation = -1
    private var subtitleDepthLocation = -1

    private var subtitleTextureId = -1
    @Volatile private var subtitleBitmap: Bitmap? = null
    @Volatile private var subtitleBitmapDirty = false
    @Volatile private var subtitleEnabled = false
    @Volatile private var subtitleDepth = 0f

    // Half-texel size in active-content coordinate terms (eps) for seam-safe TAB split.
    private var inputTexelSizeY = 0.5f / 1600f

    // Cached heights for recomputing TAB sampling epsilon.
    private var lastBufferHeight = 1600
    private var lastContentHeight = 1600
    private var videoFraction = 1.0f

    /**
     * Set the heights so TAB split epsilon tracks rendered content scale.
     *
     * bufferHeight  = rows in the SurfaceTexture buffer (set via setDefaultBufferSize in initialize())
     * contentHeight = rows mpv actually renders (set via android-surface-size in surfaceChanged)
     */
    fun setHeights(bufferHeight: Int, contentHeight: Int) {
        lastBufferHeight = bufferHeight
        lastContentHeight = contentHeight
        recomputeTabSampling()
    }

    /**
     * Inform the renderer of codec-padded vs display dimensions for TAB split correction.
     */
    fun setVideoCodecDimensions(codedH: Int, displayH: Int) {
        videoFraction = if (codedH > 0 && displayH > 0) displayH.toFloat() / codedH.toFloat() else 1.0f
    }

    private fun recomputeTabSampling() {
        inputTexelSizeY = 0.5f / maxOf(1f, lastContentHeight.toFloat())
    }

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
        texelSizeYLocation = glGetUniformLocation(program, "u_TexelSizeY")
        activeYMinLocation = glGetUniformLocation(program, "u_ActiveYMin")
        activeYMaxLocation = glGetUniformLocation(program, "u_ActiveYMax")
        videoFractionLocation = glGetUniformLocation(program, "u_VideoFraction")
        subtitleTexLocation = glGetUniformLocation(program, "u_SubtitleTexture")
        subtitleEnabledLocation = glGetUniformLocation(program, "u_SubtitleEnabled")
        subtitleDepthLocation = glGetUniformLocation(program, "u_SubtitleDepth")

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
        val mv = holder.transform
        val activeY = computeActiveYRange(holder.texTransform)

        glActiveTexture(GL_TEXTURE0)
        logError("glActiveTexture")
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId)
        logError("glBindTexture")

        // Set texture parameters to clamp to border and specify black border color
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)


        glUniform1i(texLocation, 0)
        logError("bind tex location")
        glUniformMatrix4fv(mvLocation, 1, false, mv, 0)
        logError("bind mv location")
        glUniform1i(modeLocation, mode)
        logError("bind mode location")
        glUniform1i(swapImagesLocation, (if (swapImages) 1 else 0))
        logError("bind swapImages location")
        glUniform1f(texelSizeYLocation, inputTexelSizeY)
        logError("bind texelSizeY location")
        glUniform1f(activeYMinLocation, activeY.first)
        glUniform1f(activeYMaxLocation, activeY.second)
        glUniform1f(videoFractionLocation, videoFraction)
        glUniform1i(subtitleEnabledLocation, if (subtitleEnabled) 1 else 0)
        glUniform1f(subtitleDepthLocation, subtitleDepth)

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

    private fun mapTransformY(transform: FloatArray, x: Float, y: Float): Float {
        return transform[1] * x + transform[5] * y + transform[13]
    }

    private fun computeActiveYRange(transform: FloatArray): Pair<Float, Float> {
        val y00 = mapTransformY(transform, 0f, 0f)
        val y01 = mapTransformY(transform, 0f, 1f)
        val y10 = mapTransformY(transform, 1f, 0f)
        val y11 = mapTransformY(transform, 1f, 1f)
        val minY = minOf(y00, y01, y10, y11)
        val maxY = maxOf(y00, y01, y10, y11)
        return Pair(minY, maxY)
    }

    private class TextureHolder(private val texture: SurfaceTexture, val transform: FloatArray) {
        var textureId = -1
        val texTransform = FloatArray(16)
        private var stale = true

        init {
            Matrix.setIdentityM(texTransform, 0)
            texture.getTransformMatrix(texTransform)
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
                texture.getTransformMatrix(texTransform)
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
            // Half-texel size (epsilon) to avoid sampling exactly at the eye boundary.
            uniform float u_TexelSizeY;
            uniform float u_ActiveYMin;
            uniform float u_ActiveYMax;
            uniform float u_VideoFraction;
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
                    // Half-TAB to SBS using transform-derived active Y window and
                    // codec/display correction for padded heights.
                    float span = max(u_ActiveYMax - u_ActiveYMin, 0.00001);
                    float eyeSpan = max(span * 0.5 * u_VideoFraction, 0.00001);
                    float split = u_ActiveYMin + eyeSpan;
                    float eps = min(u_TexelSizeY * span, eyeSpan * 0.25);
                    if (u_SwapImages == 0) {
                        if (v_TexCoord.x < 0.5) {
                            coord.x = v_TexCoord.x * 2.0;
                            coord.y = u_ActiveYMin + v_TexCoord.y * max(eyeSpan - eps, 0.00001);
                        } else {
                            coord.x = (v_TexCoord.x - 0.5) * 2.0;
                            coord.y = (split + eps) + v_TexCoord.y * max(eyeSpan - 2.0 * eps, 0.00001);
                        }
                    } else {
                        if (v_TexCoord.x < 0.5) {
                            coord.x = v_TexCoord.x * 2.0;
                            coord.y = (split + eps) + v_TexCoord.y * max(eyeSpan - 2.0 * eps, 0.00001);
                        } else {
                            coord.x = (v_TexCoord.x - 0.5) * 2.0;
                            coord.y = u_ActiveYMin + v_TexCoord.y * max(eyeSpan - eps, 0.00001);
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
                if (u_SubtitleEnabled == 1 && (u_Mode == 1 || u_Mode == 3)) {
                    float eyeX = fract(v_TexCoord.x * 2.0);
                    float depth = u_SubtitleDepth;
                    vec2 subCoord;
                    if (v_TexCoord.x < 0.5) {
                        subCoord = vec2(eyeX + depth, v_TexCoord.y);
                    } else {
                        subCoord = vec2(eyeX - depth, v_TexCoord.y);
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
