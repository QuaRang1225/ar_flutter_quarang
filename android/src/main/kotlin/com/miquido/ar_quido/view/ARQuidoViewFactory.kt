package com.miquido.ar_quido.view

import android.app.Activity
import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class ARQuidoViewFactory(
    private val messenger: BinaryMessenger,
    private val applicationContext: Context
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    companion object {
        const val METHOD_CHANNEL_NAME = "plugins.miquido.com/ar_quido"
    }

    private var activity: Activity? = null
    private val createdViews = mutableListOf<ARQuidoView>()

    fun setActivity(activity: Activity?) {
        this.activity = activity
        // 이미 생성된 뷰들에게도 Activity 전달
        createdViews.forEach { it.setActivity(activity) }
    }

    override fun create(context: Context?, viewId: Int, args: Any?): PlatformView {
        val creationParams = args as? Map<*, *>
        val referenceImagePaths = (creationParams?.get("referenceImagePaths") as? List<*>)
            ?.filterIsInstance<String>()
            ?: emptyList()

        val methodChannel = MethodChannel(messenger, METHOD_CHANNEL_NAME)

        val view = ARQuidoView(
            context = context ?: applicationContext,
            viewId = viewId,
            imagePaths = referenceImagePaths,
            methodChannel = methodChannel,
            activity = activity
        )

        createdViews.add(view)
        return view
    }
}
