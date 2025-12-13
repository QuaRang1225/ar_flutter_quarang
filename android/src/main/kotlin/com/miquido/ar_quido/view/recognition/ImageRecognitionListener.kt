package com.miquido.ar_quido.view.recognition

interface ImageRecognitionListener {
    fun onRecognitionStarted()
    fun onError(errorCode: ErrorCode)
    fun onDetected(detectedImage: String)
    fun onImageTapped(tappedImage: String)
}
