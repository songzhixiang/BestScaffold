package com.example.camera_core.opengl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Draws an ordinary GL_TEXTURE_2D, such as the color texture produced by an FBO. */
class Texture2dDrawer {

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """.trimIndent()

    private val vertexBuffer: FloatBuffer = floatBufferOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    )
    private val textureBuffer: FloatBuffer = floatBufferOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f,
    )

    private var program = 0
    private var positionHandle = -1
    private var textureCoordinateHandle = -1
    private var textureSamplerHandle = -1

    fun init() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = try {
            loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        } catch (error: Exception) {
            GLES20.glDeleteShader(vertexShader)
            throw error
        }

        try {
            program = GLES20.glCreateProgram()
            check(program != 0) { "无法创建 2D 纹理绘制程序" }
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            check(linkStatus[0] == GLES20.GL_TRUE) {
                "2D 纹理着色器链接失败：${GLES20.glGetProgramInfoLog(program)}"
            }

            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            textureCoordinateHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            textureSamplerHandle = GLES20.glGetUniformLocation(program, "uTexture")
            check(positionHandle >= 0 && textureCoordinateHandle >= 0 && textureSamplerHandle >= 0) {
                "2D 纹理着色器缺少必需的 attribute 或 uniform"
            }
        } catch (error: Exception) {
            release()
            throw error
        } finally {
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    fun draw(textureId: Int) {
        check(program != 0) { "2D 纹理着色器尚未初始化" }
        require(textureId != 0) { "待绘制的 2D 纹理 ID 不能为 0" }

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        // sampler receives the texture-unit index, not the OpenGL texture ID.
        GLES20.glUniform1i(textureSamplerHandle, 0)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(
            positionHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            vertexBuffer,
        )

        textureBuffer.position(0)
        GLES20.glEnableVertexAttribArray(textureCoordinateHandle)
        GLES20.glVertexAttribPointer(
            textureCoordinateHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            textureBuffer,
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(textureCoordinateHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)

        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) {
            "绘制 FBO 纹理失败：GL error=0x${error.toString(16)}"
        }
    }

    fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        positionHandle = -1
        textureCoordinateHandle = -1
        textureSamplerHandle = -1
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "无法创建 OpenGL 着色器，type=$type" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            val infoLog = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("2D 纹理着色器编译失败，type=$type：$infoLog")
        }
        return shader
    }

    private fun floatBufferOf(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(values)
            .apply { position(0) }
}
