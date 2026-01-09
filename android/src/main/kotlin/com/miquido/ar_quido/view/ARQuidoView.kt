package com.miquido.ar_quido.view

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.miquido.ar_quido.view.recognition.ARCoreImageRecognizer
import com.miquido.ar_quido.view.recognition.ErrorCode
import com.miquido.ar_quido.view.recognition.ImageRecognitionListener
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView

/**
 * ARCore 기반 이미지 인식 PlatformView
 * iOS의 ARQuidoViewController와 동일한 동작
 */
@SuppressLint("ViewConstructor")
class ARQuidoView(
    context: Context,
    private val viewId: Int,
    private val imagePaths: List<String>,
    private val methodChannel: MethodChannel,
    private var activity: Activity?
) : GLSurfaceView(context), PlatformView {

    companion object {
        private const val TAG = "ARQuidoView"
    }

    private var shouldFlashlightBeOn = false
    private var isViewAttached = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * iOS의 ImageRecognitionDelegate와 동일한 콜백 구현
     */
    private val recognitionListener = object : ImageRecognitionListener {
        override fun onRecognitionStarted() {
            mainHandler.post {
                Log.i(TAG, "Recognition started")
                methodChannel.invokeMethod(
                    "scanner#start",
                    mapOf("view" to viewId)
                )
            }
        }

        override fun onError(errorCode: ErrorCode) {
            mainHandler.post {
                Log.e(TAG, "Recognition error: $errorCode")
                methodChannel.invokeMethod(
                    "scanner#error",
                    mapOf("errorCode" to errorCode.toString())
                )
            }
        }

        override fun onDetected(detectedImage: String) {
            mainHandler.post {
                Log.i(TAG, "Image detected: $detectedImage")
                methodChannel.invokeMethod(
                    "scanner#onImageDetected",
                    mapOf("imageName" to detectedImage)
                )
            }
        }

        override fun onImageTapped(tappedImage: String) {
            mainHandler.post {
                Log.i(TAG, "Image tapped: $tappedImage")
                methodChannel.invokeMethod(
                    "scanner#onDetectedImageTapped",
                    mapOf("imageName" to tappedImage)
                )
            }
        }
    }

    private var recognizer: ARCoreImageRecognizer? = null

    init {
        methodChannel.setMethodCallHandler(::handleMethodCall)

        // OpenGL ES 3.0 설정 (ARCore 요구사항)
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true

        // 인식기 생성
        createRecognizer()

        Log.i(TAG, "ARQuidoView created with ${imagePaths.size} reference images")
    }

    private fun createRecognizer() {
        recognizer = ARCoreImageRecognizer(
            context = context,
            imagePaths = imagePaths,
            listener = recognitionListener
        ).also {
            it.setActivity(activity)
            setRenderer(it)
            renderMode = RENDERMODE_CONTINUOUSLY
        }
    }

    // ========== 터치 이벤트 처리 (iOS handleTap과 동일) ==========

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // ACTION_DOWN을 처리해야 후속 이벤트(ACTION_UP)를 받을 수 있음
                Log.d(TAG, "Touch DOWN at: (${event.x}, ${event.y})")
                return true
            }
            MotionEvent.ACTION_UP -> {
                val x = event.x
                val y = event.y
                Log.i(TAG, "Touch UP at: ($x, $y)")

                // 터치된 이미지 찾기
                recognizer?.onTap(x, y)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ========== Lifecycle 관리 ==========

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isViewAttached = true
        Log.i(TAG, "View attached to window")

        // ARCore 세션 초기화 및 시작
        recognizer?.let { rec ->
            if (rec.initialize()) {
                rec.resume()
                refreshFlashlightState()
            }
        }
    }

    override fun onDetachedFromWindow() {
        isViewAttached = false
        Log.i(TAG, "View detached from window")

        recognizer?.pause()
        recognizer?.destroy()

        super.onDetachedFromWindow()
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "View resumed")

        if (isViewAttached) {
            recognizer?.resume()
            refreshFlashlightState()
        }
    }

    override fun onPause() {
        Log.i(TAG, "View paused")
        recognizer?.pause()
        super.onPause()
    }

    // ========== PlatformView 구현 ==========

    override fun getView(): View = this

    override fun dispose() {
        Log.i(TAG, "Disposing view")
        methodChannel.setMethodCallHandler(null)
        recognizer?.destroy()
        recognizer = null
    }

    // ========== Activity 연동 ==========

    fun setActivity(newActivity: Activity?) {
        activity = newActivity
        recognizer?.setActivity(newActivity)
    }

    // ========== MethodChannel 핸들링 ==========

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "scanner#toggleFlashlight" -> {
                val shouldTurnOn = call.argument<Boolean>("shouldTurnOn") ?: false
                toggleFlashlight(shouldTurnOn)
                result.success(null)
            }
            "scanner#loadVideos" -> {
                // Expecting arguments: { "videos": [ {"imageName":"hr-6", "url":"https://...mp4"}, ... ] }
                val videos = call.argument<List<Map<String, Any>>>("videos")
                val map = mutableMapOf<String, String>()
                videos?.forEach { item ->
                    val name = item["imageName"] as? String
                    val url = item["url"] as? String
                    if (!name.isNullOrEmpty() && !url.isNullOrEmpty()) {
                        map[name] = url
                    }
                }
                recognizer?.setVideoUrlMap(map)
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    private fun toggleFlashlight(shouldTurnOn: Boolean) {
        recognizer?.setFlashlight(shouldTurnOn)
        shouldFlashlightBeOn = shouldTurnOn
    }

    private fun refreshFlashlightState() {
        toggleFlashlight(shouldFlashlightBeOn)
    }
}
