import UIKit
import ARKit
import SceneKit
import AVFoundation
import SpriteKit
import Flutter

protocol ImageRecognitionDelegate: AnyObject {
    func onRecognitionStarted()
    func onRecognitionPaused()
    func onRecognitionResumed()
    func onDetect(imageKey: String)
    func onDetectedImageTapped(imageKey: String)
}

// Instance counter for debugging
private var instanceCounter = 0

class ARQuidoViewController: UIViewController {

    private var sceneView: ARSCNView?

    private var session: ARSession? {
        return sceneView?.session
    }

    private var wasCameraInitialized = false
    private var isResettingTracking = false
    private let referenceImagePaths: Array<String>
    private let methodChannel: FlutterMethodChannel

    // Cache resolved bundle paths to avoid repeated Flutter key lookups
    private var resolvedImagePathCache: [String: String] = [:]

    // Keep strong references for video playback
    private var activePlayer: AVPlayer?
    private var activeVideoNode: SKVideoNode?
    private var activeVideoScene: SKScene?

    // Track nodes per anchor to support multiple image re-detection
    private var anchorNodeMap: [UUID: SCNNode] = [:]
    private var currentlyTrackedImageName: String?

    // Track if view controller is being deallocated
    private var isBeingTornDown = false
    private var hasCleanedUp = false

    // Debug instance ID
    private let instanceId: Int

    init(referenceImagePaths: Array<String>, methodChannel channel: FlutterMethodChannel) {
        instanceCounter += 1
        self.instanceId = instanceCounter
        self.referenceImagePaths = referenceImagePaths
        self.methodChannel = channel
        super.init(nibName: nil, bundle: nil)
        print("🟢 [\(instanceId)] ARQuidoViewController INIT - total instances: \(instanceCounter)")
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        print("🔴 [\(instanceId)] ARQuidoViewController DEINIT - hasCleanedUp: \(hasCleanedUp)")
        // Don't do heavy cleanup here - should already be done in viewWillDisappear or cleanup()
        instanceCounter -= 1
        print("🔴 [\(instanceId)] ARQuidoViewController DEINIT END - remaining instances: \(instanceCounter)")
    }

    /// Call this to fully cleanup before deallocation
    func cleanup() {
        print("🧹 [\(instanceId)] cleanup() called - hasCleanedUp: \(hasCleanedUp)")
        guard !hasCleanedUp else {
            print("🧹 [\(instanceId)] cleanup() - already cleaned up, skipping")
            return
        }
        hasCleanedUp = true
        isBeingTornDown = true

        print("🧹 [\(instanceId)] - removing method call handler")
        methodChannel.setMethodCallHandler(nil)

        print("🧹 [\(instanceId)] - calling cleanupAllContent")
        cleanupAllContent()

        print("🧹 [\(instanceId)] - pausing session")
        sceneView?.session.pause()

        print("🧹 [\(instanceId)] - removing delegates")
        sceneView?.session.delegate = nil
        sceneView?.delegate = nil

        print("🧹 [\(instanceId)] - removing child nodes")
        sceneView?.scene.rootNode.enumerateChildNodes { node, _ in
            node.removeFromParentNode()
        }

        print("🧹 [\(instanceId)] - setting sceneView to nil")
        sceneView = nil

        print("🧹 [\(instanceId)] cleanup() END")
    }

    override func loadView() {
        print("📱 [\(instanceId)] loadView START")

        let arView = ARSCNView(frame: .zero)
        arView.delegate = self
        arView.session.delegate = self

        let tapGesture = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        arView.addGestureRecognizer(tapGesture)

        sceneView = arView
        view = arView

        print("📱 [\(instanceId)] loadView END")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        print("📱 [\(instanceId)] viewDidLoad")
        methodChannel.setMethodCallHandler(handleMethodCall(call:result:))
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        print("📱 [\(instanceId)] viewDidAppear - isBeingTornDown: \(isBeingTornDown), hasCleanedUp: \(hasCleanedUp)")

        guard !hasCleanedUp else {
            print("📱 [\(instanceId)] viewDidAppear - already cleaned up, skipping")
            return
        }

        UIApplication.shared.isIdleTimerDisabled = true
        isBeingTornDown = false
        resetTracking()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        print("📱 [\(instanceId)] viewWillDisappear")

        isBeingTornDown = true
        pauseSession()
    }

    private func pauseSession() {
        print("⏸️ [\(instanceId)] pauseSession START")

        sceneView?.session.pause()
        cleanupAllContent()
        currentlyTrackedImageName = nil

        print("⏸️ [\(instanceId)] pauseSession END")
    }

