package com.miquido.ar_quido

import com.miquido.ar_quido.view.ARQuidoViewFactory
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding

class ARQuidoPlugin : FlutterPlugin, ActivityAware {

    companion object {
        private const val VIEW_TYPE = "plugins.miquido.com/ar_quido_view_android"
    }

    private var viewFactory: ARQuidoViewFactory? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        viewFactory = ARQuidoViewFactory(flutterPluginBinding.binaryMessenger, flutterPluginBinding.applicationContext)
        flutterPluginBinding.platformViewRegistry.registerViewFactory(VIEW_TYPE, viewFactory!!)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        viewFactory = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        viewFactory?.setActivity(binding.activity)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        viewFactory?.setActivity(null)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        viewFactory?.setActivity(binding.activity)
    }

    override fun onDetachedFromActivity() {
        viewFactory?.setActivity(null)
    }
}
