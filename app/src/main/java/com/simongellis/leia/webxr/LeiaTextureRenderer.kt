package com.simongellis.leia.webxr

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20.*
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
    private var eyeSplitYLocation = -1

    // Half-texel size in buffer-coordinate terms (eps) and the y-coordinate of the HTAB
    // eye split in the raw texture, both derived from the actual buffer and content heights.
    private var inputTexelSizeY = 0.5f / 1600f
    private var eyeSplitY = 0.5f

    // Cached heights for recomputing eyeSplitY when videoFraction changes.
    private var lastBufferHeight = 1600
    private var lastContentHeight = 1600
    // Fraction of the coded video frame that is valid display content: displayH / codedH.
    // For a 1080p H.264 video, codedH=1088 (16-row aligned), displayH=1080, so fraction=0.9926.
    // eyeSplitY = fraction * contentHeight / (2 * bufferHeight)
    private var videoFraction = 1.0f

    /**
     * Set the heights so the shader can find the true HTAB eye split.
     *
     * bufferHeight  = rows in the SurfaceTexture buffer (set via setDefaultBufferSize in initialize())
     * contentHeight = rows mpv actually renders (set via android-surface-size in surfaceChanged)
     */
    fun setHeights(bufferHeight: Int, contentHeight: Int) {
        lastBufferHeight = bufferHeight
        lastContentHeight = contentHeight
        recomputeEyeSplit()
    }

    /**
     * Inform the renderer of the video's codec-padded vs display dimensions.
     * H.264 hardware decoders align frame heights to multiples of 16, so a 1080p video
     * has codedH=1088 but displayH=1080. mpv renders all 1088 rows scaled to the output,
     * so the true eye split (at display row displayH/2) is at OES y = displayH/(2*codedH),
     * not 0.5. Pass codedH=0 to reset to no-padding mode (videoFraction=1.0).
     */
    fun setVideoCodecDimensions(codedH: Int, displayH: Int) {
        videoFraction = if (codedH > 0 && displayH > 0) displayH.toFloat() / codedH.toFloat() else 1.0f
        Log.i(TAG, "setVideoCodecDimensions: codedH=$codedH displayH=$displayH videoFraction=$videoFraction")
        recomputeEyeSplit()
    }

    private fun recomputeEyeSplit() {
        inputTexelSizeY = 0.5f / lastBufferHeight
        eyeSplitY = videoFraction * lastContentHeight.toFloat() / (2f * lastBufferHeight.toFloat())
        Log.i(TAG, "recomputeEyeSplit: bufferH=$lastBufferHeight contentH=$lastContentHeight videoFraction=$videoFraction eyeSplitY=$eyeSplitY")
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
        eyeSplitYLocation = glGetUniformLocation(program, "u_EyeSplitY")

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
        holder.tryUpdateTexImage()
        val textureId = holder.textureId
        val mv = holder.transform

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
        glUniform1f(eyeSplitYLocation, eyeSplitY)
        logError("bind eyeSplitY location")

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
            uniform int u_Mode;
            uniform int u_SwapImages;
            // Half-texel size (epsilon) to avoid sampling exactly at the eye boundary.
            uniform float u_TexelSizeY;
            // Actual raw-texture y-coordinate of the HTAB eye split, derived from
            // SurfaceTexture.getTransformMatrix(). Defaults to 0.5.
            uniform float u_EyeSplitY;
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
                    // Half-TAB to SBS: top half of input = left eye, bottom = right eye.
                    // Use the actual eye split y from the SurfaceTexture transform matrix
                    // so the mapping is correct even when content height < buffer height.
                    // Both eyes use the same scale factor (~eyeSplit) so they stay aligned.
                    float eyeSplit = u_EyeSplitY;
                    float eps = u_TexelSizeY;
                    if (u_SwapImages == 0) {
                        if (v_TexCoord.x < 0.5) {
                            // left eye: map display [0,1] -> raw y [0, eyeSplit-eps]
                            coord.x = v_TexCoord.x * 2.0;
                            coord.y = v_TexCoord.y * (eyeSplit - eps);
                        } else {
                            // right eye: map display [0,1] -> raw y [eyeSplit+eps, 2*eyeSplit-eps]
                            coord.x = (v_TexCoord.x - 0.5) * 2.0;
                            coord.y = (eyeSplit + eps) + v_TexCoord.y * (eyeSplit - 2.0 * eps);
                        }
                    } else {
                        if (v_TexCoord.x < 0.5) {
                            coord.x = v_TexCoord.x * 2.0;
                            coord.y = (eyeSplit + eps) + v_TexCoord.y * (eyeSplit - 2.0 * eps);
                        } else {
                            coord.x = (v_TexCoord.x - 0.5) * 2.0;
                            coord.y = v_TexCoord.y * (eyeSplit - eps);
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