    private func cleanupAllContent() {
        print("🗑️ [\(instanceId)] cleanupAllContent - anchorNodeMap: \(anchorNodeMap.count)")

        activeVideoNode?.pause()
        activePlayer?.pause()
        activeVideoNode = nil
        activePlayer = nil
        activeVideoScene = nil

        for (_, node) in anchorNodeMap {
            node.removeFromParentNode()
        }
        anchorNodeMap.removeAll()
    }

    @objc
    func handleTap(_ gestureRecognize: UIGestureRecognizer) {
        guard let sceneView = sceneView, !isBeingTornDown else { return }
        let location = gestureRecognize.location(in: sceneView)
        let hitResults = sceneView.hitTest(location, options: [:])
        if hitResults.count > 0, let tappedImageName = hitResults[0].node.name {
            onDetectedImageTapped(imageKey: tappedImageName)
        }
    }

    // MARK: - Session management

    var isRestartAvailable = true

    func resetTracking() {
        print("🔄 [\(instanceId)] resetTracking - isBeingTornDown: \(isBeingTornDown), isResettingTracking: \(isResettingTracking)")

        guard !isBeingTornDown, !hasCleanedUp else {
            print("🔄 [\(instanceId)] resetTracking ABORTED")
            return
        }

        if isResettingTracking {
            return
        }
        isResettingTracking = true
        currentlyTrackedImageName = nil

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self, !self.isBeingTornDown, !self.hasCleanedUp else {
                self?.isResettingTracking = false
                return
            }

            var referenceImages = [ARReferenceImage]()

            for assetPath in self.referenceImagePaths {
                if self.isBeingTornDown || self.hasCleanedUp { break }

                let imageName = ((assetPath as NSString).lastPathComponent as NSString).deletingPathExtension
                let flutterKey = FlutterDartProject.lookupKey(forAsset: assetPath)

                guard let bundlePath = Bundle.main.path(forResource: flutterKey, ofType: nil),
                      let image = UIImage(contentsOfFile: bundlePath),
                      let cg = image.cgImage else {
                    continue
                }

                let referenceImage = ARReferenceImage(cg, orientation: .up, physicalWidth: 0.5)
                referenceImage.name = imageName
                referenceImages.append(referenceImage)
            }

            guard !self.isBeingTornDown, !self.hasCleanedUp else {
                self.isResettingTracking = false
                return
            }

            print("🔄 [\(self.instanceId)] - loaded \(referenceImages.count) reference images")

            let configuration = ARWorldTrackingConfiguration()
            configuration.detectionImages = Set(referenceImages)
            configuration.maximumNumberOfTrackedImages = 4

            DispatchQueue.main.async { [weak self] in
                guard let self = self, !self.isBeingTornDown, !self.hasCleanedUp else {
                    self?.isResettingTracking = false
                    return
                }

                print("🔄 [\(self.instanceId)] - running AR session")
                self.session?.run(configuration, options: [.resetTracking, .removeExistingAnchors])

                if !self.wasCameraInitialized {
                    self.onRecognitionStarted()
                    self.wasCameraInitialized = true
                } else {
                    self.onRecognitionResumed()
                }

                self.isResettingTracking = false
                print("🔄 [\(self.instanceId)] resetTracking END")
            }
        }
    }
}

extension ARQuidoViewController: ARSCNViewDelegate {

    func renderer(_ renderer: SCNSceneRenderer, didAdd node: SCNNode, for anchor: ARAnchor) {
        guard !isBeingTornDown, !hasCleanedUp else { return }
        guard let imageAnchor = anchor as? ARImageAnchor else { return }

        let imageName = imageAnchor.referenceImage.name ?? ""
        print("🎯 [\(instanceId)] didAdd: \(imageName)")

        currentlyTrackedImageName = imageName

        DispatchQueue.main.async { [weak self] in
            guard let self = self, !self.isBeingTornDown, !self.hasCleanedUp else { return }
            self.onDetect(imageKey: imageName)
        }

        addOverlayNode(for: imageAnchor, to: node)
    }

    func renderer(_ renderer: SCNSceneRenderer, didUpdate node: SCNNode, for anchor: ARAnchor) {
        guard !isBeingTornDown, !hasCleanedUp else { return }
        guard let imageAnchor = anchor as? ARImageAnchor else { return }

        let imageName = imageAnchor.referenceImage.name ?? ""

        if imageAnchor.isTracked {
            if anchorNodeMap[anchor.identifier] == nil {
                addOverlayNode(for: imageAnchor, to: node)
            }
            anchorNodeMap[anchor.identifier]?.isHidden = false

            if currentlyTrackedImageName != imageName {
                currentlyTrackedImageName = imageName
                DispatchQueue.main.async { [weak self] in
                    guard let self = self, !self.isBeingTornDown, !self.hasCleanedUp else { return }
                    self.onDetect(imageKey: imageName)
                }
            }
        } else {
            anchorNodeMap[anchor.identifier]?.isHidden = true
            if currentlyTrackedImageName == imageName {
                currentlyTrackedImageName = nil
            }
        }
    }

