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

class ARQuidoViewController: UIViewController {
    
    var sceneView: ARSCNView!
    
    let updateQueue = DispatchQueue(label: Bundle.main.bundleIdentifier! +
                                    ".serialSceneKitQueue")
    
    var session: ARSession {
        return sceneView.session
    }
    
    private var wasCameraInitialized = false
    private var isResettingTracking = false
    private let referenceImagePaths: Array<String>
    private let methodChannel: FlutterMethodChannel
    private var detectedImageNode: SCNNode?
    // Cache resolved bundle paths to avoid repeated Flutter key lookups
    private var resolvedImagePathCache: [String: String] = [:]

    // Keep strong references for video playback
    private var activePlayer: AVPlayer?
    private var activeVideoNode: SKVideoNode?
    private var activeVideoScene: SKScene?
    
    init(referenceImagePaths: Array<String>, methodChannel channel: FlutterMethodChannel) {
        self.referenceImagePaths = referenceImagePaths
        self.methodChannel = channel
        super.init(nibName: nil, bundle: nil)
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    deinit {
        methodChannel.setMethodCallHandler(nil)
    }
    
    override func viewDidLoad() {
        super.viewDidLoad()
        methodChannel.setMethodCallHandler(handleMethodCall(call:result:))
        sceneView = ARSCNView(frame: CGRect.zero)
        sceneView.delegate = self
        sceneView.session.delegate = self
        let tapGesture = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        sceneView.addGestureRecognizer(tapGesture)
        view = sceneView
    }
    
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        UIApplication.shared.isIdleTimerDisabled = true
        resetTracking()
    }
    
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        session.pause()
        cleanupDetectedContent()
        onRecognitionPaused()
    }
    
    @objc
    func handleTap(_ gestureRecognize: UIGestureRecognizer) {
        let location = gestureRecognize.location(in: sceneView)
        let hitResults = sceneView.hitTest(location, options: [:])
        if hitResults.count > 0, let tappedImageName = hitResults[0].node.name {
            onDetectedImageTapped(imageKey: tappedImageName)
        }
        
    }
    
    // MARK: - Session management (Image detection setup)
    
    /// Prevents restarting the session while a restart is in progress.
    var isRestartAvailable = true
    
    func resetTracking() {
        if isResettingTracking {
            return
        }
        isResettingTracking = true

        // Build reference images off the main thread
        DispatchQueue.global(qos: .userInitiated).async {
            var referenceImages = [ARReferenceImage]()

            for assetPath in self.referenceImagePaths {
                let imageName = ((assetPath as NSString).lastPathComponent as NSString).deletingPathExtension

                let flutterKey = FlutterDartProject.lookupKey(forAsset: assetPath)
                guard let bundlePath = Bundle.main.path(forResource: flutterKey, ofType: nil) else {
                    print("❌ reference image bundle path not found: \(assetPath)")
                    continue
                }

                guard let image = UIImage(contentsOfFile: bundlePath),
                      let cg = image.cgImage else {
                    print("❌ failed to load reference image: \(bundlePath)")
                    continue
                }

                let referenceImage = ARReferenceImage(cg, orientation: .up, physicalWidth: 0.5)
                referenceImage.name = imageName
                referenceImages.append(referenceImage)
            }

            let configuration = ARWorldTrackingConfiguration()
            configuration.detectionImages = Set(referenceImages)
            configuration.maximumNumberOfTrackedImages = 1

            // Run the session on main thread
            DispatchQueue.main.async {
                self.session.run(configuration, options: [.resetTracking, .removeExistingAnchors])

                if !self.wasCameraInitialized {
                    self.onRecognitionStarted()
                    self.wasCameraInitialized = true
                } else {
                    self.onRecognitionResumed()
                }

                self.isResettingTracking = false
            }
        }
    }
}

extension ARQuidoViewController: ARSCNViewDelegate {
    
    func renderer(_ renderer: SCNSceneRenderer, didAdd node: SCNNode, for anchor: ARAnchor) {
        guard let imageAnchor = anchor as? ARImageAnchor else { return }

        let referenceImage = imageAnchor.referenceImage
        let imageName = referenceImage.name ?? ""

        // Notify Flutter on main (UI-safe)
        DispatchQueue.main.async {
            self.onDetect(imageKey: imageName)
        }

        // Prevent duplicate nodes piling up
        if detectedImageNode != nil {
            return
        }

        // Resolve resources off-main (fast I/O only)
        DispatchQueue.global(qos: .userInitiated).async {
            // Video case
            if imageName == "hr-6" || imageName == "st-11" {
                let asset = (imageName == "hr-6") ? "assets/video/hr-6.mp4" : "assets/video/st-11.mp4"
                let flutterKey = FlutterDartProject.lookupKey(forAsset: asset)
                let videoPath = Bundle.main.path(forResource: flutterKey, ofType: nil)

                DispatchQueue.main.async {
                    // Clean up any previous content
                    self.cleanupDetectedContent()

                    guard let videoPath, FileManager.default.fileExists(atPath: videoPath) else {
                        print("❌ video not found: \(asset)")
                        return
                    }

                    let videoURL = URL(fileURLWithPath: videoPath)

                    // Create player + sprite content on main
                    let player = AVPlayer(url: videoURL)
                    player.isMuted = true

                    let videoNode = SKVideoNode(avPlayer: player)
                    // Fix upside-down video
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

                    node.addChildNode(planeNode)
                    self.detectedImageNode = planeNode

                    // Keep strong refs
                    self.activePlayer = player
                    self.activeVideoNode = videoNode
                    self.activeVideoScene = skScene

                    player.play()
                    videoNode.play()
                }

                return
            }

            // Image case
            let imagePath = self.imageAssetPath(for: imageName)

            DispatchQueue.main.async {
                // Clean up any previous content
                self.cleanupDetectedContent()

                guard let imagePath,
                      let image = UIImage(contentsOfFile: imagePath) else {
                    print("❌ image asset not found for \(imageName)")
                    return
                }

                let plane = SCNPlane(width: referenceImage.physicalSize.width,
                                     height: referenceImage.physicalSize.height)

                let material = SCNMaterial()
                material.diffuse.contents = image
                material.isDoubleSided = true
                material.lightingModel = .physicallyBased
                plane.materials = [material]

                let planeNode = SCNNode(geometry: plane)
                planeNode.name = imageName
                planeNode.eulerAngles.x = -.pi / 2

                node.addChildNode(planeNode)
                self.detectedImageNode = planeNode
            }
        }
    }
    
