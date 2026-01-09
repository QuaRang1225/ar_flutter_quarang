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

    // Keep strong references for video playback per anchor
    private var anchorVideoPlayerMap: [UUID: AVPlayer] = [:]
    private var anchorVideoNodeMap: [UUID: SKVideoNode] = [:]
    private var anchorVideoURLMap: [UUID: URL] = [:]  // 앵커별 비디오 URL 저장
    // Mapping from reference image name -> video URL (local file path or remote http(s) URL)
    private var videoURLMap: [String: String] = [:]

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
        // Ensure audio session is configured for playback so video audio is audible
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
            try AVAudioSession.sharedInstance().setActive(true)
            print("[\(instanceId)] Audio session configured for playback")
        } catch {
            print("[\(instanceId)] Failed to configure audio session: \(error)")
        }
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

        // 모든 비디오 정지 및 정리
        for (_, player) in anchorVideoPlayerMap {
            player.pause()
        }
        for (_, videoNode) in anchorVideoNodeMap {
            videoNode.pause()
        }
        anchorVideoPlayerMap.removeAll()
        anchorVideoNodeMap.removeAll()
        anchorVideoURLMap.removeAll()

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
        let anchorId = anchor.identifier

        if imageAnchor.isTracked {
            if anchorNodeMap[anchorId] == nil {
                addOverlayNode(for: imageAnchor, to: node)
            }
            anchorNodeMap[anchorId]?.isHidden = false

            if currentlyTrackedImageName != imageName {
                currentlyTrackedImageName = imageName
                DispatchQueue.main.async { [weak self] in
                    guard let self = self, !self.isBeingTornDown, !self.hasCleanedUp else { return }
                    self.onDetect(imageKey: imageName)
                }
            }
        } else {
            // 트래킹 해제됨
            anchorNodeMap[anchorId]?.isHidden = true

            if currentlyTrackedImageName == imageName {
                currentlyTrackedImageName = nil
            }
        }
    }

    func renderer(_ renderer: SCNSceneRenderer, didRenderScene scene: SCNScene, atTime time: TimeInterval) {
        // 매 프레임마다 실제로 보이는 비디오만 재생 (안드로이드와 동일한 로직)
        guard !isBeingTornDown, !hasCleanedUp else { return }
        guard let currentFrame = sceneView?.session.currentFrame else { return }

        // 비디오 플레이어가 없으면 스킵
        guard !anchorVideoPlayerMap.isEmpty else { return }

        // 1. 현재 트래킹 중인 비디오 앵커 찾기
        var currentlyTrackedVideoAnchorId: UUID? = nil

        for anchor in currentFrame.anchors {
            guard let imageAnchor = anchor as? ARImageAnchor else { continue }

            let imageName = imageAnchor.referenceImage.name ?? ""
            let isVideoMarker = imageName == "hr-6" || imageName == "st-11" || videoURLMap[imageName] != nil

            if isVideoMarker && imageAnchor.isTracked && anchorVideoPlayerMap[anchor.identifier] != nil {
                currentlyTrackedVideoAnchorId = anchor.identifier
                break // 하나만 찾으면 됨 (안드로이드처럼 한 번에 하나만 재생)
            }
        }

        // 2. 모든 비디오 플레이어 상태 업데이트
        for (anchorId, player) in anchorVideoPlayerMap {
            guard let videoNode = anchorVideoNodeMap[anchorId] else { continue }

            let shouldPlay = (anchorId == currentlyTrackedVideoAnchorId)
            let isPlaying = (player.rate != 0)

            if shouldPlay && !isPlaying {
                // 재생해야 하는데 멈춰있음 → 재생
                DispatchQueue.main.async {
                    // currentItem이 제거된 경우 URL을 다시 로드
                    if player.currentItem == nil, let url = self.anchorVideoURLMap[anchorId] {
                        let newItem = AVPlayerItem(url: url)
                        player.replaceCurrentItem(with: newItem)
                        print("🎬 [\(self.instanceId)] 🔄 Video item reloaded: \(anchorId)")
                    }

                    player.isMuted = false
                    player.volume = 1.0
                    player.play()
                    videoNode.play()
                    print("🎬 [\(self.instanceId)] ✅ Video PLAY: \(anchorId), rate=\(player.rate), volume=\(player.volume)")
                }
            } else if !shouldPlay && isPlaying {
                // 멈춰야 하는데 재생 중 → AVAudioSession을 직접 제어해서 완전히 정지
                DispatchQueue.main.async {
                    // 1. 플레이어 완전 정지
                    player.pause()
                    player.rate = 0.0
                    player.volume = 0.0
                    player.isMuted = true

                    // 2. 오디오 세션 비활성화 (강제)
                    do {
                        try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
                        try AVAudioSession.sharedInstance().setActive(true)
                    } catch {
                        print("🎬 [\(self.instanceId)] ⚠️ Failed to reset audio session: \(error)")
                    }

                    // 3. currentItem 제거
                    player.replaceCurrentItem(with: nil)
                    videoNode.pause()

                    print("🎬 [\(self.instanceId)] ⏸️ Video STOP: \(anchorId) - audio session reset")
                }
            }
        }
    }

    func renderer(_ renderer: SCNSceneRenderer, didRemove node: SCNNode, for anchor: ARAnchor) {
        guard let imageAnchor = anchor as? ARImageAnchor else { return }

        let imageName = imageAnchor.referenceImage.name ?? ""
        let anchorId = anchor.identifier
        print("🎯 [\(instanceId)] didRemove: \(imageName)")

        if let overlayNode = anchorNodeMap[anchorId] {
            overlayNode.removeFromParentNode()
            anchorNodeMap.removeValue(forKey: anchorId)
        }

        // 비디오 마커가 완전히 제거되면 비디오 정지 및 리소스 해제
        if imageName == "hr-6" || imageName == "st-11" || videoURLMap[imageName] != nil {
            anchorVideoPlayerMap[anchorId]?.pause()
            anchorVideoNodeMap[anchorId]?.pause()
            anchorVideoPlayerMap.removeValue(forKey: anchorId)
            anchorVideoNodeMap.removeValue(forKey: anchorId)
            anchorVideoURLMap.removeValue(forKey: anchorId)
            print("🎬 [\(instanceId)] Video stopped and resources released: \(imageName)")
        }

        // 현재 트래킹 중인 이미지가 제거되면 초기화
        if currentlyTrackedImageName == imageName {
            currentlyTrackedImageName = nil
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

        // Video case: prefer remote/local URL provided via MethodChannel mapping, otherwise fallback to bundled assets for known names
        if let mappedURLString = videoURLMap[imageName] {
            guard let url = URL(string: mappedURLString) else { return }

            DispatchQueue.main.async { [weak self] in
                guard let self = self, !self.isBeingTornDown, !self.hasCleanedUp else { return }

                let player = AVPlayer(url: url)
                player.isMuted = false
                player.volume = 1.0
                player.allowsExternalPlayback = false

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

                // 앵커별로 비디오 플레이어 저장
                self.anchorVideoPlayerMap[imageAnchor.identifier] = player
                self.anchorVideoNodeMap[imageAnchor.identifier] = videoNode
                self.anchorVideoURLMap[imageAnchor.identifier] = url  // URL 저장 (재생 재개 시 필요)

                player.play()
                videoNode.play()
            }
            return
        }

        // fallback: existing bundled-asset handling for specific image names
        if imageName == "hr-6" || imageName == "st-11" {
            let asset = (imageName == "hr-6") ? "assets/video/hr-6.mp4" : "assets/video/st-11.mp4"
            let flutterKey = FlutterDartProject.lookupKey(forAsset: asset)

            guard let videoPath = Bundle.main.path(forResource: flutterKey, ofType: nil),
                  FileManager.default.fileExists(atPath: videoPath) else {
                return
            }

            DispatchQueue.main.async { [weak self] in
                guard let self = self, !self.isBeingTornDown, !self.hasCleanedUp else { return }

                let videoURL = URL(fileURLWithPath: videoPath)
                let player = AVPlayer(url: videoURL)
                player.isMuted = false
                player.volume = 1.0

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

                // 앵커별로 비디오 플레이어 저장
                self.anchorVideoPlayerMap[imageAnchor.identifier] = player
                self.anchorVideoNodeMap[imageAnchor.identifier] = videoNode
                self.anchorVideoURLMap[imageAnchor.identifier] = videoURL  // URL 저장 (재생 재개 시 필요)

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
        } else if call.method == "scanner#loadVideos" {
            // Load video mappings from Flutter
            let arguments = call.arguments as? Dictionary<String, Any?>
            if let videos = arguments?["videos"] as? [Dictionary<String, Any?>] {
                var map: [String: String] = [:]
                for item in videos {
                    if let name = item["imageName"] as? String, let url = item["url"] as? String {
                        map[name] = url
                    }
                }
                DispatchQueue.main.async { [weak self] in
                    self?.videoURLMap = map
                    print("[\(self?.instanceId ?? -1)] handleMethodCall loaded video map: \(map)")
                }
            }
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
