package com.example.camera_core.opengl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class OesDrawer {

    // 顶点着色器：处理位置和矩阵变换
    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        uniform mat4 uSTMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uSTMatrix * aTexCoord).xy;
        }
    """.trimIndent()

    // 片段着色器：采样 OES 纹理并输出颜色 (核心宏定义不可少)
    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """.trimIndent()

    private var program = 0
    private var aPositionHandle = 0
    private var aTexCoordHandle = 0
    private var uSTMatrixHandle = 0
    private var textureSamplerHandle = 0

    // 铺满全屏的顶点坐标
    private val vertexData = floatArrayOf(
        -1.0f, -1.0f,
        1.0f, -1.0f,
        -1.0f,  1.0f,
        1.0f,  1.0f
    )

    // 对应的纹理坐标
    private val textureData = floatArrayOf(
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    )

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertexData).apply { position(0) }
    private val textureBuffer: FloatBuffer = ByteBuffer.allocateDirect(textureData.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().put(textureData).apply { position(0) }

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
            check(program != 0) { "无法创建 OpenGL 程序" }
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            check(linkStatus[0] == GLES20.GL_TRUE) {
                "OES 着色器链接失败：${GLES20.glGetProgramInfoLog(program)}"
            }

            aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
            textureSamplerHandle = GLES20.glGetUniformLocation(program, "sTexture")
            check(aPositionHandle >= 0 && aTexCoordHandle >= 0 &&
                uSTMatrixHandle >= 0 && textureSamplerHandle >= 0
            ) { "OES 着色器缺少必需的 attribute 或 uniform" }
        } catch (error: Exception) {
            release()
            throw error
        } finally {
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    fun draw(textureId: Int, transformMatrix: FloatArray) {
        check(program != 0) { "OES 着色器尚未初始化" }
        GLES20.glUseProgram(program)

        // 激活 OES 纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureSamplerHandle, 0)

        // 传入顶点数据
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        // 传入纹理坐标数据
        GLES20.glEnableVertexAttribArray(aTexCoordHandle)
        GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

        // 传入 SurfaceTexture 的纹理坐标变换矩阵。
        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, transformMatrix, 0)

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTexCoordHandle)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glUseProgram(0)

        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) {
            "OES 绘制失败：GL error=0x${error.toString(16)}"
        }
    }

    /** 必须在创建此程序的 GL 上下文仍有效时调用。 */
    fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "无法创建 OpenGL 着色器，type=$type" }
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            val infoLog = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("OES 着色器编译失败，type=$type：$infoLog")
        }
        return shader
    }
}
