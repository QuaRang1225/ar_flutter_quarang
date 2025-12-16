package com.miquido.ar_quido.view.recognition

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import com.google.ar.core.AugmentedImage
import io.flutter.FlutterInjector
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 비디오를 AR 평면 위에 렌더링
 * iOS의 SKVideoNode + AVPlayer와 동일한 역할
 */
class VideoTextureRenderer(private val context: Context) {

    companion object {
        private const val TAG = "VideoTextureRenderer"
        private const val COORDS_PER_VERTEX = 3
        private const val TEX_COORDS_PER_VERTEX = 2
        private const val FLOAT_SIZE = 4

        // 비디오 마커 이름
        val VIDEO_MARKERS = setOf("hr-6", "st-11")

        // 단위 평면 정점 (중심 기준, XZ 평면)
        private val QUAD_COORDS = floatArrayOf(
            -0.5f, 0.0f, -0.5f,
            -0.5f, 0.0f, +0.5f,
            +0.5f, 0.0f, -0.5f,
            +0.5f, 0.0f, +0.5f
        )

        // 텍스처 좌표 (외부 텍스처용)
        // NOTE: 기존에는 (V)축을 뒤집어 넣고 있었는데, 기기/파이프라인에 따라 결과가 180도 뒤집혀 보일 수 있어
        // 기본(뒤집지 않음) 좌표를 사용한다.
        private val QUAD_TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
        )

        // 외부 텍스처(비디오)용 버텍스 셰이더 (기본 UV 그대로 사용)
        private const val VIDEO_VERTEX_SHADER = """
            uniform mat4 u_ModelViewProjection;
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = u_ModelViewProjection * a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        // 외부 텍스처(비디오)용 프래그먼트 셰이더
        private const val VIDEO_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_Texture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """
    }

    private var quadCoords: FloatBuffer
    private var quadTexCoords: FloatBuffer

    // 비디오 셰이더 프로그램
    private var videoProgram = 0
    private var videoPositionAttrib = 0
    private var videoTexCoordAttrib = 0
    private var videoMvpMatrixUniform = 0
    private var videoTextureUniform = 0

    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    // 비디오 재생 상태
    private var mediaPlayer: MediaPlayer? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var videoTextureId = 0
    private var currentVideoMarker: String? = null
    private var isVideoReady = false

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
        // 비디오 텍스처 생성
        val textureIds = IntArray(1)
        GLES30.glGenTextures(1, textureIds, 0)
        videoTextureId = textureIds[0]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        // 비디오 셰이더 프로그램 생성
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, VIDEO_VERTEX_SHADER)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, VIDEO_FRAGMENT_SHADER)

        videoProgram = GLES30.glCreateProgram()
        GLES30.glAttachShader(videoProgram, vertexShader)
        GLES30.glAttachShader(videoProgram, fragmentShader)
        GLES30.glLinkProgram(videoProgram)

        videoPositionAttrib = GLES30.glGetAttribLocation(videoProgram, "a_Position")
        videoTexCoordAttrib = GLES30.glGetAttribLocation(videoProgram, "a_TexCoord")
        videoMvpMatrixUniform = GLES30.glGetUniformLocation(videoProgram, "u_ModelViewProjection")
        videoTextureUniform = GLES30.glGetUniformLocation(videoProgram, "u_Texture")

        // SurfaceTexture 설정
        surfaceTexture = SurfaceTexture(videoTextureId)
        surface = Surface(surfaceTexture)

        Log.i(TAG, "Video renderer created, texture ID: $videoTextureId")
    }

    /**
     * 비디오 마커인지 확인
     */
    fun isVideoMarker(imageName: String): Boolean {
        return VIDEO_MARKERS.contains(imageName)
    }

    /**
     * 비디오 준비 (마커가 처음 감지되면 호출)
     */
    fun prepareVideo(imageName: String) {
        if (currentVideoMarker == imageName && isVideoReady) {
            return  // 이미 준비됨
        }

        // 기존 비디오 정리
        stopVideo()

        val videoAsset = when (imageName) {
            "hr-6" -> "assets/video/hr-6.mp4"
            "st-11" -> "assets/video/st-11.mp4"
            else -> return
        }

        val flutterLoader = FlutterInjector.instance().flutterLoader()
        val lookupKey = flutterLoader.getLookupKeyForAsset(videoAsset)

        try {
            val afd: AssetFileDescriptor = context.assets.openFd(lookupKey)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                setSurface(surface)
                isLooping = true
                setVolume(0f, 0f)  // 음소거 (iOS와 동일)
                setOnPreparedListener {
                    Log.i(TAG, "Video prepared: $imageName")
                    isVideoReady = true
                    start()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    false
                }
                prepareAsync()
            }

            currentVideoMarker = imageName
            afd.close()
            Log.i(TAG, "Video loading: $imageName")

        } catch (e: IOException) {
            Log.e(TAG, "Failed to load video: $videoAsset", e)
        }
    }

    /**
     * 비디오 정지
     */
    fun stopVideo() {
        mediaPlayer?.let {
            it.stop()
            it.release()
        }
        mediaPlayer = null
        currentVideoMarker = null
        isVideoReady = false
    }

    /**
     * 비디오 프레임 그리기
     */
    fun draw(
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        augmentedImage: AugmentedImage
    ): Boolean {
        val imageName = augmentedImage.name

        if (!isVideoMarker(imageName)) {
            return false
        }

        // 비디오 준비
        if (currentVideoMarker != imageName) {
            prepareVideo(imageName)
        }

        if (!isVideoReady || mediaPlayer == null) {
            return true  // 비디오 마커이지만 아직 준비 안됨
        }

        // SurfaceTexture 업데이트
        try {
            surfaceTexture?.updateTexImage()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update texture", e)
            return true
        }

        val pose = augmentedImage.centerPose
        val extentX = augmentedImage.extentX
        val extentZ = augmentedImage.extentZ

        // 모델 행렬 생성
        pose.toMatrix(modelMatrix, 0)
        Matrix.scaleM(modelMatrix, 0, extentX, 1.0f, extentZ)

        // MVP 행렬 계산
        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        // 블렌딩 활성화
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glUseProgram(videoProgram)

        // MVP 행렬 설정
        GLES30.glUniformMatrix4fv(videoMvpMatrixUniform, 1, false, mvpMatrix, 0)

        // 텍스처 바인딩
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES30.glUniform1i(videoTextureUniform, 0)

        // 정점 설정
        GLES30.glEnableVertexAttribArray(videoPositionAttrib)
        GLES30.glVertexAttribPointer(
            videoPositionAttrib, COORDS_PER_VERTEX, GLES30.GL_FLOAT, false, 0, quadCoords
        )

        // 텍스처 좌표 설정
        GLES30.glEnableVertexAttribArray(videoTexCoordAttrib)
        GLES30.glVertexAttribPointer(
            videoTexCoordAttrib, TEX_COORDS_PER_VERTEX, GLES30.GL_FLOAT, false, 0, quadTexCoords
        )

        // 그리기
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // 정리
        GLES30.glDisableVertexAttribArray(videoPositionAttrib)
        GLES30.glDisableVertexAttribArray(videoTexCoordAttrib)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES30.glDisable(GLES30.GL_BLEND)

        return true
    }

    /**
     * 리소스 정리
     */
    fun destroy() {
        stopVideo()

        surface?.release()
        surface = null

        surfaceTexture?.release()
        surfaceTexture = null

        if (videoTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(videoTextureId), 0)
            videoTextureId = 0
        }

        if (videoProgram != 0) {
            GLES30.glDeleteProgram(videoProgram)
            videoProgram = 0
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)

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
