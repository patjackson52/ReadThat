import BackgroundTasks
import Foundation

/// Registers one app-refresh task that revalidates database-backed repositories.
/// The refresh closure must write its results to the local database; SwiftUI
/// observes that database and never renders a network response directly.
@available(iOS 13.0, *)
public final class BackgroundRefreshCoordinator: @unchecked Sendable {
    public typealias RefreshOperation = @Sendable () async throws -> Void

    private let identifier: String
    private let earliestInterval: TimeInterval
    private let refresh: RefreshOperation

    public init(
        identifier: String,
        earliestInterval: TimeInterval = 60 * 60,
        refresh: @escaping RefreshOperation
    ) {
        self.identifier = identifier
        self.earliestInterval = earliestInterval
        self.refresh = refresh
    }

    /// Call during application launch, before finishing scene construction.
    public func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { [weak self] task in
            guard let self, let refreshTask = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            self.run(refreshTask)
        }
    }

    /// Call after registration and whenever the app enters the background.
    public func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: earliestInterval)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // The OS may already have an equivalent request. The next lifecycle
            // transition retries scheduling; foreground refresh remains intact.
        }
    }

    /// Call from the scene's active transition. The repository/database write
    /// refines already-visible cached state and is not awaited by first paint.
    public func refreshWhenActive() {
        let operation = refresh
        Task { try? await operation() }
    }

    private func run(_ task: BGAppRefreshTask) {
        schedule()
        let operation = Task {
            do {
                try await refresh()
                guard !Task.isCancelled else {
                    task.setTaskCompleted(success: false)
                    return
                }
                task.setTaskCompleted(success: true)
            } catch {
                task.setTaskCompleted(success: false)
            }
        }
        task.expirationHandler = { operation.cancel() }
    }
}