    var imageHighlightAction: SCNAction {
        return .repeatForever(
            .sequence([
                .wait(duration: 0.25),
                .fadeOpacity(to: 0.85, duration: 0.3),
                .fadeOpacity(to: 0.15, duration: 0.3),
            ])
        )
    }
    
    private func cleanupDetectedContent() {
        if let nodeToRemove = detectedImageNode {
            nodeToRemove.removeFromParentNode()
            detectedImageNode = nil
        }

        // Stop video resources
        activeVideoNode?.pause()
        activePlayer?.pause()
        activeVideoNode = nil
        activePlayer = nil
        activeVideoScene = nil
    }
}

extension ARQuidoViewController: ARSessionDelegate {
    func session(_ session: ARSession, didFailWithError error: Error) {
        guard error is ARError else { return }
        
        let errorWithInfo = error as NSError
        let messages = [
            errorWithInfo.localizedDescription,
            errorWithInfo.localizedFailureReason,
            errorWithInfo.localizedRecoverySuggestion
        ]
        
        let errorMessage = messages.compactMap({ $0 }).joined(separator: "\n")
        
        DispatchQueue.main.async {
            self.displayErrorMessage(title: "The AR session failed.", message: errorMessage)
        }
    }
    
    func sessionInterruptionEnded(_ session: ARSession) {
        restartExperience()
    }
    
    func sessionShouldAttemptRelocalization(_ session: ARSession) -> Bool {
        return true
    }
    
    // MARK: - Error handling
    
    func displayErrorMessage(title: String, message: String) {
        let alertController = UIAlertController(title: title, message: message, preferredStyle: .alert)
        let restartAction = UIAlertAction(title: "Restart Session", style: .default) { _ in
            alertController.dismiss(animated: true, completion: nil)
            self.resetTracking()
        }
        alertController.addAction(restartAction)
        present(alertController, animated: true, completion: nil)
    }
    
    // MARK: - Interface Actions
    
    func restartExperience() {
        guard isRestartAvailable else { return }
        isRestartAvailable = false
        resetTracking()
        DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) {
            self.isRestartAvailable = true
        }
    }
}

// MARK: PlatformView interface implementation

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
        guard let camera = AVCaptureDevice.default(for: AVMediaType.video) else {
            return
        }
        if camera.hasTorch {
            do {
                try camera.lockForConfiguration()
                camera.torchMode = shouldTurnOn ? .on : .off
                camera.unlockForConfiguration()
            } catch {
                print("Torch could not be used")
            }
        } else {
            print("Torch is not available")
        }
    }
}

extension ARQuidoViewController: ImageRecognitionDelegate {
    func onRecognitionPaused() {
        methodChannel.invokeMethod("scanner#recognitionPaused", arguments: nil)
    }
    
    func onRecognitionResumed() {
        methodChannel.invokeMethod("scanner#recognitionResumed", arguments: nil)
    }
    
    func onRecognitionStarted() {
        methodChannel.invokeMethod("scanner#start", arguments: [String:Any]())
    }
    
    func onDetect(imageKey: String) {
        methodChannel.invokeMethod("scanner#onImageDetected", arguments: ["imageName": imageKey])
    }
    
    func onDetectedImageTapped(imageKey: String) {
        methodChannel.invokeMethod("scanner#onDetectedImageTapped", arguments: ["imageName": imageKey])
    }
}

extension ARQuidoViewController {
    /// 이미지 경로
    func imageAssetPath(for imageName: String) -> String? {
        if let cached = resolvedImagePathCache[imageName] {
            return cached
        }
        let base = "assets/images/marker_images"

        // 모든 가능한 디렉토리 후보
        let directories: [String] = [
            "\(base)/hm",
            "\(base)/hr",
            "\(base)/st/1",
            "\(base)/st/2",
            "\(base)/st/3",
            "\(base)/2022/1",
            "\(base)/2022/2",
            "\(base)/2022/3",
            "\(base)/2022/4",
        ]

        for dir in directories {
            if let resolved = resolveAsset("\(dir)/\(imageName)") {
                resolvedImagePathCache[imageName] = resolved
                return resolved
            }
        }

        return nil
    }
    
    /// 에셋 생성
    private func resolveAsset(_ basePath: String) -> String? {
        let extensions = ["png", "jpg", "jpeg"]

        for ext in extensions {
            let assetPath = "\(basePath).\(ext)"
            let flutterKey = FlutterDartProject.lookupKey(forAsset: assetPath)

            if let bundlePath = Bundle.main.path(forResource: flutterKey, ofType: nil) {
                return bundlePath
            }
        }
        return nil
    }
}
