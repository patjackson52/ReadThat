import Foundation
import Network

public struct UnifiedNetworkResponse: Sendable {
    public let status: Int
    public let headers: [String: String]
    public let body: Data
}

public struct UnifiedNetworkPolicy: Sendable {
    public var allowsExpensiveAccess: Bool
    public var allowsConstrainedAccess: Bool
    public var knownHTTP3Origin: Bool

    public init(
        allowsExpensiveAccess: Bool = true,
        allowsConstrainedAccess: Bool = true,
        knownHTTP3Origin: Bool = false
    ) {
        self.allowsExpensiveAccess = allowsExpensiveAccess
        self.allowsConstrainedAccess = allowsConstrainedAccess
        self.knownHTTP3Origin = knownHTTP3Origin
    }

    public static let api = UnifiedNetworkPolicy(knownHTTP3Origin: true)
    public static let discretionaryImage = UnifiedNetworkPolicy(
        allowsExpensiveAccess: false,
        allowsConstrainedAccess: false
    )
}

/// One long-lived URLSession for API and image traffic. URLSession owns pooled
/// TLS/HTTP-2/HTTP-3 connections and adapts them across path changes. AVPlayer
/// uses AVFoundation's native HLS loader, which independently gets the same
/// Network.framework HTTP/3 and connection-migration behavior.
public final class UnifiedNetworkClient: @unchecked Sendable {
    public static let shared = UnifiedNetworkClient()

    private let delegate = UnifiedNetworkDelegate()
    private let session: URLSession
    private let imageMemoryCache = NSCache<NSString, NSData>()

    public var lastNegotiatedProtocol: String? { delegate.lastProtocol }

    public init(cacheBytes: Int? = nil) {
        let physicalMemory = ProcessInfo.processInfo.physicalMemory
        let defaultDiskBytes: Int
        switch physicalMemory {
        case ..<(3 * 1_024 * 1_024 * 1_024): defaultDiskBytes = 64 * 1_024 * 1_024
        case ..<(6 * 1_024 * 1_024 * 1_024): defaultDiskBytes = 192 * 1_024 * 1_024
        default: defaultDiskBytes = 384 * 1_024 * 1_024
        }

        let configuration = URLSessionConfiguration.default
        configuration.waitsForConnectivity = true
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 120
        configuration.requestCachePolicy = .useProtocolCachePolicy
        configuration.urlCache = URLCache(
            memoryCapacity: 32 * 1_024 * 1_024,
            diskCapacity: cacheBytes ?? defaultDiskBytes,
            diskPath: "dev.readthat.sdui.http-cache"
        )
        configuration.allowsCellularAccess = true
        configuration.allowsExpensiveNetworkAccess = true
        configuration.allowsConstrainedNetworkAccess = true
        configuration.tlsMinimumSupportedProtocolVersion = .TLSv12
        // Do not enable MPTCP handover here: it requires a special entitlement
        // and server support. QUIC connection migration handles HTTP/3 path
        // changes without holding a second, possibly metered, interface open.
        configuration.multipathServiceType = .none
        if #available(iOS 26.0, *) {
            // Mutations share this session, so replayable TLS 0-RTT stays off.
            configuration.enablesEarlyData = false
        }
        imageMemoryCache.totalCostLimit = 32 * 1_024 * 1_024
        imageMemoryCache.countLimit = 128
        session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
    }

    public func request(
        _ url: URL,
        method: String = "GET",
        headers: [String: String] = [:],
        body: Data? = nil,
        policy: UnifiedNetworkPolicy = .api,
        cachePolicy: URLRequest.CachePolicy = .reloadIgnoringLocalCacheData
    ) async throws -> UnifiedNetworkResponse {
        guard url.scheme?.lowercased() == "https" else {
            throw URLError(.appTransportSecurityRequiresSecureConnection)
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        request.allHTTPHeaderFields = headers
        // Structured API data is cached by the app's database. URLCache is
        // reserved for image bytes so authenticated JSON is never duplicated
        // or accidentally reused across accounts.
        request.cachePolicy = cachePolicy
        request.allowsExpensiveNetworkAccess = policy.allowsExpensiveAccess
        request.allowsConstrainedNetworkAccess = policy.allowsConstrainedAccess
        request.assumesHTTP3Capable = policy.knownHTTP3Origin

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        let responseHeaders = http.allHeaderFields.reduce(into: [String: String]()) { result, entry in
            result[String(describing: entry.key).lowercased()] = String(describing: entry.value)
        }
        return UnifiedNetworkResponse(status: http.statusCode, headers: responseHeaders, body: data)
    }

    public func imageData(
        from url: URL,
        permitMeteredNetwork: Bool,
        knownHTTP3Origin: Bool = false,
        cacheKey: String? = nil
    ) async throws -> Data {
        // Prefer the server's immutable media/version id. Signed delivery URLs
        // rotate, but decoded bytes should retain one L1 identity across refresh.
        let memoryKey = (cacheKey ?? url.absoluteString) as NSString
        if let cached = imageMemoryCache.object(forKey: memoryKey) {
            return cached as Data
        }
        let response = try await request(
            url,
            policy: UnifiedNetworkPolicy(
                allowsExpensiveAccess: permitMeteredNetwork,
                allowsConstrainedAccess: permitMeteredNetwork,
                knownHTTP3Origin: knownHTTP3Origin
            ),
            cachePolicy: .useProtocolCachePolicy
        )
        guard (200..<300).contains(response.status) else { throw URLError(.badServerResponse) }
        imageMemoryCache.setObject(response.body as NSData, forKey: memoryKey, cost: response.body.count)
        return response.body
    }

    public func handleMemoryWarning() {
        imageMemoryCache.removeAllObjects()
    }
}

private final class UnifiedNetworkDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var protocolStorage: String?
    var lastProtocol: String? { lock.withLock { protocolStorage } }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didFinishCollecting metrics: URLSessionTaskMetrics
    ) {
        let value = metrics.transactionMetrics.last?.networkProtocolName
        lock.withLock { protocolStorage = value }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        guard request.url?.scheme?.lowercased() == "https" else {
            completionHandler(nil)
            return
        }
        let hadAuthorization = task.originalRequest?.value(forHTTPHeaderField: "Authorization") != nil
        let crossedHost = task.originalRequest?.url?.host != request.url?.host
        completionHandler(hadAuthorization && crossedHost ? nil : request)
    }
}
