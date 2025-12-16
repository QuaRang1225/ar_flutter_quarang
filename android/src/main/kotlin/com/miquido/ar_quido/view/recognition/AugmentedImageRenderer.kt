package com.miquido.ar_quido.view.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.AugmentedImage
import io.flutter.FlutterInjector
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 감지된 이미지 위에 텍스처 이미지를 렌더링
 * iOS의 SCNPlane과 동일한 역할
 */
class AugmentedImageRenderer(private val context: Context) {

    companion object {
        private const val TAG = "AugmentedImageRenderer"
        private const val COORDS_PER_VERTEX = 3
        private const val TEX_COORDS_PER_VERTEX = 2
        private const val FLOAT_SIZE = 4

        // 단위 평면 정점 (중심 기준, XZ 평면)
        private val QUAD_COORDS = floatArrayOf(
            -0.5f, 0.0f, -0.5f,
            -0.5f, 0.0f, +0.5f,
            +0.5f, 0.0f, -0.5f,
            +0.5f, 0.0f, +0.5f
        )

        // 텍스처 좌표 (Y축 뒤집기)
        private val QUAD_TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
        )

        // 텍스처용 버텍스 셰이더
        private const val TEXTURE_VERTEX_SHADER = """
            uniform mat4 u_ModelViewProjection;
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = u_ModelViewProjection * a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        // 텍스처용 프래그먼트 셰이더
        private const val TEXTURE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D u_Texture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """

        // 이미지 에셋 검색 경로 (iOS imageAssetPath와 동일)
        private val IMAGE_DIRECTORIES = listOf(
            "assets/images/marker_images/hm",
            "assets/images/marker_images/hr",
            "assets/images/marker_images/st/1",
            "assets/images/marker_images/st/2",
            "assets/images/marker_images/st/3",
            "assets/images/marker_images/2022/1",
            "assets/images/marker_images/2022/2",
            "assets/images/marker_images/2022/3",
            "assets/images/marker_images/2022/4",
            "assets/images/marker_images/ps",
        )

