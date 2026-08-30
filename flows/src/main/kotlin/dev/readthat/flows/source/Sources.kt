package dev.readthat.flows.source

import dev.readthat.flows.model.Connectivity
import dev.readthat.flows.model.Post
import dev.readthat.flows.model.Settings
import dev.readthat.flows.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

// ---------------------------------------------------------------------------
// 1. COLD FLOW — a new producer per collector.
// ---------------------------------------------------------------------------

/**
 * `flow { }` is COLD: the block re-runs from scratch for every collector, and
 * nothing happens until someone collects.
 *
 * Two collectors here get two independent tickers starting at 0 — which is the
 * usual "why is my counter restarting?" bug when a cold flow should have been
 * `shareIn`/`stateIn`.
 */
fun ticker(intervalMs: Long, count: Int): Flow<Int> = flow {
    for (i in 0 until count) {
        emit(i)
        if (i < count - 1) delay(intervalMs)
    }
}

// ---------------------------------------------------------------------------
// 2. callbackFlow — bridging a listener API into a Flow.
// ---------------------------------------------------------------------------

/**
 * A stand-in for the shape of a real callback API — ConnectivityManager,
 * SensorManager, a Firebase listener, a third-party SDK.
 */
class FakeConnectivityManager {
    fun interface Listener {
        fun onChanged(state: Connectivity)
    }

    private val listeners = mutableSetOf<Listener>()

    /** Test hook: how many listeners are currently registered. */
    val listenerCount: Int get() = synchronized(listeners) { listeners.size }

    fun register(listener: Listener) {
        synchronized(listeners) { listeners += listener }
    }

    fun unregister(listener: Listener) {
        synchronized(listeners) { listeners -= listener }
    }

    fun emit(state: Connectivity) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it.onChanged(state) }
    }
}

/**
 * The canonical `callbackFlow` shape.
 *
 * Three things make it correct, and all three get asked about:
 *
 *  1. `trySend` — the callback fires on someone else's thread and is not a suspend
 *     context, so you cannot `emit`. `trySend` is the non-suspending offer.
 *  2. `awaitClose { }` — suspends until the collector goes away, then unregisters.
 *     **Omitting it leaks the listener**, and `callbackFlow` will actually throw at
 *     runtime if it's missing. This is the whole reason `callbackFlow` exists.
 *  3. The initial value is pushed before awaiting, so a late subscriber isn't stuck
 *     with no state until the next change happens.
 */
fun FakeConnectivityManager.asFlow(initial: Connectivity = Connectivity.ONLINE): Flow<Connectivity> =
    callbackFlow {
        trySend(initial)

        val listener = FakeConnectivityManager.Listener { state -> trySend(state) }
        register(listener)

        awaitClose { unregister(listener) }
    }

// ---------------------------------------------------------------------------
// 3. HOT SOURCE — MutableStateFlow as an in-memory single source of truth.
// ---------------------------------------------------------------------------

/**
 * Local store. Always has a value, always replays it to new collectors, and
 * conflates: a fast writer does not queue up emissions for a slow reader.
 *
 * `update { }` is the compare-and-set loop — the thread-safe way to mutate. Doing
 * `value = value.copy(...)` is a read-modify-write race under concurrent writers.
 */
class LocalStore {
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _cachedPosts = MutableStateFlow<List<Post>>(emptyList())
    val cachedPosts: StateFlow<List<Post>> = _cachedPosts.asStateFlow()

    fun setDarkMode(enabled: Boolean) = _settings.update { it.copy(darkMode = enabled) }

    fun setAutoplay(enabled: Boolean) = _settings.update { it.copy(autoplayVideo = enabled) }

    fun blockSubreddit(name: String) =
        _settings.update { it.copy(blockedSubreddits = it.blockedSubreddits + name) }

    fun cachePosts(posts: List<Post>) { _cachedPosts.value = posts }
}

// ---------------------------------------------------------------------------
// 4. SUSPEND "REMOTE" SOURCE — fails a configurable number of times first.
// ---------------------------------------------------------------------------

/**
 * In-process fake. Fails [failuresBeforeSuccess] times so retry/backoff can be
 * exercised deterministically.
 */
open class FakeRemoteSource(
    private val failuresBeforeSuccess: Int = 0,
    private val latencyMs: Long = 0,
) {
    private val userAttempts = AtomicInteger(0)

    /** Test hook: how many times [fetchUser] was actually invoked. */
    val attemptCount: Int get() = userAttempts.get()

    suspend fun fetchUser(id: String): User {
        if (latencyMs > 0) delay(latencyMs)
        val attempt = userAttempts.incrementAndGet()
        if (attempt <= failuresBeforeSuccess) {
            throw IllegalStateException("transient failure #$attempt")
        }
        return User(id = id, displayName = "u/$id", karma = 1_234 * attempt)
    }

    open suspend fun search(query: String): List<Post> {
        if (latencyMs > 0) delay(latencyMs)
        if (query.isBlank()) return emptyList()
        // Deterministic, query-derived results.
        return List(3) { i ->
            Post(
                id = "$query-$i",
                subreddit = SUBS[i % SUBS.size],
                title = "$query result ${i + 1}",
                score = query.length * 100 + i,
            )
        }
    }

    /**
     * A cold flow that emits a growing list — stands in for a paged or streaming
     * endpoint, and gives `collectLatest` / `flatMapLatest` something cancellable
     * to interrupt.
     */
    fun streamPosts(subreddit: String, pages: Int = 3, pageDelayMs: Long = 100): Flow<List<Post>> =
        flow {
            val acc = mutableListOf<Post>()
            repeat(pages) { page ->
                delay(pageDelayMs)
                acc += List(2) { i ->
                    Post(
                        id = "$subreddit-p$page-$i",
                        subreddit = subreddit,
                        title = "$subreddit post ${page * 2 + i}",
                        score = (page + 1) * 10 + i,
                    )
                }
                emit(acc.toList())
            }
        }

    private companion object {
        val SUBS = listOf("androiddev", "Kotlin", "compose")
    }
}

/** Rethrow cancellation, swallow everything else. Used by the repositories. */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
