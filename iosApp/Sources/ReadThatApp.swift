import ReadThatShared
import SwiftUI
@preconcurrency import PhotosUI
import UniformTypeIdentifiers
import AVFoundation
import ImageIO
import UIKit
@preconcurrency import BackgroundTasks

private enum ReadThatConfiguration {
    static func string(_ key: String) -> String {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    static func boolean(_ key: String) -> Bool {
        let value = Bundle.main.object(forInfoDictionaryKey: key)
        if let number = value as? NSNumber { return number.boolValue }
        guard let text = value as? String else { return false }
        return ["1", "true", "yes"].contains(text.lowercased())
    }

    static var buildType: String {
        #if DEBUG
        "debug"
        #else
        "release"
        #endif
    }
}

private final class ReadThatBackgroundSyncCoordinator {
    private static let identifier = "dev.readthat.ios.pending-mutations"
    private static let refreshInterval: TimeInterval = 60 * 60
    private let graph: IosReadThatGraph

    init(graph: IosReadThatGraph) {
        self.graph = graph
    }

    func register() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.identifier,
            using: .main
        ) { [weak self] task in
            guard let self, let processingTask = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            self.handle(processingTask)
        }
    }

    /// Mutation commits request the earliest available run; lifecycle maintenance remains hourly.
    func schedule(urgent: Bool = false) {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.identifier)
        let request = BGProcessingTaskRequest(identifier: Self.identifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        if !urgent {
            request.earliestBeginDate = Date(timeIntervalSinceNow: Self.refreshInterval)
        }
        try? BGTaskScheduler.shared.submit(request)
    }

    private func handle(_ task: BGProcessingTask) {
        schedule()
        task.expirationHandler = { [weak self] in self?.graph.cancelPendingMutationSync() }
        graph.runBackgroundMaintenance { succeeded in
            task.setTaskCompleted(success: succeeded.boolValue)
        }
    }
}

/**
 * Native timing source only. KMP owns frame classification, batching, surface attribution,
 * durable Room storage, privacy filtering, and export through the process-wide HTTP client.
 */
@MainActor
private final class ReadThatFrameMonitor: NSObject {
    private let graph: IosReadThatGraph
    private var displayLink: CADisplayLink?
    private var previousTimestamp: CFTimeInterval?

    init(graph: IosReadThatGraph) {
        self.graph = graph
    }

    func start() {
        guard displayLink == nil else { return }
        previousTimestamp = nil
        let link = CADisplayLink(target: self, selector: #selector(onFrame(_:)))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    func stop() {
        graph.flushFrameHealth()
        displayLink?.invalidate()
        displayLink = nil
        previousTimestamp = nil
    }

    func flush() {
        graph.flushFrameHealth()
    }

    @objc private func onFrame(_ link: CADisplayLink) {
        defer { previousTimestamp = link.timestamp }
        guard let previousTimestamp else { return }
        let durationMillis = (link.timestamp - previousTimestamp) * 1_000
        let frameBudgetMillis = max(1, (link.targetTimestamp - link.timestamp) * 1_000)
        graph.recordFramePresentation(
            durationMillis: durationMillis,
            frameBudgetMillis: frameBudgetMillis
        )
    }
}

@main
struct ReadThatIOSApp: App {
    private let deepLinks: DeepLinkInbox
    private let backGestures: PlatformBackGestureBridge
    private let graph: IosReadThatGraph
    private let backgroundSync: ReadThatBackgroundSyncCoordinator
    private let frameMonitor: ReadThatFrameMonitor

