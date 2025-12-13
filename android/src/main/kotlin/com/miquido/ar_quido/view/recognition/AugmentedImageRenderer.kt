package com.miquido.ar_quido.view.recognition

import android.opengl.GLES30
import android.opengl.Matrix
import com.google.ar.core.AugmentedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 감지된 이미지 위에 반투명 plane을 렌더링
 * iOS의 SCNPlane과 동일한 역할
 */
class AugmentedImageRenderer {

    companion object {
        private const val COORDS_PER_VERTEX = 3
        private const val FLOAT_SIZE = 4

        // 단위 평면 정점 (중심 기준, XZ 평면)
        private val QUAD_COORDS = floatArrayOf(
            -0.5f, 0.0f, -0.5f,
            -0.5f, 0.0f, +0.5f,
            +0.5f, 0.0f, -0.5f,
            +0.5f, 0.0f, +0.5f
        )

        private const val VERTEX_SHADER = """
            uniform mat4 u_ModelViewProjection;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_ModelViewProjection * a_Position;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
    }

    // ========== 색상 및 투명도 설정 ==========
    // RGB: 0.0 ~ 1.0, Alpha: 0.0 (투명) ~ 1.0 (불투명)
    var colorR = 0.6f   // 회색
    var colorG = 0.6f
    var colorB = 0.6f
    var colorA = 0.5f  // 75% 불투명 (iOS와 동일)

    private var quadCoords: FloatBuffer
    private var program = 0
    private var positionAttrib = 0
    private var mvpMatrixUniform = 0
    private var colorUniform = 0

    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    init {
        quadCoords = ByteBuffer.allocateDirect(QUAD_COORDS.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(QUAD_COORDS)
        quadCoords.position(0)
    }

    fun createOnGlThread() {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        positionAttrib = GLES30.glGetAttribLocation(program, "a_Position")
        mvpMatrixUniform = GLES30.glGetUniformLocation(program, "u_ModelViewProjection")
        colorUniform = GLES30.glGetUniformLocation(program, "u_Color")
    }

    /**
     * 색상 설정 (0.0 ~ 1.0)
     */
    fun setColor(r: Float, g: Float, b: Float, a: Float) {
        colorR = r.coerceIn(0f, 1f)
        colorG = g.coerceIn(0f, 1f)
        colorB = b.coerceIn(0f, 1f)
        colorA = a.coerceIn(0f, 1f)
    }

    /**
     * 색상 설정 (0 ~ 255)
     */
    fun setColorRGB(r: Int, g: Int, b: Int, a: Int) {
        colorR = r / 255f
        colorG = g / 255f
        colorB = b / 255f
        colorA = a / 255f
    }

    fun draw(
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        augmentedImage: AugmentedImage
    ) {
        val pose = augmentedImage.centerPose
        val extentX = augmentedImage.extentX
        val extentZ = augmentedImage.extentZ

        // 모델 행렬 생성: pose 위치에 이미지 크기만큼 스케일
        pose.toMatrix(modelMatrix, 0)

        // 스케일 적용 (이미지 실제 크기)
        Matrix.scaleM(modelMatrix, 0, extentX, 1.0f, extentZ)

        // MVP 행렬 계산
        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        // 블렌딩 활성화 (반투명)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glUseProgram(program)

        // MVP 행렬 설정
        GLES30.glUniformMatrix4fv(mvpMatrixUniform, 1, false, mvpMatrix, 0)

        // 색상 설정
        GLES30.glUniform4f(colorUniform, colorR, colorG, colorB, colorA)

        // 정점 설정
        GLES30.glEnableVertexAttribArray(positionAttrib)
        GLES30.glVertexAttribPointer(
            positionAttrib, COORDS_PER_VERTEX, GLES30.GL_FLOAT, false, 0, quadCoords
        )

        // 그리기
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // 정리
        GLES30.glDisableVertexAttribArray(positionAttrib)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)
        return shader
    }
}
