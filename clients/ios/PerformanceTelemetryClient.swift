import Foundation

public struct ClientPerformanceEvent: Codable, Sendable {
    public let name: String
    public let value: Double
    public let unit: String
    public let surface: String
    public let outcome: String
    public let recordedAtEpochMs: Int64
    public let attributes: [String: String]
    public let measurements: [String: Double]

    public init(
        name: String,
        value: Double,
        unit: String = "MILLISECOND",
        surface: String,
        outcome: String = "SUCCESS",
        attributes: [String: String] = [:],
        measurements: [String: Double] = [:]
    ) {
        self.name = name
        self.value = value
        self.unit = unit
        self.surface = surface
        self.outcome = outcome
        self.recordedAtEpochMs = Int64(Date().timeIntervalSince1970 * 1_000)
        self.attributes = attributes
        self.measurements = measurements
    }
}

public struct ClientPerformanceTimer: Sendable {
    private let started = DispatchTime.now().uptimeNanoseconds
    public init() {}
    public func elapsedMilliseconds() -> Double {
        Double(DispatchTime.now().uptimeNanoseconds - started) / 1_000_000
    }
}

/// iOS exporter for the KMP-compatible envelope. The actor is L1; a bounded
/// Application Support JSON file is L2 for process death and spotty networks.
public actor PerformanceTelemetryClient {
    public static let shared = PerformanceTelemetryClient()

    private var events: [ClientPerformanceEvent]
    private var configuration: (endpoint: URL, appVersion: String, buildType: String)?
    private var scheduledFlush: Task<Void, Never>?
    private var isFlushing = false
    private let sessionID = UUID().uuidString.lowercased()
    private let fileURL: URL

    public init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let directory = support.appendingPathComponent("dev.readthat.sdui", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let outboxURL = directory.appendingPathComponent("performance-outbox-v1.json")
        fileURL = outboxURL
        events = (try? Data(contentsOf: outboxURL))
            .flatMap { try? JSONDecoder().decode([ClientPerformanceEvent].self, from: $0) } ?? []
    }

    public func configure(endpoint: URL, appVersion: String, buildType: String = "release") {
        configuration = (endpoint, appVersion, buildType)
    }

    public func record(_ event: ClientPerformanceEvent) async {
        guard event.value.isFinite, event.value >= 0 else { return }
        events.append(event)
        if events.count > 1_000 { events.removeFirst(events.count - 1_000) }
        persist()
        if events.count >= 20 { await flush() }
        else { scheduleFlush(afterNanoseconds: 5_000_000_000) }
    }

    public func record(
        name: String,
        timer: ClientPerformanceTimer,
        surface: String,
        attributes: [String: String] = [:]
    ) async {
        await record(ClientPerformanceEvent(
            name: name,
            value: timer.elapsedMilliseconds(),
            surface: surface,
            attributes: attributes
        ))
    }

    public func flush() async {
        guard let configuration, !events.isEmpty, !isFlushing else { return }
        isFlushing = true
        defer { isFlushing = false }
        let count = min(events.count, 50)
        let selected = Array(events.prefix(count))
        let envelope = PerformanceEnvelope(
            platform: "ios",
            appVersion: configuration.appVersion,
            buildType: configuration.buildType,
            sessionId: sessionID,
            events: selected
        )
        guard let body = try? JSONEncoder().encode(envelope) else { return }
        do {
            let response = try await UnifiedNetworkClient.shared.request(
                configuration.endpoint,
                method: "POST",
                headers: ["content-type": "application/json"],
                body: body,
                policy: .api
            )
            guard response.status == 202 else { return }
            events.removeFirst(count)
            persist()
            if !events.isEmpty { scheduleFlush(afterNanoseconds: 1_000_000) }
        } catch {
            // BackgroundTasks and the next foreground activation retry the L2 queue.
            scheduleFlush(afterNanoseconds: 30_000_000_000)
        }
    }

    private func scheduleFlush(afterNanoseconds delay: UInt64) {
        guard scheduledFlush == nil else { return }
        scheduledFlush = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled else { return }
            await self?.runScheduledFlush()
        }
    }

    private func runScheduledFlush() async {
        scheduledFlush = nil
        await flush()
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(events) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }
}

private struct PerformanceEnvelope: Encodable {
    let schemaVersion = 1
    let platform: String
    let appVersion: String
    let buildType: String
    let sessionId: String
    let events: [ClientPerformanceEvent]
}