    init() {
        let graph = IosReadThatGraph(
            baseUrl: ReadThatConfiguration.string("READTHAT_API_BASE_URL"),
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0",
            demoUsername: ReadThatConfiguration.string("READTHAT_DEMO_USERNAME"),
            demoPassword: ReadThatConfiguration.string("READTHAT_DEMO_PASSWORD"),
            allowLocalDevelopmentHttp: ReadThatConfiguration.boolean("READTHAT_ALLOW_LOCAL_HTTP"),
            buildType: ReadThatConfiguration.buildType
        )
        let backgroundSync = ReadThatBackgroundSyncCoordinator(graph: graph)
        self.deepLinks = DeepLinkInbox()
        self.backGestures = PlatformBackGestureBridge()
        self.graph = graph
        self.backgroundSync = backgroundSync
        self.frameMonitor = ReadThatFrameMonitor(graph: graph)
        backgroundSync.register()
    }

    var body: some Scene {
        WindowGroup {
            ReadThatHostView(
                deepLinks: deepLinks,
                backGestures: backGestures,
                graph: graph,
                backgroundSync: backgroundSync,
                frameMonitor: frameMonitor
            )
        }
    }
}

private struct ReadThatRootView: UIViewControllerRepresentable {
    let deepLinks: DeepLinkInbox
    let backGestures: PlatformBackGestureBridge
    let graph: IosReadThatGraph
    let onCreationQueued: () -> Void
    let onCommunityVisitQueued: () -> Void
    let onCommunityMembershipQueued: () -> Void
    let initialNavigationState: String
    let onNavigationStateChanged: (String) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(backGestures: backGestures)
    }

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.MainViewController(
            graph: graph,
            deepLinks: deepLinks,
            backGestures: backGestures,
            onCreationQueued: onCreationQueued,
            onCommunityVisitQueued: onCommunityVisitQueued,
            onCommunityMembershipQueued: onCommunityMembershipQueued,
            initialNavigationState: initialNavigationState,
            onNavigationStateChanged: onNavigationStateChanged
        )
        context.coordinator.install(on: controller.view)
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    static func dismantleUIViewController(
        _ uiViewController: UIViewController,
        coordinator: Coordinator
    ) {
        coordinator.uninstall()
    }

    @MainActor
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        private let backGestures: PlatformBackGestureBridge
        private weak var installedView: UIView?
        private var edgePan: UIScreenEdgePanGestureRecognizer?

        init(backGestures: PlatformBackGestureBridge) {
            self.backGestures = backGestures
        }

        func install(on view: UIView) {
            guard edgePan == nil else { return }
            let recognizer = UIScreenEdgePanGestureRecognizer(
                target: self,
                action: #selector(handleEdgePan(_:))
            )
            recognizer.edges = .left
            recognizer.cancelsTouchesInView = false
            recognizer.delaysTouchesBegan = false
            recognizer.delegate = self
            view.addGestureRecognizer(recognizer)
            installedView = view
            edgePan = recognizer
        }

        func uninstall() {
            if let edgePan { installedView?.removeGestureRecognizer(edgePan) }
            edgePan = nil
            installedView = nil
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard backGestures.isEnabled,
                  let pan = gestureRecognizer as? UIScreenEdgePanGestureRecognizer else {
                return false
            }
            let velocity = pan.velocity(in: installedView)
            return velocity.x > 0 && abs(velocity.x) > abs(velocity.y)
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            true
        }

        @objc private func handleEdgePan(_ recognizer: UIScreenEdgePanGestureRecognizer) {
            guard recognizer.state == .ended, let view = installedView else { return }
            let translation = recognizer.translation(in: view)
            let completionDistance = max(48, view.bounds.width * 0.12)
            guard translation.x >= completionDistance else { return }
            _ = backGestures.request()
        }
    }
}

private struct ReadThatHostView: View {
    let deepLinks: DeepLinkInbox
    let backGestures: PlatformBackGestureBridge
    let graph: IosReadThatGraph
    let backgroundSync: ReadThatBackgroundSyncCoordinator
    let frameMonitor: ReadThatFrameMonitor
    @State private var pickerRequest: MediaPickerRequest?
    @SceneStorage("readthat.navigation.v1") private var navigationState = ""

