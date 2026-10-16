package com.example.camera_core.opengl

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import com.example.camera_core.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class BitmapDrawer (var context: Context){

    //创建着色器代码
    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            vTexCoord = aTexCoord;
            gl_Position = aPosition;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() {
            vec2 flippedCoord = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
            gl_FragColor = texture2D(uTexture, flippedCoord);
        }
    """.trimIndent()


    private var program: Int = 0
    private var textureId: Int = 0

    private var aPositionLocation: Int = 0
    private var aTexCoordLocation: Int = 0
    private var uTextureLocation: Int = 0

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


        //创建片元着色器
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        //创建顶点着色器
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        try {

            program = GLES20.glCreateProgram()

            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            // 7. 链接程序
            GLES20.glLinkProgram(program)


            // 获取 attribute 位置
            // 获取位置
            aPositionLocation = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
            uTextureLocation = GLES20.glGetUniformLocation(program, "uTexture")

            // 加载纹理
            textureId = loadTexture(context, R.drawable.sample_image)
        } catch (error: Exception) {

            throw error
        } finally {
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    fun draw() {
        GLES20.glUseProgram(program)

        // 激活并绑定纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureLocation, 0)
        // 传入顶点数据
        GLES20.glEnableVertexAttribArray(aPositionLocation)
        GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        // 传入纹理坐标数据
        GLES20.glEnableVertexAttribArray(aTexCoordLocation)
        GLES20.glVertexAttribPointer(aTexCoordLocation, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

        GLES20.glEnableVertexAttribArray(aTexCoordLocation)

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 清理
        GLES20.glDisableVertexAttribArray(aPositionLocation)
        GLES20.glDisableVertexAttribArray(aTexCoordLocation)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        // 1. 创建着色器对象
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "无法创建 OpenGL 着色器，type=$type" }
        // 2. 上传着色器源代码
        GLES20.glShaderSource(shader, shaderCode)
        // 3. 编译着色器
        GLES20.glCompileShader(shader)
        // 4. 检查编译状态
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            val infoLog = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("OES 着色器编译失败，type=$type：$infoLog")
        }
        return shader
    }

    fun loadTexture(context: Context, resourceId: Int): Int {
        val textureIds = IntArray(1)

        // 1. 生成 1 个纹理 ID
        GLES20.glGenTextures(1, textureIds, 0)
        if (textureIds[0] == 0) {
            throw RuntimeException("Error generating texture")
        }

        // 2. 加载 Bitmap
        val options = BitmapFactory.Options().apply {
            inScaled = false  // 不缩放
        }
        val bitmap = BitmapFactory.decodeResource(
            context.resources,
            resourceId,
            options
        ) ?: throw RuntimeException("Error decoding bitmap")

        // 3. 绑定到 2D 目标
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])

        // 4. 设置纹理参数
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        // 5. 上传纹理数据
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        // 6. 回收 Bitmap
        bitmap.recycle()

        // 7. 解绑纹理
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        return textureIds[0]
    }
}