    func renderer(_ renderer: SCNSceneRenderer, didRemove node: SCNNode, for anchor: ARAnchor) {
        guard let imageAnchor = anchor as? ARImageAnchor else { return }

        let imageName = imageAnchor.referenceImage.name ?? ""
        print("🎯 [\(instanceId)] didRemove: \(imageName)")

        if let overlayNode = anchorNodeMap[anchor.identifier] {
            overlayNode.removeFromParentNode()
            anchorNodeMap.removeValue(forKey: anchor.identifier)
        }

        if imageName == "hr-6" || imageName == "st-11" {
            activeVideoNode?.pause()
            activePlayer?.pause()
            activeVideoNode = nil
            activePlayer = nil
            activeVideoScene = nil
        }
    }

    private func addOverlayNode(for imageAnchor: ARImageAnchor, to parentNode: SCNNode) {
        guard !isBeingTornDown, !hasCleanedUp else { return }

        let referenceImage = imageAnchor.referenceImage
        let imageName = referenceImage.name ?? ""

        if let existingNode = anchorNodeMap[imageAnchor.identifier] {
            existingNode.removeFromParentNode()
            anchorNodeMap.removeValue(forKey: imageAnchor.identifier)
        }

        // Video case
        if imageName == "hr-6" || imageName == "st-11" {
            let asset = (imageName == "hr-6") ? "assets/video/hr-6.mp4" : "assets/video/st-11.mp4"
            let flutterKey = FlutterDartProject.lookupKey(forAsset: asset)

            guard let videoPath = Bundle.main.path(forResource: flutterKey, ofType: nil),
                  FileManager.default.fileExists(atPath: videoPath) else {
                return
            }

            DispatchQueue.main.async { [weak self] in
                guard let self = self, !self.isBeingTornDown, !self.hasCleanedUp else { return }

                let player = AVPlayer(url: URL(fileURLWithPath: videoPath))
                player.isMuted = true

                let videoNode = SKVideoNode(avPlayer: player)
                videoNode.yScale = -1

                let skScene = SKScene(size: CGSize(width: 1280, height: 720))
                videoNode.position = CGPoint(x: skScene.size.width / 2, y: skScene.size.height / 2)
                videoNode.size = skScene.size
                skScene.addChild(videoNode)

                let material = SCNMaterial()
                material.diffuse.contents = skScene
                material.isDoubleSided = true

                let plane = SCNPlane(width: referenceImage.physicalSize.width,
                                     height: referenceImage.physicalSize.height)
                plane.materials = [material]

                let planeNode = SCNNode(geometry: plane)
                planeNode.name = imageName
                planeNode.eulerAngles.x = -.pi / 2

                parentNode.addChildNode(planeNode)
                self.anchorNodeMap[imageAnchor.identifier] = planeNode

                self.activePlayer = player
                self.activeVideoNode = videoNode
                self.activeVideoScene = skScene

                player.play()
                videoNode.play()
            }
            return
        }

        // Image case
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self, !self.isBeingTornDown, !self.hasCleanedUp else { return }

            let imagePath = self.imageAssetPath(for: imageName)

            DispatchQueue.main.async { [weak self] in
                guard let self = self, !self.isBeingTornDown, !self.hasCleanedUp else { return }

                guard let imagePath = imagePath,
                      let image = UIImage(contentsOfFile: imagePath) else {
                    return
                }

                let plane = SCNPlane(width: referenceImage.physicalSize.width,
                                     height: referenceImage.physicalSize.height)

                let material = SCNMaterial()
                material.diffuse.contents = image
                material.isDoubleSided = true
                material.lightingModel = .constant
                plane.materials = [material]

                let planeNode = SCNNode(geometry: plane)
                planeNode.name = imageName
                planeNode.eulerAngles.x = -.pi / 2

                parentNode.addChildNode(planeNode)
                self.anchorNodeMap[imageAnchor.identifier] = planeNode
            }
        }
    }
}

extension ARQuidoViewController: ARSessionDelegate {
    func session(_ session: ARSession, didFailWithError error: Error) {
        print("❌ [\(instanceId)] session didFailWithError: \(error.localizedDescription)")
        guard error is ARError, !isBeingTornDown, !hasCleanedUp else { return }

        let errorWithInfo = error as NSError
        let messages = [
            errorWithInfo.localizedDescription,
            errorWithInfo.localizedFailureReason,
            errorWithInfo.localizedRecoverySuggestion
        ]
        let errorMessage = messages.compactMap({ $0 }).joined(separator: "\n")

        DispatchQueue.main.async { [weak self] in
            guard let self = self, !self.isBeingTornDown, !self.hasCleanedUp else { return }
            self.displayErrorMessage(title: "AR session failed", message: errorMessage)
        }
    }