    var body: some View {
        ReadThatRootView(
            deepLinks: deepLinks,
            backGestures: backGestures,
            graph: graph,
            onCreationQueued: { backgroundSync.schedule(urgent: true) },
            onCommunityVisitQueued: { backgroundSync.schedule(urgent: true) },
            onCommunityMembershipQueued: { backgroundSync.schedule(urgent: true) },
            initialNavigationState: navigationState,
            onNavigationStateChanged: { navigationState = $0 }
        )
            // Compose owns safe-drawing insets. Let its controller fill the window so
            // SwiftUI does not apply the same top and bottom safe areas a second time.
            .ignoresSafeArea(.container)
            .ignoresSafeArea(.keyboard)
            .onAppear { frameMonitor.start() }
            .onDisappear { frameMonitor.stop() }
            .onReceive(NotificationCenter.default.publisher(
                for: UIApplication.didBecomeActiveNotification
            )) { _ in
                frameMonitor.start()
            }
            .onOpenURL { url in
                _ = deepLinks.offerUrl(url: url.absoluteString)
            }
            .onReceive(NotificationCenter.default.publisher(
                for: UIApplication.didEnterBackgroundNotification
            )) { _ in
                frameMonitor.stop()
                // Revalidate Room and warm a bounded preview window even when there are no new
                // mutations. The task reschedules itself after every OS-granted execution.
                backgroundSync.schedule()
            }
            .onReceive(NotificationCenter.default.publisher(
                for: UIApplication.didReceiveMemoryWarningNotification
            )) { _ in
                frameMonitor.flush()
            }
            .onReceive(NotificationCenter.default.publisher(for: .readThatPickMedia)) { notification in
                let identifier = notification.userInfo?["kind"] as? String ?? ""
                let requestId = notification.userInfo?["requestId"] as? String ?? UUID().uuidString
                guard let policy = MediaAcquisitionPolicies.shared.forIdentifier(identifier: identifier) else {
                    NotificationCenter.default.post(
                        name: .readThatMediaPickerFinished,
                        object: nil,
                        userInfo: ["requestId": requestId, "error": "Unsupported media picker request."]
                    )
                    return
                }
                let requestPolicy = NativeMediaPolicy(policy)
                if requestPolicy.isCamera && !UIImagePickerController.isSourceTypeAvailable(.camera) {
                    NotificationCenter.default.post(
                        name: .readThatMediaPickerFinished,
                        object: nil,
                        userInfo: [
                            "requestId": requestId,
                            "error": "The camera is unavailable on this device.",
                        ]
                    )
                } else {
                    pickerRequest = MediaPickerRequest(policy: requestPolicy, requestId: requestId)
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: .readThatShare)) { notification in
                guard let text = notification.userInfo?["text"] as? String,
                      let presenter = UIApplication.shared.readThatTopViewController else { return }
                let subject = (notification.userInfo?["subject"] as? String)?.nilIfEmpty
                let mimeType = notification.userInfo?["mimeType"] as? String ?? "text/plain"
                let activity = UIActivityViewController(
                    activityItems: [ReadThatShareItem(text: text, subject: subject, mimeType: mimeType)],
                    applicationActivities: nil
                )
                activity.popoverPresentationController?.sourceView = presenter.view
                presenter.present(activity, animated: true)
            }
            .sheet(item: $pickerRequest) { request in
                if request.policy.isCamera {
                    ReadThatCameraPicker(policy: request.policy) { result in finish(request, with: result) }
                } else {
                    ReadThatMediaPicker(policy: request.policy) { result in finish(request, with: result) }
                }
            }
    }

    private func finish(_ request: MediaPickerRequest, with result: Result<[StagedMedia], Error>) {
        switch result {
        case .success(let items):
            items.forEach { item in
                var userInfo = item.userInfo
                userInfo["requestId"] = request.requestId
                NotificationCenter.default.post(
                    name: .readThatMediaPicked,
                    object: nil,
                    userInfo: userInfo
                )
            }
            NotificationCenter.default.post(
                name: .readThatMediaPickerFinished,
                object: nil,
                userInfo: ["requestId": request.requestId]
            )
        case .failure(let error):
            NotificationCenter.default.post(
                name: .readThatMediaPickerFinished,
                object: nil,
                userInfo: ["requestId": request.requestId, "error": error.localizedDescription]
            )
        }
        pickerRequest = nil
    }
}

