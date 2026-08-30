import AVFoundation
import Foundation

/// Persistent HLS downloads use AVFoundation's asset download stack. URLCache
/// is intentionally not used for manifests or segments: it cannot represent a
/// playable offline HLS package and dynamic manifests must be revalidated.
public final class StreamAssetDownloadManager: NSObject, @unchecked Sendable {
    public typealias Completion = @Sendable (Result<URL, Error>) -> Void

    private let identifier: String
    private let lock = NSLock()
    private var completions: [Int: Completion] = [:]
    private lazy var session: AVAssetDownloadURLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: identifier)
        configuration.waitsForConnectivity = true
        configuration.allowsCellularAccess = false
        configuration.allowsExpensiveNetworkAccess = false
        configuration.allowsConstrainedNetworkAccess = false
        return AVAssetDownloadURLSession(
            configuration: configuration,
            assetDownloadDelegate: self,
            delegateQueue: OperationQueue()
        )
    }()

    public init(identifier: String = "dev.readthat.sdui.stream-downloads") {
        self.identifier = identifier
        super.init()
    }

    @discardableResult
    public func download(
        hlsURL: URL,
        title: String,
        minimumBitrate: Double = 1_000_000,
        completion: @escaping Completion
    ) -> Int? {
        let asset = AVURLAsset(url: hlsURL)
        let task = session.makeAssetDownloadTask(
            asset: asset,
            assetTitle: title,
            assetArtworkData: nil,
            options: [AVAssetDownloadTaskMinimumRequiredMediaBitrateKey: minimumBitrate]
        )
        guard let task else { return nil }
        lock.withLock { completions[task.taskIdentifier] = completion }
        task.resume()
        return task.taskIdentifier
    }

    public func cancel(taskIdentifier: Int) {
        session.getAllTasks { tasks in
            tasks.first { $0.taskIdentifier == taskIdentifier }?.cancel()
        }
    }
}

extension StreamAssetDownloadManager: AVAssetDownloadDelegate {
    public func urlSession(
        _ session: URLSession,
        assetDownloadTask: AVAssetDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        let completion = lock.withLock { completions.removeValue(forKey: assetDownloadTask.taskIdentifier) }
        completion?(.success(location))
    }

    public func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        guard let error else { return }
        let completion = lock.withLock { completions.removeValue(forKey: task.taskIdentifier) }
        completion?(.failure(error))
    }
}
