import AVFoundation
import AVKit
import Network
import SwiftUI

public struct StreamVideoAsset: Codable, Sendable {
    public let hlsUrl: URL?
    public let dashUrl: URL?
    public let posterUrl: URL?
    public let fallbackUrl: URL?
    public let deliveryStatus: String
    public let processingProgress: Int
}

public struct StreamVideoPreferences: Sendable {
    public var autoplay = true
    public var autoplayOnMetered = false
    public var reduceDataOnMetered = true

    public init(
        autoplay: Bool = true,
        autoplayOnMetered: Bool = false,
        reduceDataOnMetered: Bool = true
    ) {
        self.autoplay = autoplay
        self.autoplayOnMetered = autoplayOnMetered
        self.reduceDataOnMetered = reduceDataOnMetered
    }
}

/// Native HLS adapter for the same feed payload Android consumes. AVPlayer owns
/// adaptive rendition selection; this controller only supplies policy ceilings.
@MainActor
public final class StreamVideoController: ObservableObject {
    public let player: AVPlayer
    private let item: AVPlayerItem
    private let preferences: StreamVideoPreferences
    private let monitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "dev.readthat.sdui.video-network")

    public init?(asset: StreamVideoAsset, preferences: StreamVideoPreferences = .init()) {
        guard let url = asset.hlsUrl ?? asset.fallbackUrl else { return nil }
        let asset = AVURLAsset(
            url: url,
            options: [
                AVURLAssetAllowsCellularAccessKey: true,
                AVURLAssetAllowsExpensiveNetworkAccessKey: true,
                AVURLAssetAllowsConstrainedNetworkAccessKey: true,
            ]
        )
        self.item = AVPlayerItem(asset: asset)
        self.player = AVPlayer(playerItem: item)
        self.preferences = preferences
        player.automaticallyWaitsToMinimizeStalling = true
        player.preventsDisplaySleepDuringVideoPlayback = false
        item.canUseNetworkResourcesForLiveStreamingWhilePaused = false
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor [weak self] in self?.apply(path: path) }
        }
        monitor.start(queue: monitorQueue)
    }

    deinit { monitor.cancel() }

    private func apply(path: NWPath) {
        let constrained: Bool
        if #available(iOS 13.0, *) { constrained = path.isConstrained } else { constrained = false }
        let metered = path.isExpensive || constrained
        item.preferredPeakBitRate = metered && preferences.reduceDataOnMetered ? 1_500_000 : 8_000_000
        item.preferredMaximumResolution = metered && preferences.reduceDataOnMetered
            ? CGSize(width: 854, height: 480)
            : CGSize(width: 1920, height: 1080)
        item.preferredForwardBufferDuration = metered ? 15 : 45
        let mayAutoplay = preferences.autoplay && path.status == .satisfied &&
            (!metered || preferences.autoplayOnMetered)
        if mayAutoplay { player.play() } else { player.pause() }
    }
}

public struct StreamVideoView: View {
    @StateObject private var controller: StreamVideoController

    public init?(asset: StreamVideoAsset, preferences: StreamVideoPreferences = .init()) {
        guard let controller = StreamVideoController(asset: asset, preferences: preferences) else { return nil }
        _controller = StateObject(wrappedValue: controller)
    }

    public var body: some View {
        VideoPlayer(player: controller.player)
            .onDisappear { controller.player.pause() }
    }
}
