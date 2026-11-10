package com.example.camera_core.opengl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OesDrawer {

    // 顶点着色器：处理位置和矩阵变换
    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        uniform mat4 uMvpMatrix;
        uniform mat4 uSTMatrix;
        uniform mat4 uUserTextureMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMvpMatrix * aPosition;
            vTexCoord = (uSTMatrix * uUserTextureMatrix * aTexCoord).xy;
        }
    """.trimIndent()

    // 片段着色器：采样 OES 纹理并输出颜色 (核心宏定义不可少)
    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture; //sTexture 是采样器，不是纹理 ID
        uniform float uSwirlEnabled;
        uniform vec2 uSwirlCenter;
        uniform float uSwirlRadius;
        uniform float uSwirlStrength;
        uniform float uTextureAspectRatio;
        void main() {
            vec2 sampleCoordinate = vTexCoord;
            if (uSwirlEnabled > 0.5) {
                vec2 delta = sampleCoordinate - uSwirlCenter;
                delta.x *= uTextureAspectRatio;
                float distanceToCenter = length(delta);
                if (distanceToCenter < uSwirlRadius) {
                    float falloff = (uSwirlRadius - distanceToCenter) / uSwirlRadius;
                    float angle = uSwirlStrength * falloff * falloff;
                    float sine = sin(angle);
                    float cosine = cos(angle);
                    delta = mat2(cosine, -sine, sine, cosine) * delta;
                    delta.x /= uTextureAspectRatio;
                    sampleCoordinate = uSwirlCenter + delta;
                }
            }
            gl_FragColor = texture2D(sTexture, clamp(sampleCoordinate, 0.0, 1.0)); //实际上是在对 GPU 说：“请去 sTexture 指定的 0 号卡槽，按照插值得到的 vTexCoord 坐标，把那里的像素颜色捞出来”。
        }
    """.trimIndent()

    private var program = 0
    private var vboId = 0
    private var vaoId = 0
    private var aPositionHandle = 0
    private var aTexCoordHandle = 0
    private var uMvpMatrixHandle = 0
    private var uSTMatrixHandle = 0
    private var uUserTextureMatrixHandle = 0
    private var textureSamplerHandle = 0
    private var swirlEnabledHandle = 0
    private var swirlCenterHandle = 0
    private var swirlRadiusHandle = 0
    private var swirlStrengthHandle = 0
    private var textureAspectRatioHandle = 0

    // 每个顶点按 x、y、u、v 交错存放，保留相机纹理原有的 90 度旋转。
    private val vertexData = floatArrayOf(
        -1.0f, -1.0f, 0.0f, 1.0f,
        1.0f, -1.0f, 0.0f, 0.0f,
        -1.0f, 1.0f, 1.0f, 1.0f,
        1.0f, 1.0f, 1.0f, 0.0f,
    )

    /** 在当前 OpenGL ES 3.0 或更高版本的上下文中初始化。 */
    fun init() {
        //创建片元着色器
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        //创建顶点着色器
        val fragmentShader = try {
            loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        } catch (error: Exception) {
            GLES20.glDeleteShader(vertexShader)
            throw error
        }

        try {
            // 5. 创建程序对象
            program = GLES20.glCreateProgram()
            check(program != 0) { "无法创建 OpenGL 程序" }
            // 6. 附加着色器
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            // 7. 链接程序
            GLES20.glLinkProgram(program)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            check(linkStatus[0] == GLES20.GL_TRUE) {
                "OES 着色器链接失败：${GLES20.glGetProgramInfoLog(program)}"
            }
            GLES20.glUseProgram(program);
            // 获取 attribute 位置
            aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            uMvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMvpMatrix")
            uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
            uUserTextureMatrixHandle = GLES20.glGetUniformLocation(program, "uUserTextureMatrix")
            textureSamplerHandle = GLES20.glGetUniformLocation(program, "sTexture")
            // 1. 采样器指定纹理单元（永久生效，移出绘制循环）
            GLES20.glUniform1i(textureSamplerHandle, 0)
            swirlEnabledHandle = GLES20.glGetUniformLocation(program, "uSwirlEnabled")
            swirlCenterHandle = GLES20.glGetUniformLocation(program, "uSwirlCenter")
            swirlRadiusHandle = GLES20.glGetUniformLocation(program, "uSwirlRadius")
            swirlStrengthHandle = GLES20.glGetUniformLocation(program, "uSwirlStrength")
            textureAspectRatioHandle = GLES20.glGetUniformLocation(program, "uTextureAspectRatio")
            check(aPositionHandle >= 0 && aTexCoordHandle >= 0 && uMvpMatrixHandle >= 0 &&
                uSTMatrixHandle >= 0 && uUserTextureMatrixHandle >= 0 &&
                textureSamplerHandle >= 0 && swirlEnabledHandle >= 0 &&
                swirlCenterHandle >= 0 && swirlRadiusHandle >= 0 &&
                swirlStrengthHandle >= 0 && textureAspectRatioHandle >= 0
            ) { "OES 着色器缺少必需的 attribute 或 uniform" }
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

    fun draw(
        textureId: Int,
        textureMatrix: FloatArray,
        userTextureMatrix: FloatArray,
        mvpMatrix: FloatArray,
        swirlEffect: OesSwirlEffect,
    ) {
        check(program != 0) { "OES 着色器尚未初始化" }
        // 使用程序
        GLES20.glUseProgram(program)

        // 激活 OES 纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)


        // MVP 改变顶点几何位置；ST 矩阵只改变 OES 纹理采样坐标。
        GLES20.glUniformMatrix4fv(uMvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, textureMatrix, 0)
        GLES20.glUniformMatrix4fv(
            uUserTextureMatrixHandle,
            1,
            false,
            userTextureMatrix,
            0,
        )
        GLES20.glUniform1f(swirlEnabledHandle, if (swirlEffect.enabled) 1f else 0f)
        GLES20.glUniform2f(swirlCenterHandle, swirlEffect.centerX, swirlEffect.centerY)
        GLES20.glUniform1f(swirlRadiusHandle, swirlEffect.radius)
        GLES20.glUniform1f(swirlStrengthHandle, swirlEffect.strengthRadians)
        GLES20.glUniform1f(textureAspectRatioHandle, swirlEffect.textureAspectRatio)

        GLES30.glBindVertexArray(vaoId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        // 解绑纹理
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        // 关闭程序
        GLES20.glUseProgram(0)

        checkGl("OES 绘制")
    }

    /** 必须在创建这些资源的 GL 上下文处于当前状态时调用。 */
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

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) {
            "$operation 失败：GL error=0x${error.toString(16)}"
        }
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
}
