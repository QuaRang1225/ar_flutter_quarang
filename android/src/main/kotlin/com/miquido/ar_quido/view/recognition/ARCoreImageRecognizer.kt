package com.miquido.ar_quido.view.recognition

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import io.flutter.FlutterInjector
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * ARCore 기반 이미지 인식 엔진
 * iOS의 ARKit 구현과 동일한 동작:
 * - 이미지 감지 시 회색 반투명 plane으로 이미지 덮기
 * - 이미지를 보고 있는 동안 계속 트래킹
 */
class ARCoreImageRecognizer(
    private val context: Context,
    private val imagePaths: List<String>,
    private val listener: ImageRecognitionListener
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "ARCoreImageRecognizer"
        private const val REFERENCE_IMAGES_PATH = "assets/reference_images/"
        private const val REFERENCE_IMAGE_EXTENSION = ".jpg"
        private const val PHYSICAL_WIDTH_METERS = 0.5f
        private const val CACHE_FILE_NAME = "arcore_image_database.bin"
        private const val CACHE_HASH_FILE_NAME = "arcore_image_database_hash.txt"
    }

    private var session: Session? = null
    private var activity: Activity? = null

    // 이미 콜백을 보낸 이미지 (중복 콜백 방지)
    private val notifiedImages = mutableSetOf<String>()
    private val isSessionResumed = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)

    private var backgroundRenderer: BackgroundRenderer? = null
    private var augmentedImageRenderer: AugmentedImageRenderer? = null
    private var videoTextureRenderer: VideoTextureRenderer? = null
    // imageName -> url mapping (provided from Flutter)
    private val videoUrlMap: MutableMap<String, String> = mutableMapOf()

    // 현재 트래킹 중인 이미지들
    private val trackedImages = mutableMapOf<String, AugmentedImage>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    // 이미지 DB 초기화 상태
    private val isImageDatabaseReady = AtomicBoolean(false)

    // 카메라 행렬 (GL 스레드에서 업데이트)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    // 터치 처리용 행렬 복사본 (동기화 목적)
    private val viewMatrixCopy = FloatArray(16)
    private val projectionMatrixCopy = FloatArray(16)
    private val matrixLock = Object()

    // 화면 크기
    private var screenWidth = 0
    private var screenHeight = 0

    // 터치 처리용 트래킹 이미지 스냅샷
    data class TrackedImageInfo(
        val name: String,
        val centerPose: FloatArray,  // 4x4 행렬
        val extentX: Float,
        val extentZ: Float
    )
    private val trackedImageSnapshots = mutableListOf<TrackedImageInfo>()
    private val snapshotLock = Object()

    fun setActivity(activity: Activity?) {
        this.activity = activity
    }

    fun setVideoUrlMap(map: Map<String, String>) {
        synchronized(videoUrlMap) {
            videoUrlMap.clear()
            videoUrlMap.putAll(map)
        }
        Log.i(TAG, "Video URL map updated: $videoUrlMap")
    }

    fun initialize(): Boolean {
        val currentActivity = activity ?: run {
            Log.e(TAG, "Activity is null")
            listener.onError(ErrorCode.RECOGNITION_SDK_INITIALIZE)
            return false
        }

        try {
            when (ArCoreApk.getInstance().requestInstall(currentActivity, true)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    Log.i(TAG, "ARCore install requested")
                    return false
                }
                ArCoreApk.InstallStatus.INSTALLED -> {
                    Log.i(TAG, "ARCore is installed")
                }
            }

            session = Session(currentActivity)

            // 백그라운드에서 이미지 DB 생성 (iOS의 resetTracking과 동일)
            backgroundExecutor.execute {
                Log.i(TAG, "Starting image database creation on background thread")
                val imageDatabase = createImageDatabase()

                if (imageDatabase == null) {
                    Log.e(TAG, "Failed to create image database")
                    mainHandler.post {
                        listener.onError(ErrorCode.RECOGNITION_SDK_LOAD_IMAGES)
                    }
                    return@execute
                }

                // 메인 스레드에서 세션 설정
                mainHandler.post {
                    try {
                        val currentSession = session ?: return@post

                        val config = Config(currentSession).apply {
                            augmentedImageDatabase = imageDatabase
                            focusMode = Config.FocusMode.AUTO
                            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                            planeFindingMode = Config.PlaneFindingMode.DISABLED
                            lightEstimationMode = Config.LightEstimationMode.DISABLED
                            depthMode = Config.DepthMode.DISABLED
                        }

                        currentSession.configure(config)
                        isImageDatabaseReady.set(true)
                        Log.i(TAG, "Image database configured with ${imagePaths.size} reference images")

                        // 이미지 DB 준비 완료 후 세션 resume (이미 resume 요청이 있었으면)
                        if (isInitialized.get() && !isSessionResumed.get()) {
                            resumeInternal()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to configure session", e)
                        listener.onError(ErrorCode.RECOGNITION_SDK_INITIALIZE)
                    }
                }
            }

            isInitialized.set(true)
            Log.i(TAG, "ARCore session created, image database loading in background...")
            return true

        } catch (e: UnavailableArcoreNotInstalledException) {
            Log.e(TAG, "ARCore not installed", e)
            listener.onError(ErrorCode.RECOGNITION_SDK_INITIALIZE)
        } catch (e: UnavailableApkTooOldException) {
            Log.e(TAG, "ARCore APK too old", e)
            listener.onError(ErrorCode.RECOGNITION_SDK_INITIALIZE)
        } catch (e: UnavailableSdkTooOldException) {
            Log.e(TAG, "ARCore SDK too old", e)
            listener.onError(ErrorCode.RECOGNITION_SDK_INITIALIZE)
        } catch (e: UnavailableDeviceNotCompatibleException) {
            Log.e(TAG, "Device not compatible", e)
            listener.onError(ErrorCode.RECOGNITION_SDK_INITIALIZE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize", e)
            listener.onError(ErrorCode.RECOGNITION_SDK_INITIALIZE)
        }

        return false
    }

    /**
     * 이미지 경로 목록의 해시값 계산 (캐시 유효성 검사용)
     */
    private fun calculateImagePathsHash(): String {
        val digest = MessageDigest.getInstance("MD5")
        val sortedPaths = imagePaths.sorted().joinToString("|")
        val hashBytes = digest.digest(sortedPaths.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 캐시된 해시값 읽기
     */
    private fun readCachedHash(): String? {
        val hashFile = File(context.cacheDir, CACHE_HASH_FILE_NAME)
        return try {
            if (hashFile.exists()) hashFile.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 해시값 캐시에 저장
     */
    private fun saveCachedHash(hash: String) {
        val hashFile = File(context.cacheDir, CACHE_HASH_FILE_NAME)
        try {
            hashFile.writeText(hash)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save hash", e)
        }
    }

    /**
     * 캐시된 이미지 DB 로드 시도
     */
    private fun loadCachedDatabase(): AugmentedImageDatabase? {
        val currentSession = session ?: return null
        val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)

        if (!cacheFile.exists()) {
            Log.i(TAG, "No cached database found")
            return null
        }

        // 해시 확인 (이미지 목록이 변경되었는지)
        val currentHash = calculateImagePathsHash()
        val cachedHash = readCachedHash()

        if (currentHash != cachedHash) {
            Log.i(TAG, "Image list changed, cache invalidated (current: $currentHash, cached: $cachedHash)")
            cacheFile.delete()
            return null
        }

        return try {
            val startTime = System.currentTimeMillis()
            FileInputStream(cacheFile).use { inputStream ->
                val database = AugmentedImageDatabase.deserialize(currentSession, inputStream)
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "Loaded cached database with ${database.numImages} images in ${elapsed}ms")
                database
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached database", e)
            cacheFile.delete()
            null
        }
    }

    /**
     * 이미지 DB를 캐시에 저장
     */
    private fun saveDatabaseToCache(database: AugmentedImageDatabase) {
        val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)

        try {
            FileOutputStream(cacheFile).use { outputStream ->
                database.serialize(outputStream)
            }
            saveCachedHash(calculateImagePathsHash())
            Log.i(TAG, "Database cached to ${cacheFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache database", e)
        }
    }

    private fun createImageDatabase(): AugmentedImageDatabase? {
        val currentSession = session ?: return null

        // 1. 캐시된 DB 로드 시도
        loadCachedDatabase()?.let { cachedDb ->
            return cachedDb
        }

        // 2. 캐시가 없거나 무효한 경우 새로 생성
        Log.i(TAG, "Creating new image database...")
        val startTime = System.currentTimeMillis()

        try {
            val imageDatabase = AugmentedImageDatabase(currentSession)
            val flutterLoader = FlutterInjector.instance().flutterLoader()

            for (imagePath in imagePaths) {
                val imageName = imagePath.substringAfterLast("/").substringBeforeLast(".")
                val lookupKey = flutterLoader.getLookupKeyForAsset(imagePath)

                try {
                    context.assets.open(lookupKey).use { inputStream ->
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        if (bitmap != null) {
                            val index = imageDatabase.addImage(imageName, bitmap, PHYSICAL_WIDTH_METERS)
                            Log.d(TAG, "Added image '$imageName' at index $index")
                            bitmap.recycle()
                        } else {
                            Log.e(TAG, "Failed to decode: $imageName")
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to load: $imagePath", e)
                }
            }

            if (imageDatabase.numImages > 0) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "Created database with ${imageDatabase.numImages} images in ${elapsed}ms")

                // 3. 캐시에 저장
                saveDatabaseToCache(imageDatabase)

                return imageDatabase
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create image database", e)
        }

        return null
    }

    fun resume() {
        if (!isInitialized.get()) {
            Log.w(TAG, "Cannot resume - not initialized")
            return
        }

        // 이미지 DB가 아직 준비 안됐으면 나중에 자동으로 resume됨
        if (!isImageDatabaseReady.get()) {
            Log.i(TAG, "Image database not ready yet, will resume after it's ready")
            return
        }

        resumeInternal()
    }

    private fun resumeInternal() {
        try {
            session?.resume()
            isSessionResumed.set(true)
            Log.i(TAG, "ARCore session resumed")

            mainHandler.post {
                listener.onRecognitionStarted()
            }
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            listener.onError(ErrorCode.UNKNOWN_ERROR)
        }
    }

    fun pause() {
        if (isSessionResumed.get()) {
            session?.pause()
            isSessionResumed.set(false)
            Log.i(TAG, "ARCore session paused")
        }
    }

    fun destroy() {
        pause()
        session?.close()
        session = null
        notifiedImages.clear()
        trackedImages.clear()
        synchronized(snapshotLock) {
            trackedImageSnapshots.clear()
        }
        isInitialized.set(false)
        isImageDatabaseReady.set(false)
        backgroundRenderer = null
        augmentedImageRenderer?.destroy()
        augmentedImageRenderer = null
        videoTextureRenderer?.destroy()
        videoTextureRenderer = null
        backgroundExecutor.shutdownNow()
        Log.i(TAG, "ARCore session destroyed")
    }

    fun resetTracking() {
        notifiedImages.clear()
        trackedImages.clear()
        synchronized(snapshotLock) {
            trackedImageSnapshots.clear()
        }
        Log.i(TAG, "Tracking reset")
    }

    fun setFlashlight(enabled: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }

            cameraId?.let {
                cameraManager.setTorchMode(it, enabled)
                Log.i(TAG, "Flashlight: $enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set flashlight", e)
        }
    }

    /**
     * 터치 이벤트 처리 - 트래킹 중인 이미지의 화면 영역과 터치 좌표 비교
     * 스레드 안전하게 스냅샷 데이터 사용
     */
    fun onTap(touchX: Float, touchY: Float) {
        if (!isSessionResumed.get()) return
        if (screenWidth == 0 || screenHeight == 0) return

        Log.i(TAG, "=== onTap called at ($touchX, $touchY) ===")

        // 스냅샷 데이터 복사 (스레드 안전)
        val snapshots: List<TrackedImageInfo>
        val localViewMatrix = FloatArray(16)
        val localProjMatrix = FloatArray(16)
        val localScreenWidth: Int
        val localScreenHeight: Int

        synchronized(snapshotLock) {
            snapshots = trackedImageSnapshots.toList()
        }
        synchronized(matrixLock) {
            System.arraycopy(viewMatrixCopy, 0, localViewMatrix, 0, 16)
            System.arraycopy(projectionMatrixCopy, 0, localProjMatrix, 0, 16)
            localScreenWidth = screenWidth
            localScreenHeight = screenHeight
        }

        Log.i(TAG, "Tracked image snapshots: ${snapshots.size}, screen: ${localScreenWidth}x${localScreenHeight}")

        if (snapshots.isEmpty()) {
            Log.i(TAG, "No tracked images in snapshot")
            return
        }

        // VP 행렬 계산
        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, localProjMatrix, 0, localViewMatrix, 0)

        // 각 트래킹 중인 이미지의 화면 영역 확인
        for (imageInfo in snapshots) {
            val modelMatrix = imageInfo.centerPose
            val extentX = imageInfo.extentX
            val extentZ = imageInfo.extentZ

            // 이미지의 4개 코너 좌표 계산 (로컬 좌표)
            val corners = arrayOf(
                floatArrayOf(-extentX / 2, 0f, -extentZ / 2),
                floatArrayOf(extentX / 2, 0f, -extentZ / 2),
                floatArrayOf(extentX / 2, 0f, extentZ / 2),
                floatArrayOf(-extentX / 2, 0f, extentZ / 2)
            )

            var minScreenX = Float.MAX_VALUE
            var maxScreenX = Float.MIN_VALUE
            var minScreenY = Float.MAX_VALUE
            var maxScreenY = Float.MIN_VALUE
            var validCorners = 0

            // 각 코너를 화면 좌표로 변환
            for (corner in corners) {
                // 월드 좌표로 변환
                val worldPos = FloatArray(4)
                worldPos[0] = modelMatrix[0] * corner[0] + modelMatrix[4] * corner[1] + modelMatrix[8] * corner[2] + modelMatrix[12]
                worldPos[1] = modelMatrix[1] * corner[0] + modelMatrix[5] * corner[1] + modelMatrix[9] * corner[2] + modelMatrix[13]
                worldPos[2] = modelMatrix[2] * corner[0] + modelMatrix[6] * corner[1] + modelMatrix[10] * corner[2] + modelMatrix[14]
                worldPos[3] = 1f

                // 클립 좌표로 변환
                val clipPos = FloatArray(4)
                Matrix.multiplyMV(clipPos, 0, vpMatrix, 0, worldPos, 0)

                // NDC로 변환 (카메라 뒤에 있는 경우 제외)
                if (clipPos[3] > 0.001f) {
                    val ndcX = clipPos[0] / clipPos[3]
                    val ndcY = clipPos[1] / clipPos[3]

                    // 화면 좌표로 변환
                    val screenX = (ndcX + 1f) / 2f * localScreenWidth
                    val screenY = (1f - ndcY) / 2f * localScreenHeight

                    minScreenX = minOf(minScreenX, screenX)
                    maxScreenX = maxOf(maxScreenX, screenX)
                    minScreenY = minOf(minScreenY, screenY)
                    maxScreenY = maxOf(maxScreenY, screenY)
                    validCorners++
                }
            }

            if (validCorners < 4) {
                Log.d(TAG, "Image '${imageInfo.name}' has invalid corners: $validCorners")
                continue
            }

            Log.i(TAG, "Image '${imageInfo.name}' screen bounds: ($minScreenX, $minScreenY) - ($maxScreenX, $maxScreenY)")
            Log.i(TAG, "Touch at ($touchX, $touchY), checking bounds...")

            // 터치 좌표가 이미지 영역 내에 있는지 확인 (약간의 여유 추가)
            val margin = 50f  // 50px 여유
            if (touchX >= (minScreenX - margin) && touchX <= (maxScreenX + margin) &&
                touchY >= (minScreenY - margin) && touchY <= (maxScreenY + margin)) {
                Log.i(TAG, "*** TAPPED ON IMAGE: ${imageInfo.name} ***")

                mainHandler.post {
                    listener.onImageTapped(imageInfo.name)
                }
                return
            }
        }

        Log.i(TAG, "Touch not on any tracked image")
    }

    // ========== GLSurfaceView.Renderer ==========

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        // 백그라운드 렌더러 생성
        backgroundRenderer = BackgroundRenderer()
        val textureId = backgroundRenderer!!.createOnGlThread()

        // 이미지 오버레이 렌더러 생성
        augmentedImageRenderer = AugmentedImageRenderer(context)
        augmentedImageRenderer!!.createOnGlThread()

        // 비디오 렌더러 생성
        videoTextureRenderer = VideoTextureRenderer(context)
        videoTextureRenderer!!.createOnGlThread()

        // ARCore 세션에 텍스처 설정
        session?.setCameraTextureName(textureId)

        Log.i(TAG, "Surface created, texture ID: $textureId")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        GLES30.glViewport(0, 0, width, height)

        val displayRotation = activity?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        session?.setDisplayGeometry(displayRotation, width, height)

        Log.i(TAG, "Surface changed: ${width}x${height}")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val currentSession = session ?: return
        if (!isSessionResumed.get()) return

        try {
            val frame = currentSession.update()

            // 카메라 배경 그리기
            backgroundRenderer?.draw(frame)

            // 카메라가 트래킹 중인지 확인
            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) {
                return
            }

            // 카메라 행렬 가져오기
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

            // 터치 처리용 행렬 복사 (스레드 안전)
            synchronized(matrixLock) {
                System.arraycopy(viewMatrix, 0, viewMatrixCopy, 0, 16)
                System.arraycopy(projectionMatrix, 0, projectionMatrixCopy, 0, 16)
            }

            // 모든 AugmentedImage 업데이트 확인
            val updatedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)

            for (augmentedImage in updatedImages) {
                val imageName = augmentedImage.name

                when (augmentedImage.trackingState) {
                    TrackingState.TRACKING -> {
                        // 트래킹 중인 이미지 저장
                        trackedImages[imageName] = augmentedImage

                        // 첫 감지 시 콜백 (iOS의 didAdd와 동일)
                        if (!notifiedImages.contains(imageName)) {
                            notifiedImages.add(imageName)
                            Log.i(TAG, "Image detected: $imageName")

                            mainHandler.post {
                                listener.onDetected(imageName)
                            }
                        }
                    }
                    TrackingState.PAUSED -> {
                        // 이미지가 시야에서 벗어남 - 트래킹 목록에서 제거
                        trackedImages.remove(imageName)
                        // 비디오 마커인 경우 비디오 정지 (번들 마커 또는 동적 URL 매핑)
                        if (VideoTextureRenderer.VIDEO_MARKERS.contains(imageName) || videoUrlMap.containsKey(imageName)) {
                            videoTextureRenderer?.stopVideo()
                        }
                    }
                    TrackingState.STOPPED -> {
                        // 트래킹 완전 중지
                        trackedImages.remove(imageName)
                        notifiedImages.remove(imageName)
                        // 비디오 마커인 경우 비디오 정지 (번들 마커 또는 동적 URL 매핑)
                        if (VideoTextureRenderer.VIDEO_MARKERS.contains(imageName) || videoUrlMap.containsKey(imageName)) {
                            videoTextureRenderer?.stopVideo()
                        }
                    }
                }
            }

            // 트래킹 중인 모든 이미지에 이미지/비디오 plane 그리기 (iOS SCNPlane과 동일)
            // 동시에 터치 처리용 스냅샷 업데이트
            val newSnapshots = mutableListOf<TrackedImageInfo>()

            for ((imageName, augmentedImage) in trackedImages) {
                if (augmentedImage.trackingState == TrackingState.TRACKING &&
                    augmentedImage.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING) {

                    // 비디오 마커인 경우 비디오 렌더러 사용, 아니면 이미지 렌더러 사용
                    // If there's a URL mapping for this image, prepare remote video; otherwise fallback to bundled markers
                    val isVideoDrawn: Boolean
                    if (videoUrlMap.containsKey(imageName)) {
                        val url = videoUrlMap[imageName]
                        if (!url.isNullOrEmpty()) {
                            videoTextureRenderer?.prepareVideoFromUrl(imageName, url)
                        }
                        isVideoDrawn = videoTextureRenderer?.draw(viewMatrix, projectionMatrix, augmentedImage) ?: true
                    } else {
                        // fallback to bundled asset markers
                        if (VideoTextureRenderer.VIDEO_MARKERS.contains(imageName)) {
                            videoTextureRenderer?.prepareVideo(imageName)
                        }
                        isVideoDrawn = videoTextureRenderer?.draw(viewMatrix, projectionMatrix, augmentedImage) ?: false
                    }
                    if (!isVideoDrawn) {
                        augmentedImageRenderer?.draw(viewMatrix, projectionMatrix, augmentedImage)
                    }

                    // 스냅샷 저장
                    val poseMatrix = FloatArray(16)
                    augmentedImage.centerPose.toMatrix(poseMatrix, 0)
                    newSnapshots.add(TrackedImageInfo(
                        name = imageName,
                        centerPose = poseMatrix,
                        extentX = augmentedImage.extentX,
                        extentZ = augmentedImage.extentZ
                    ))
                }
            }

            // 스냅샷 업데이트 (스레드 안전)
            synchronized(snapshotLock) {
                trackedImageSnapshots.clear()
                trackedImageSnapshots.addAll(newSnapshots)
            }

        } catch (e: NotTrackingException) {
            Log.d(TAG, "Not tracking")
        } catch (e: Exception) {
            Log.e(TAG, "Error during frame update", e)
        }
    }
}
