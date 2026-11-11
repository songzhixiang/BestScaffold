package com.example.camera_core.opengl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Draws an ordinary GL_TEXTURE_2D, such as the color texture produced by an FBO. */
class Texture2dDrawer {

    private val vertexShaderCode = """
        #version 300 es
        in vec4 aPosition;
        in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #version 300 es
        precision mediump float;
        in vec2 vTexCoord;
        uniform sampler2D uTexture;
        layout(location = 0) out vec4 fragColor;
        void main() {
            fragColor = texture(uTexture, vTexCoord);
        }
    """.trimIndent()

    private val vertexData = floatArrayOf(
        -1f, -1f,0f, 0f,
        1f, -1f,1f, 0f,
        -1f, 1f,0f, 1f,
        1f, 1f,1f, 1f,
    )
    private var vboId = 0
    private var vaoId = 0
    private var program = 0
    private var aPositionHandle = -1
    private var aTexCoordHandle = -1
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

            aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            textureSamplerHandle = GLES20.glGetUniformLocation(program, "uTexture")
            check(aPositionHandle >= 0 && aTexCoordHandle >= 0 && textureSamplerHandle >= 0) {
                "2D 纹理着色器缺少必需的 attribute 或 uniform"
            }
            initVertexArray()
        } catch (error: Exception) {
            release()
            throw error
        } finally {
            GLES20.glUseProgram(0)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    private fun initVertexArray() {
        val byteCount = vertexData.size * Float.SIZE_BYTES
        val vertexBuffer = ByteBuffer.allocateDirect(byteCount)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexData)
            .apply { position(0) }
        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vboId = buffers[0]
        check(vboId != 0) { "无法创建 OES 顶点缓冲区" }

        val vertexArrays = IntArray(1)
        GLES30.glGenVertexArrays(1, vertexArrays, 0)
        vaoId = vertexArrays[0]
        check(vaoId != 0) { "无法创建 OES 顶点数组对象" }

        GLES30.glBindVertexArray(vaoId)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)

        try {
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER, byteCount, vertexBuffer, GLES20.GL_STATIC_DRAW,
            )

            val strideBytes = 4 * Float.SIZE_BYTES
            val uvOffsetBytes = 2 * Float.SIZE_BYTES
            GLES20.glEnableVertexAttribArray(aPositionHandle)
            GLES20.glVertexAttribPointer(
                aPositionHandle, 2, GLES20.GL_FLOAT, false, strideBytes, 0,
            )
            GLES20.glEnableVertexAttribArray(aTexCoordHandle)
            GLES20.glVertexAttribPointer(
                aTexCoordHandle, 2, GLES20.GL_FLOAT, false, strideBytes, uvOffsetBytes,
            )
            checkGl("初始化 OES VAO/VBO")
        } finally {
            GLES30.glBindVertexArray(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }
    }

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) {
            "$operation 失败：GL error=0x${error.toString(16)}"
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

        GLES30.glBindVertexArray(vaoId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)

        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) {
            "绘制 FBO 纹理失败：GL error=0x${error.toString(16)}"
        }
    }

    fun release() {
        if (vaoId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
            vaoId = 0
        }
        if (vboId != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(vboId), 0)
            vboId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }

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