private struct MediaPickerRequest: Identifiable {
    let id = UUID()
    let policy: NativeMediaPolicy
    let requestId: String
}

/** Sendable value snapshot of the exported KMP policy used by native PhotosUI/AVFoundation work. */
private struct NativeMediaPolicy: Sendable {
    let identifier: String
    let isVideo: Bool
    let isCamera: Bool
    let maximumItems: Int
    let maximumBytesPerItem: Int64
    let maximumPixelDimension: Int?
    let defaultMimeType: String
    let defaultFileExtension: String
    let tooLargeMessage: String
    let maximumDimensionMessage: String?

    init(_ policy: MediaAcquisitionPolicy) {
        let policies = MediaAcquisitionPolicies.shared
        identifier = policy.identifier
        isVideo = policy.identifier == policies.video.identifier
        isCamera = policy.identifier == policies.camera.identifier
        maximumItems = Int(policy.maximumItems)
        maximumBytesPerItem = policy.maximumBytesPerItem
        maximumPixelDimension = policy.maximumPixelDimension.map { Int($0.intValue) }
        defaultMimeType = policy.defaultMimeType
        defaultFileExtension = policy.defaultFileExtension
        tooLargeMessage = policy.tooLargeMessage
        maximumDimensionMessage = policy.maximumDimensionMessage
    }
}

private struct StagedMedia: Sendable {
    let path: String
    let name: String
    let mimeType: String
    let byteSize: Int64
    let width: Int?
    let height: Int?
    let durationSeconds: Int?

    var userInfo: [String: Any] {
        var value: [String: Any] = [
            "path": path, "name": name, "mimeType": mimeType, "byteSize": byteSize,
        ]
        if let width { value["width"] = width }
        if let height { value["height"] = height }
        if let durationSeconds { value["durationSeconds"] = durationSeconds }
        return value
    }
}

private struct PreparedMedia: Sendable {
    let destination: URL
    let name: String
    let mimeType: String
    let byteSize: Int64
}

private final class MediaResultCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var valuesBySelectionIndex: [Int: StagedMedia] = [:]

    func insert(_ value: StagedMedia, atSelectionIndex index: Int) {
        lock.withLock { valuesBySelectionIndex[index] = value }
    }

    /** Metadata inspection is concurrent, but gallery order must remain the user's picker order. */
    func orderedSnapshot() -> [StagedMedia] {
        lock.withLock {
            valuesBySelectionIndex.keys.sorted().compactMap { valuesBySelectionIndex[$0] }
        }
    }
}

private struct ReadThatMediaPicker: UIViewControllerRepresentable {
    let policy: NativeMediaPolicy
    let onFinish: (Result<[StagedMedia], Error>) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        configuration.filter = policy.isVideo ? .videos : .images
        configuration.selectionLimit = policy.maximumItems
        configuration.selection = .ordered
        configuration.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(policy: policy, onFinish: onFinish) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let policy: NativeMediaPolicy
        private let onFinish: (Result<[StagedMedia], Error>) -> Void

