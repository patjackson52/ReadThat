import UIKit

/// Native counterpart to Android JankStats. Install once in the app shell and
/// update `surface` from navigation. It batches frames instead of exporting on
/// every display-link callback.
@MainActor
public final class PerformanceFrameMonitor {
    public var surface = "APP"

    private var displayLink: CADisplayLink?
    private var priorTimestamp: CFTimeInterval?
    private var durations: [Double] = []
    private var jankCount = 0
    private var slowFrameCount = 0
    private var frozenFrameCount = 0
    private var observerTokens: [NSObjectProtocol] = []

    public init() {}

    public func start() {
        guard displayLink == nil else { return }
        let center = NotificationCenter.default
        observerTokens = [
            center.addObserver(
                forName: UIApplication.didEnterBackgroundNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                Task { @MainActor in
                    self?.flush()
                    await PerformanceTelemetryClient.shared.flush()
                }
            },
            center.addObserver(
                forName: UIApplication.didReceiveMemoryWarningNotification,
                object: nil,
                queue: .main
            ) { _ in
                Task { await PerformanceTelemetryClient.shared.flush() }
            },
        ]
        let link = CADisplayLink(target: self, selector: #selector(onFrame(_:)))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    public func stop() {
        flush()
        displayLink?.invalidate()
        displayLink = nil
        observerTokens.forEach(NotificationCenter.default.removeObserver)
        observerTokens.removeAll()
    }

    public func flush() {
        guard !durations.isEmpty else { return }
        let sorted = durations.sorted()
        let p95Index = min(sorted.count - 1, max(0, Int(ceil(Double(sorted.count) * 0.95)) - 1))
        let average = durations.reduce(0, +) / Double(durations.count)
        let event = ClientPerformanceEvent(
            name: "screen_frame_summary",
            value: sorted[p95Index],
            surface: surface,
            measurements: [
                "frame_count": Double(durations.count),
                "jank_count": Double(jankCount),
                "slow_frame_count": Double(slowFrameCount),
                "frozen_frame_count": Double(frozenFrameCount),
                "fps": min(240, 1_000 / average),
            ]
        )
        reset()
        Task { await PerformanceTelemetryClient.shared.record(event) }
    }

    @objc private func onFrame(_ link: CADisplayLink) {
        defer { priorTimestamp = link.timestamp }
        guard let priorTimestamp else { return }
        let durationMs = (link.timestamp - priorTimestamp) * 1_000
        let budgetMs = max(1, (link.targetTimestamp - link.timestamp) * 1_000)
        durations.append(durationMs)
        if durationMs > budgetMs * 2 { jankCount += 1 }
        if durationMs > 16.67 { slowFrameCount += 1 }
        if durationMs > 700 { frozenFrameCount += 1 }
        if durations.count >= 300 { flush() }
    }

    private func reset() {
        durations.removeAll(keepingCapacity: true)
        jankCount = 0
        slowFrameCount = 0
        frozenFrameCount = 0
    }

    deinit {
        displayLink?.invalidate()
        observerTokens.forEach(NotificationCenter.default.removeObserver)
    }
}