    func sessionWasInterrupted(_ session: ARSession) {
        print("⚠️ [\(instanceId)] sessionWasInterrupted")
        cleanupAllContent()
        currentlyTrackedImageName = nil
    }

    func sessionInterruptionEnded(_ session: ARSession) {
        print("✅ [\(instanceId)] sessionInterruptionEnded")
        guard !isBeingTornDown, !hasCleanedUp else { return }
        restartExperience()
    }

    func sessionShouldAttemptRelocalization(_ session: ARSession) -> Bool {
        return true
    }

    func displayErrorMessage(title: String, message: String) {
        guard !isBeingTornDown, !hasCleanedUp else { return }
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Restart", style: .default) { [weak self] _ in
            self?.resetTracking()
        })
        present(alert, animated: true)
    }

    func restartExperience() {
        print("🔁 [\(instanceId)] restartExperience")
        guard isRestartAvailable, !isBeingTornDown, !hasCleanedUp else { return }
        isRestartAvailable = false
        resetTracking()
        DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) { [weak self] in
            self?.isRestartAvailable = true
        }
    }
}

// MARK: - FlutterMethodChannel

extension ARQuidoViewController {
    private func handleMethodCall(call: FlutterMethodCall, result: FlutterResult) {
        if call.method == "scanner#toggleFlashlight" {
            let arguments = call.arguments as? Dictionary<String, Any?>
            let shouldTurnOn = (arguments?["shouldTurnOn"] as? Bool) ?? false
            toggleFlashlight(shouldTurnOn)
            result(nil)
        } else {
            result(FlutterMethodNotImplemented)
        }
    }

    private func toggleFlashlight(_ shouldTurnOn: Bool) {
        guard let camera = AVCaptureDevice.default(for: .video), camera.hasTorch else { return }
        do {
            try camera.lockForConfiguration()
            camera.torchMode = shouldTurnOn ? .on : .off
            camera.unlockForConfiguration()
        } catch {
            print("Torch error: \(error)")
        }
    }
}

extension ARQuidoViewController: ImageRecognitionDelegate {
    func onRecognitionPaused() {
        guard !isBeingTornDown, !hasCleanedUp else { return }
        print("📤 [\(instanceId)] onRecognitionPaused")
        methodChannel.invokeMethod("scanner#recognitionPaused", arguments: nil)
    }

    func onRecognitionResumed() {
        guard !isBeingTornDown, !hasCleanedUp else { return }
        print("📤 [\(instanceId)] onRecognitionResumed")
        methodChannel.invokeMethod("scanner#recognitionResumed", arguments: nil)
    }

    func onRecognitionStarted() {
        guard !isBeingTornDown, !hasCleanedUp else { return }
        print("📤 [\(instanceId)] onRecognitionStarted")
        methodChannel.invokeMethod("scanner#start", arguments: [String:Any]())
    }

    func onDetect(imageKey: String) {
        guard !isBeingTornDown, !hasCleanedUp else { return }
        print("📤 [\(instanceId)] onDetect: \(imageKey)")
        methodChannel.invokeMethod("scanner#onImageDetected", arguments: ["imageName": imageKey])
    }

    func onDetectedImageTapped(imageKey: String) {
        guard !isBeingTornDown, !hasCleanedUp else { return }
        print("📤 [\(instanceId)] onDetectedImageTapped: \(imageKey)")
        methodChannel.invokeMethod("scanner#onDetectedImageTapped", arguments: ["imageName": imageKey])
    }
}

extension ARQuidoViewController {
    func imageAssetPath(for imageName: String) -> String? {
        if let cached = resolvedImagePathCache[imageName] {
            return cached
        }
        let base = "assets/images/marker_images"
        let directories = [
            "\(base)/hm", "\(base)/hr",
            "\(base)/st/1", "\(base)/st/2", "\(base)/st/3",
            "\(base)/2022/1", "\(base)/2022/2", "\(base)/2022/3", "\(base)/2022/4",
            "\(base)/ps"
        ]

        for dir in directories {
            if let resolved = resolveAsset("\(dir)/\(imageName)") {
                resolvedImagePathCache[imageName] = resolved
                return resolved
            }
        }
        return nil
    }

    private func resolveAsset(_ basePath: String) -> String? {
        for ext in ["png", "jpg", "jpeg"] {
            let assetPath = "\(basePath).\(ext)"
            let flutterKey = FlutterDartProject.lookupKey(forAsset: assetPath)
            if let bundlePath = Bundle.main.path(forResource: flutterKey, ofType: nil) {
                return bundlePath
            }
        }
        return nil
    }
}