        init(policy: NativeMediaPolicy, onFinish: @escaping (Result<[StagedMedia], Error>) -> Void) {
            self.policy = policy
            self.onFinish = onFinish
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard !results.isEmpty else { onFinish(.success([])); return }
            let group = DispatchGroup()
            let collector = MediaResultCollector()
            let firstError = MediaErrorBox()
            let type = policy.isVideo ? UTType.movie.identifier : UTType.image.identifier
            let requestPolicy = policy
            results.enumerated().forEach { selectionIndex, result in
                group.enter()
                let provider = result.itemProvider
                let suggestedName = provider.suggestedName
                provider.loadFileRepresentation(forTypeIdentifier: type) { source, error in
                    do {
                        if let error { throw error }
                        guard let source else { throw MediaPickerError.unavailable }
                        // The picker-owned URL is ephemeral, so copy it before returning from
                        // this callback. Metadata loading can then use modern async AVFoundation.
                        let prepared = try Self.stageFile(
                            source: source,
                            suggestedName: suggestedName,
                            policy: requestPolicy
                        )
                        Task.detached {
                            defer { group.leave() }
                            do {
                                collector.insert(
                                    try await Self.inspect(prepared, policy: requestPolicy),
                                    atSelectionIndex: selectionIndex
                                )
                            } catch {
                                try? FileManager.default.removeItem(at: prepared.destination)
                                firstError.capture(error)
                            }
                        }
                    } catch {
                        firstError.capture(error)
                        group.leave()
                    }
                }
            }
            group.notify(queue: .main) {
                if let error = firstError.value {
                    collector.orderedSnapshot().forEach {
                        try? FileManager.default.removeItem(atPath: $0.path)
                    }
                    self.onFinish(.failure(error))
                }
                else { self.onFinish(.success(collector.orderedSnapshot())) }
            }
        }

        nonisolated private static func stageFile(
            source: URL,
            suggestedName: String?,
            policy: NativeMediaPolicy
        ) throws -> PreparedMedia {
            let manager = FileManager.default
            let support = try manager.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            ).appendingPathComponent("pending-uploads", isDirectory: true)
            try manager.createDirectory(at: support, withIntermediateDirectories: true)
            let extensionValue = source.pathExtension.isEmpty ? policy.defaultFileExtension : source.pathExtension
            let destination = support.appendingPathComponent(UUID().uuidString).appendingPathExtension(extensionValue)
            try manager.copyItem(at: source, to: destination)
            let attributes = try manager.attributesOfItem(atPath: destination.path)
            let byteSize = (attributes[.size] as? NSNumber)?.int64Value ?? 0
            guard byteSize > 0, byteSize <= policy.maximumBytesPerItem else {
                try? manager.removeItem(at: destination)
                throw MediaPickerError.message(policy.tooLargeMessage)
            }
            let mimeType = UTType(filenameExtension: extensionValue)?.preferredMIMEType
                ?? policy.defaultMimeType
            return PreparedMedia(
                destination: destination,
                name: suggestedName ?? destination.lastPathComponent,
                mimeType: mimeType,
                byteSize: byteSize
            )
        }

        nonisolated private static func inspect(
            _ prepared: PreparedMedia,
            policy: NativeMediaPolicy
        ) async throws -> StagedMedia {
            var width: Int?
            var height: Int?
            var duration: Int?
            if policy.isVideo {
                let asset = AVURLAsset(url: prepared.destination)
                if let track = try await asset.loadTracks(withMediaType: .video).first {
                    let naturalSize = try await track.load(.naturalSize)
                    let preferredTransform = try await track.load(.preferredTransform)
                    let size = naturalSize.applying(preferredTransform)
                    width = Int(abs(size.width))
                    height = Int(abs(size.height))
                }
                let loadedDuration = try await asset.load(.duration)
                let seconds = CMTimeGetSeconds(loadedDuration)
                if seconds.isFinite { duration = Int(seconds.rounded()) }
            } else if let source = CGImageSourceCreateWithURL(prepared.destination as CFURL, nil),
                      let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] {
                width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue
                height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue
            }
            if let maximum = policy.maximumPixelDimension {
                guard let width, let height, width > 0, height > 0 else {
                    throw MediaPickerError.unavailable
                }
                guard width <= maximum, height <= maximum else {
                    throw MediaPickerError.message(
                        policy.maximumDimensionMessage
                            ?? "The selected image dimensions are unsupported."
                    )
                }
            }
            return StagedMedia(
                path: prepared.destination.path,
                name: prepared.name,
                mimeType: prepared.mimeType,
                byteSize: prepared.byteSize,
                width: width,
                height: height,
                durationSeconds: duration
            )
        }
    }
}