        // 지원하는 이미지 확장자
        private val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg")
    }

    private var quadCoords: FloatBuffer
    private var quadTexCoords: FloatBuffer

    // 텍스처 프로그램
    private var textureProgram = 0
    private var texPositionAttrib = 0
    private var texCoordAttrib = 0
    private var texMvpMatrixUniform = 0
    private var textureUniform = 0

    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    // 텍스처 캐시 (이미지명 -> OpenGL 텍스처 ID)
    private val textureCache = mutableMapOf<String, Int>()

    // 이미지 경로 캐시 (iOS의 resolvedImagePathCache와 동일)
    private val imagePathCache = mutableMapOf<String, String?>()

    init {
        quadCoords = ByteBuffer.allocateDirect(QUAD_COORDS.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(QUAD_COORDS)
        quadCoords.position(0)

        quadTexCoords = ByteBuffer.allocateDirect(QUAD_TEX_COORDS.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(QUAD_TEX_COORDS)
        quadTexCoords.position(0)
    }

    fun createOnGlThread() {
        // 텍스처 셰이더 프로그램 생성
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, TEXTURE_VERTEX_SHADER)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, TEXTURE_FRAGMENT_SHADER)

        textureProgram = GLES30.glCreateProgram()
        GLES30.glAttachShader(textureProgram, vertexShader)
        GLES30.glAttachShader(textureProgram, fragmentShader)
        GLES30.glLinkProgram(textureProgram)

        texPositionAttrib = GLES30.glGetAttribLocation(textureProgram, "a_Position")
        texCoordAttrib = GLES30.glGetAttribLocation(textureProgram, "a_TexCoord")
        texMvpMatrixUniform = GLES30.glGetUniformLocation(textureProgram, "u_ModelViewProjection")
        textureUniform = GLES30.glGetUniformLocation(textureProgram, "u_Texture")

        Log.i(TAG, "Texture program created")
    }

    /**
     * 이미지 에셋 경로 찾기 (iOS의 imageAssetPath와 동일)
     */
    private fun findImageAssetPath(imageName: String): String? {
        // 캐시 확인
        if (imagePathCache.containsKey(imageName)) {
            return imagePathCache[imageName]
        }

        val flutterLoader = FlutterInjector.instance().flutterLoader()

        for (dir in IMAGE_DIRECTORIES) {
            for (ext in IMAGE_EXTENSIONS) {
                val assetPath = "$dir/$imageName.$ext"
                val lookupKey = flutterLoader.getLookupKeyForAsset(assetPath)

                try {
                    // 에셋이 존재하는지 확인
                    context.assets.open(lookupKey).use {
                        imagePathCache[imageName] = lookupKey
                        Log.i(TAG, "Found image asset: $lookupKey")
                        return lookupKey
                    }
                } catch (e: IOException) {
                    // 에셋 없음, 다음 시도
                }
            }
        }

        // 찾지 못함
        imagePathCache[imageName] = null
        Log.w(TAG, "Image not found for: $imageName")
        return null
    }

    /**
     * 텍스처 로드 (이미지명으로)
     */
    private fun loadTexture(imageName: String): Int {
        // 캐시 확인
        textureCache[imageName]?.let { return it }

        val assetPath = findImageAssetPath(imageName) ?: return 0

        try {
            val bitmap = context.assets.open(assetPath).use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            } ?: return 0

            val textureId = createTextureFromBitmap(bitmap)
            bitmap.recycle()

            if (textureId != 0) {
                textureCache[imageName] = textureId
                Log.i(TAG, "Texture loaded for '$imageName': $textureId")
            }

            return textureId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load texture for $imageName", e)
            return 0
        }
    }

    /**
     * 비트맵을 OpenGL 텍스처로 변환
     */
    private fun createTextureFromBitmap(bitmap: Bitmap): Int {
        val textureIds = IntArray(1)
        GLES30.glGenTextures(1, textureIds, 0)

        if (textureIds[0] == 0) {
            Log.e(TAG, "Failed to generate texture ID")
            return 0
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureIds[0])

        // 텍스처 파라미터 설정
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // 비트맵을 텍스처로 로드
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        return textureIds[0]
    }

    /**
     * 이미지 텍스처로 그리기
     */
    fun draw(
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        augmentedImage: AugmentedImage
    ) {
        val imageName = augmentedImage.name
        val pose = augmentedImage.centerPose
        val extentX = augmentedImage.extentX
        val extentZ = augmentedImage.extentZ

        // 텍스처 로드 시도
        val textureId = loadTexture(imageName)

        if (textureId == 0) {
            // 텍스처 로드 실패 시 건너뛰기 (또는 기본 색상으로 그릴 수 있음)
            return
        }

        // 모델 행렬 생성: pose 위치에 이미지 크기만큼 스케일
        pose.toMatrix(modelMatrix, 0)

        // 스케일 적용 (이미지 실제 크기)
        Matrix.scaleM(modelMatrix, 0, extentX, 1.0f, extentZ)

        // MVP 행렬 계산
        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        // 블렌딩 활성화 (PNG 투명도 지원)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glUseProgram(textureProgram)

        // MVP 행렬 설정
        GLES30.glUniformMatrix4fv(texMvpMatrixUniform, 1, false, mvpMatrix, 0)

        // 텍스처 바인딩
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(textureUniform, 0)

        // 정점 설정
        GLES30.glEnableVertexAttribArray(texPositionAttrib)
        GLES30.glVertexAttribPointer(
            texPositionAttrib, COORDS_PER_VERTEX, GLES30.GL_FLOAT, false, 0, quadCoords
        )

        // 텍스처 좌표 설정
        GLES30.glEnableVertexAttribArray(texCoordAttrib)
        GLES30.glVertexAttribPointer(
            texCoordAttrib, TEX_COORDS_PER_VERTEX, GLES30.GL_FLOAT, false, 0, quadTexCoords
        )

        // 그리기
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // 정리
        GLES30.glDisableVertexAttribArray(texPositionAttrib)
        GLES30.glDisableVertexAttribArray(texCoordAttrib)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /**
     * 리소스 정리
     */
    fun destroy() {
        for ((_, textureId) in textureCache) {
            if (textureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            }
        }
        textureCache.clear()
        imagePathCache.clear()

        if (textureProgram != 0) {
            GLES30.glDeleteProgram(textureProgram)
            textureProgram = 0
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)

        // 컴파일 에러 체크
        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val errorMsg = GLES30.glGetShaderInfoLog(shader)
            Log.e(TAG, "Shader compile error: $errorMsg")
            GLES30.glDeleteShader(shader)
            return 0
        }

        return shader
    }
}
