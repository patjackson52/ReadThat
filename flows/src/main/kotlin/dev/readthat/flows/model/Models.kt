package dev.readthat.flows.model

/**
 * A load envelope carried *inside* the stream.
 *
 * The alternative — a separate `isLoading: StateFlow<Boolean>` next to a
 * `data: StateFlow<T>` — lets the two drift out of sync, and the UI ends up
 * rendering "loading" and stale data simultaneously. Modelling load state as part
 * of the emission makes an inconsistent combination unrepresentable.
 */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>

    data class Success<T>(val data: T) : LoadState<T>

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : LoadState<Nothing>

    val dataOrNull: T? get() = (this as? Success)?.data
}

inline fun <T, R> LoadState<T>.map(transform: (T) -> R): LoadState<R> = when (this) {
    is LoadState.Success -> LoadState.Success(transform(data))
    is LoadState.Loading -> LoadState.Loading
    is LoadState.Failure -> this
}

data class User(
    val id: String,
    val displayName: String,
    val karma: Int,
)

data class Settings(
    val darkMode: Boolean = false,
    val autoplayVideo: Boolean = true,
    val blockedSubreddits: Set<String> = emptySet(),
)

data class Post(
    val id: String,
    val subreddit: String,
    val title: String,
    val score: Int,
)

enum class Connectivity { ONLINE, OFFLINE }