private struct ReadThatCameraPicker: UIViewControllerRepresentable {
    let policy: NativeMediaPolicy
    let onFinish: (Result<[StagedMedia], Error>) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.cameraCaptureMode = .photo
        picker.mediaTypes = [UTType.image.identifier]
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(policy: policy, onFinish: onFinish) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        private let policy: NativeMediaPolicy
        private let onFinish: (Result<[StagedMedia], Error>) -> Void

        init(policy: NativeMediaPolicy, onFinish: @escaping (Result<[StagedMedia], Error>) -> Void) {
            self.policy = policy
            self.onFinish = onFinish
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onFinish(.success([]))
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            do {
                guard let image = info[.originalImage] as? UIImage,
                      let data = image.jpegData(compressionQuality: 0.92) else {
                    throw MediaPickerError.unavailable
                }
                guard data.count <= policy.maximumBytesPerItem else {
                    throw MediaPickerError.message(policy.tooLargeMessage)
                }
                let manager = FileManager.default
                let support = try manager.url(
                    for: .applicationSupportDirectory,
                    in: .userDomainMask,
                    appropriateFor: nil,
                    create: true
                ).appendingPathComponent("pending-uploads", isDirectory: true)
                try manager.createDirectory(at: support, withIntermediateDirectories: true)
                let destination = support.appendingPathComponent(UUID().uuidString)
                    .appendingPathExtension(policy.defaultFileExtension)
                try data.write(to: destination, options: .atomic)
                onFinish(.success([StagedMedia(
                    path: destination.path,
                    name: "Captured photo.jpg",
                    mimeType: policy.defaultMimeType,
                    byteSize: Int64(data.count),
                    width: Int(image.size.width * image.scale),
                    height: Int(image.size.height * image.scale),
                    durationSeconds: nil
                )]))
            } catch {
                onFinish(.failure(error))
            }
        }
    }
}

private final class MediaErrorBox: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: Error?
    func capture(_ error: Error) { lock.withLock { if stored == nil { stored = error } } }
    var value: Error? { lock.withLock { stored } }
}

private enum MediaPickerError: LocalizedError {
    case unavailable
    case message(String)

    var errorDescription: String? {
        switch self {
        case .unavailable:
            "The selected media is unavailable."
        case .message(let message):
            message
        }
    }
}

private final class ReadThatShareItem: NSObject, UIActivityItemSource {
    let text: String
    let subject: String?
    let mimeType: String

    init(text: String, subject: String?, mimeType: String) {
        self.text = text
        self.subject = subject
        self.mimeType = mimeType
    }

    func activityViewControllerPlaceholderItem(_ activityViewController: UIActivityViewController) -> Any {
        text
    }

    func activityViewController(
        _ activityViewController: UIActivityViewController,
        itemForActivityType activityType: UIActivity.ActivityType?
    ) -> Any? {
        text
    }

    func activityViewController(
        _ activityViewController: UIActivityViewController,
        subjectForActivityType activityType: UIActivity.ActivityType?
    ) -> String {
        subject ?? ""
    }

    func activityViewController(
        _ activityViewController: UIActivityViewController,
        dataTypeIdentifierForActivityType activityType: UIActivity.ActivityType?
    ) -> String {
        UTType(mimeType: mimeType)?.identifier ?? UTType.plainText.identifier
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

private extension Notification.Name {
    static let readThatPickMedia = Notification.Name("ReadThatPickMedia")
    static let readThatMediaPicked = Notification.Name("ReadThatMediaPicked")
    static let readThatMediaPickerFinished = Notification.Name("ReadThatMediaPickerFinished")
    static let readThatShare = Notification.Name("ReadThatShare")
}

private extension UIApplication {
    var readThatTopViewController: UIViewController? {
        let root = connectedScenes.compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController
        var current = root
        while let presented = current?.presentedViewController { current = presented }
        return current
    }
}
