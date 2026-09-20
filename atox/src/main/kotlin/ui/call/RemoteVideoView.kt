// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.call

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import ltd.evilcorp.domain.feature.IncomingVideoFrame

class RemoteVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {
    private val renderer = YuvRenderer()

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setFrame(frame: IncomingVideoFrame?) {
        if (frame == null || frame.width <= 0 || frame.height <= 0) {
            clear()
            return
        }
        renderer.setFrame(frame)
        requestRender()
    }

    fun clear() {
        renderer.clear()
        requestRender()
    }

    private class YuvRenderer : Renderer {
        private val lock = Any()
        private val vertexBuffer = floatBuffer(
            floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f,
            ),
        )
        private val textureBuffer = floatBuffer(DEFAULT_TEXTURE_COORDS)

        private var program = 0
        private var positionHandle = 0
        private var texCoordHandle = 0
        private var ySamplerHandle = 0
        private var uSamplerHandle = 0
        private var vSamplerHandle = 0
        private val textures = IntArray(3)
        private var surfaceWidth = 0
        private var surfaceHeight = 0

        private var pendingWidth = 0
        private var pendingHeight = 0
        private var pendingY = ByteArray(0)
        private var pendingU = ByteArray(0)
        private var pendingV = ByteArray(0)
        private var pendingDirty = false
        private var pendingVisible = false

        private var frameWidth = 0
        private var frameHeight = 0
        private var yBuffer: ByteBuffer? = null
        private var uBuffer: ByteBuffer? = null
        private var vBuffer: ByteBuffer? = null
        private var frameVisible = false

        fun setFrame(frame: IncomingVideoFrame) {
            synchronized(lock) {
                ensurePendingCapacity(frame.width, frame.height)
                System.arraycopy(frame.y, 0, pendingY, 0, minOf(frame.y.size, pendingY.size))
                System.arraycopy(frame.u, 0, pendingU, 0, minOf(frame.u.size, pendingU.size))
                System.arraycopy(frame.v, 0, pendingV, 0, minOf(frame.v.size, pendingV.size))
                pendingWidth = frame.width
                pendingHeight = frame.height
                pendingVisible = true
                pendingDirty = true
            }
        }

        fun clear() {
            synchronized(lock) {
                pendingVisible = false
                pendingDirty = true
            }
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            ySamplerHandle = GLES20.glGetUniformLocation(program, "yTexture")
            uSamplerHandle = GLES20.glGetUniformLocation(program, "uTexture")
            vSamplerHandle = GLES20.glGetUniformLocation(program, "vTexture")
            GLES20.glGenTextures(3, textures, 0)
            for (texture in textures) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            GLES20.glViewport(0, 0, width, height)
            updateTextureCoordinates()
        }

        override fun onDrawFrame(gl: GL10?) {
            copyPendingFrameIfNeeded()
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (!frameVisible || frameWidth <= 0 || frameHeight <= 0 || program == 0) {
                return
            }

            GLES20.glUseProgram(program)
            uploadTexture(0, textures[0], frameWidth, frameHeight, yBuffer, ySamplerHandle)
            uploadTexture(1, textures[1], (frameWidth + 1) / 2, (frameHeight + 1) / 2, uBuffer, uSamplerHandle)
            uploadTexture(2, textures[2], (frameWidth + 1) / 2, (frameHeight + 1) / 2, vBuffer, vSamplerHandle)

            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            textureBuffer.position(0)
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
        }

        private fun copyPendingFrameIfNeeded() {
            var width = 0
            var height = 0
            var visible = false
            var y: ByteArray? = null
            var u: ByteArray? = null
            var v: ByteArray? = null
            synchronized(lock) {
                if (!pendingDirty) {
                    return
                }
                pendingDirty = false
                visible = pendingVisible
                if (visible) {
                    width = pendingWidth
                    height = pendingHeight
                    y = pendingY.copyOf(width * height)
                    u = pendingU.copyOf(((width + 1) / 2) * ((height + 1) / 2))
                    v = pendingV.copyOf(((width + 1) / 2) * ((height + 1) / 2))
                }
            }

            frameVisible = visible
            if (!visible || y == null || u == null || v == null) {
                return
            }

            ensureFrameBuffers(width, height)
            yBuffer?.put(y)?.position(0)
            uBuffer?.put(u)?.position(0)
            vBuffer?.put(v)?.position(0)
            frameWidth = width
            frameHeight = height
            updateTextureCoordinates()
        }

