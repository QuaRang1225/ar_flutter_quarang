import Foundation
import Flutter

// Instance counter for debugging
private var viewInstanceCounter = 0

class ARQuidoView: NSObject, FlutterPlatformView {
    private var viewController: ARQuidoViewController?
    private let viewInstanceId: Int
    private var cachedView: UIView

    init(
        frame: CGRect,
        viewIdentifier viewId: Int64,
        arguments args: Any?,
        binaryMessenger messenger: FlutterBinaryMessenger
    ) {
        viewInstanceCounter += 1
        self.viewInstanceId = viewInstanceCounter
        print("🟣 [View-\(viewInstanceId)] ARQuidoView INIT START - total views: \(viewInstanceCounter), viewId: \(viewId)")

        guard let creationParams = args as? Dictionary<String, Any?>,
              let referenceImagePaths = creationParams["referenceImagePaths"] as? Array<String> else {
            fatalError("Could not extract story names from creation params")
        }

        let channelName = "plugins.miquido.com/ar_quido"
        let channel = FlutterMethodChannel(name: channelName, binaryMessenger: messenger)

        let vc = ARQuidoViewController(referenceImagePaths: referenceImagePaths, methodChannel: channel)
        viewController = vc

        // Cache the view immediately - loadView will be called here
        cachedView = vc.view

        super.init()
        print("🟣 [View-\(viewInstanceId)] ARQuidoView INIT END")
    }

    deinit {
        print("🟤 [View-\(viewInstanceId)] ARQuidoView DEINIT START")

        // Call cleanup on the view controller before releasing
        if let vc = viewController {
            print("🟤 [View-\(viewInstanceId)] - calling vc.cleanup()")
            vc.cleanup()
        }

        viewController = nil
        viewInstanceCounter -= 1
        print("🟤 [View-\(viewInstanceId)] ARQuidoView DEINIT END - remaining views: \(viewInstanceCounter)")
    }

    func view() -> UIView {
        return cachedView
    }
}