        private fun uploadTexture(
            unit: Int,
            texture: Int,
            width: Int,
            height: Int,
            buffer: ByteBuffer?,
            samplerHandle: Int,
        ) {
            val source = buffer ?: return
            source.position(0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(samplerHandle, unit)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_LUMINANCE,
                width,
                height,
                0,
                GLES20.GL_LUMINANCE,
                GLES20.GL_UNSIGNED_BYTE,
                source,
            )
        }

        private fun ensurePendingCapacity(width: Int, height: Int) {
            val ySize = width * height
            val uvSize = ((width + 1) / 2) * ((height + 1) / 2)
            if (pendingY.size != ySize) {
                pendingY = ByteArray(ySize)
            }
            if (pendingU.size != uvSize) {
                pendingU = ByteArray(uvSize)
            }
            if (pendingV.size != uvSize) {
                pendingV = ByteArray(uvSize)
            }
        }

        private fun ensureFrameBuffers(width: Int, height: Int) {
            val ySize = width * height
            val uvSize = ((width + 1) / 2) * ((height + 1) / 2)
            if (frameWidth != width || frameHeight != height || yBuffer?.capacity() != ySize) {
                yBuffer = directByteBuffer(ySize)
                uBuffer = directByteBuffer(uvSize)
                vBuffer = directByteBuffer(uvSize)
            }
        }

        private fun updateTextureCoordinates() {
            if (surfaceWidth <= 0 || surfaceHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) {
                textureBuffer.position(0)
                textureBuffer.put(DEFAULT_TEXTURE_COORDS)
                textureBuffer.position(0)
                return
            }

            val viewRatio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
            val frameRatio = frameWidth.toFloat() / frameHeight.toFloat()
            var left = 0f
            var right = 1f
            var top = 0f
            var bottom = 1f
            if (frameRatio > viewRatio) {
                val visibleWidth = viewRatio / frameRatio
                left = (1f - visibleWidth) / 2f
                right = 1f - left
            } else {
                val visibleHeight = frameRatio / viewRatio
                top = (1f - visibleHeight) / 2f
                bottom = 1f - top
            }
            textureBuffer.position(0)
            textureBuffer.put(
                floatArrayOf(
                    left, bottom,
                    right, bottom,
                    left, top,
                    right, top,
                ),
            )
            textureBuffer.position(0)
        }

        private fun createProgram(vertexShader: String, fragmentShader: String): Int {
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader)
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
            val result = GLES20.glCreateProgram()
            GLES20.glAttachShader(result, vertex)
            GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
            return result
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }

        private companion object {
            val DEFAULT_TEXTURE_COORDS = floatArrayOf(
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f,
            )

            const val VERTEX_SHADER = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = aTexCoord;
                }
            """

            const val FRAGMENT_SHADER = """
                precision mediump float;
                varying vec2 vTexCoord;
                uniform sampler2D yTexture;
                uniform sampler2D uTexture;
                uniform sampler2D vTexture;
                void main() {
                    float y = texture2D(yTexture, vTexCoord).r;
                    float u = texture2D(uTexture, vTexCoord).r - 0.5;
                    float v = texture2D(vTexture, vTexCoord).r - 0.5;
                    float r = y + 1.402 * v;
                    float g = y - 0.344136 * u - 0.714136 * v;
                    float b = y + 1.772 * u;
                    gl_FragColor = vec4(r, g, b, 1.0);
                }
            """

            fun floatBuffer(values: FloatArray): FloatBuffer =
                ByteBuffer.allocateDirect(values.size * java.lang.Float.BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .apply {
                        put(values)
                        position(0)
                    }

            fun directByteBuffer(size: Int): ByteBuffer =
                ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        }
    }
}
