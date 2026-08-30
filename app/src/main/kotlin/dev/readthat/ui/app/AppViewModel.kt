package dev.readthat.ui.app

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Immutable
import dev.readthat.data.backend.BackendGraph
import dev.readthat.data.backend.BackendRepository
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.AppSettingsEntity
import dev.readthat.data.sync.PostUploadScheduler
import dev.readthat.data.sync.SubredditCreationScheduler
import dev.readthat.data.sync.CommunityVisitSyncScheduler
import dev.readthat.data.sync.CommunityMembershipSyncScheduler
import dev.readthat.shared.AppSettings
import dev.readthat.shared.AuthAction
import dev.readthat.shared.AuthForm
import dev.readthat.shared.AuthMode
import dev.readthat.shared.PostKind
import dev.readthat.shared.SessionState
import dev.readthat.shared.Validators
import dev.readthat.shared.reduceAuth
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@Immutable
data class AppUiState(
    val session: SessionState = SessionState.Restoring,
    val settings: AppSettings = AppSettings(),
    val auth: AuthForm = AuthForm(),
    val profileSaving: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val backend: BackendRepository = BackendGraph.repository(app)
    val session: StateFlow<SessionState> = backend.session

    private val settingsStore = SettingsStore(app, viewModelScope)
    val settings: StateFlow<AppSettings> = settingsStore.state

    private val mutableAuth = MutableStateFlow(AuthForm())
    val auth: StateFlow<AuthForm> = mutableAuth.asStateFlow()

    private val mutableProfileSaving = MutableStateFlow(false)
    val profileSaving: StateFlow<Boolean> = mutableProfileSaving.asStateFlow()

    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = mutableMessages.asSharedFlow()

    /** One atomic stream is the UDF boundary consumed by the root UI. */
    val uiState: StateFlow<AppUiState> = combine(
        session,
        settings,
        auth,
        profileSaving,
    ) { session, settings, auth, saving ->
        AppUiState(session, settings, auth, saving)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppUiState(),
    )

    init {
        viewModelScope.launch {
            runCatching { backend.restoreSession() }
                .onSuccess { user -> user?.let { resumePendingMutations(it.id) } }
                .onFailure { mutableMessages.emit(it.userMessage("Could not restore your session")) }
        }
    }

    fun setAuthMode(mode: AuthMode) = dispatch(AuthAction.SetMode(mode))
    fun updateUsername(value: String) = dispatch(AuthAction.SetUsername(value))
    fun updateDisplayName(value: String) = dispatch(AuthAction.SetDisplayName(value))
    fun updatePassword(value: String) = dispatch(AuthAction.SetPassword(value))
    fun togglePasswordVisibility() = dispatch(AuthAction.TogglePasswordVisibility)

    fun submitAuth() {
        val form = mutableAuth.value
        if (!form.canSubmit) return
        dispatch(AuthAction.Submit)
        viewModelScope.launch {
            runCatching {
                when (form.mode) {
                    AuthMode.Register -> backend.register(form.username, form.password, form.displayName)
                    AuthMode.Login -> backend.login(form.username, form.password)
                }
            }.onSuccess { user ->
                resumePendingMutations(user.id)
                mutableAuth.value = AuthForm(mode = form.mode)
            }.onFailure {
                dispatch(AuthAction.Failed(it.userMessage("Could not sign in")))
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            runCatching { backend.logout() }
                .onFailure { mutableMessages.emit("Signed out locally. The server could not be reached.") }
        }
    }

    fun saveProfile(
        displayName: String,
        bio: String,
        selectedAvatar: Uri?,
        removeAvatar: Boolean,
        onSaved: () -> Unit,
    ) {
        val error = Validators.displayName(displayName) ?: Validators.bio(bio)
        if (error != null) {
            mutableMessages.tryEmit(error)
            return
        }
        mutableProfileSaving.value = true
        viewModelScope.launch {
            runCatching {
                var preparedAvatar: PreparedAvatar? = null
                try {
                    val avatarMediaId = selectedAvatar?.let { uri ->
                        prepareAvatar(uri).also { preparedAvatar = it }.let { prepared ->
                            backend.uploadMedia(
                                kind = PostKind.Image,
                                contentType = prepared.contentType,
                                byteSize = prepared.file.length(),
                                openStream = prepared.file::inputStream,
                                width = prepared.width,
                                height = prepared.height,
                                altText = "${displayName.trim()} profile photo",
                            ).id
                        }
                    }
                    backend.updateProfile(
                        displayName = displayName,
                        bio = bio,
                        avatarMediaId = avatarMediaId,
                        updateAvatar = selectedAvatar != null || removeAvatar,
                    )
                } finally {
                    preparedAvatar?.file?.delete()
                }
            }
                .onSuccess { onSaved() }
                .onFailure { mutableMessages.emit(it.userMessage("Could not update profile")) }
            mutableProfileSaving.value = false
        }
    }

    private suspend fun prepareAvatar(uri: Uri): PreparedAvatar = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val contentType = resolver.getType(uri)?.lowercase()
            ?: error("Could not determine the image type")
        require(contentType in SUPPORTED_AVATAR_TYPES) { "Choose a JPEG, PNG, WebP, AVIF, or GIF image" }

        val directory = app.noBackupFilesDir.resolve("pending-avatars").apply { mkdirs() }
        val destination = directory.resolve(UUID.randomUUID().toString())
        try {
            resolver.openInputStream(uri)?.use { input ->
                destination.outputStream().buffered().use { output ->
                    input.copyToBounded(output, MAX_AVATAR_BYTES)
                }
            } ?: error("Could not read the selected image")
            require(destination.length() in 1..MAX_AVATAR_BYTES) { "Choose an image smaller than 10 MB" }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            destination.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected image could not be decoded" }
            require(bounds.outWidth <= 20_000 && bounds.outHeight <= 20_000) {
                "Choose an image no larger than 20,000 pixels per side"
            }
            PreparedAvatar(destination, contentType, bounds.outWidth, bounds.outHeight)
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsStore.update(transform)

    private fun dispatch(action: AuthAction) {
        mutableAuth.value = reduceAuth(mutableAuth.value, action)
    }

    private suspend fun resumePendingMutations(accountId: String) {
        val context = getApplication<Application>()
        SubredditCreationScheduler.resumePending(context, accountId)
        PostUploadScheduler.resumePending(context, accountId)
        CommunityVisitSyncScheduler.resumePending(context, accountId)
        CommunityMembershipSyncScheduler.resumePending(context, accountId)
    }
}

private data class PreparedAvatar(
    val file: File,
    val contentType: String,
    val width: Int,
    val height: Int,
)

private val SUPPORTED_AVATAR_TYPES = setOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/avif",
    "image/gif",
)
private const val MAX_AVATAR_BYTES = 10L * 1024 * 1024

private fun InputStream.copyToBounded(output: OutputStream, maxBytes: Long) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) return
        total += count
        require(total <= maxBytes) { "Choose an image smaller than 10 MB" }
        output.write(buffer, 0, count)
    }
}

private fun Throwable.userMessage(fallback: String): String = message?.takeIf(String::isNotBlank) ?: fallback

private class SettingsStore(context: Context, scope: CoroutineScope) {
    private val dao = AppDatabase.get(context.applicationContext).appSettingsDao()
    private val legacy = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(AppSettings())
    private val newestLocalWrite = AtomicLong(0L)
    private val writes = Channel<AppSettingsEntity>(Channel.CONFLATED)
    val state: StateFlow<AppSettings> = mutableState.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            for (entity in writes) dao.upsert(entity)
        }
        scope.launch(Dispatchers.IO) {
            dao.observe().collect { entity ->
                if (entity == null) {
                    val updatedAt = System.currentTimeMillis()
                    if (newestLocalWrite.compareAndSet(0L, updatedAt)) {
                        val migrated = readLegacySettings()
                        mutableState.value = migrated
                        writes.send(migrated.toEntity(updatedAt))
                        legacy.edit().clear().apply()
                    }
                } else if (entity.updatedAt >= newestLocalWrite.get()) {
                    mutableState.value = entity.toDomain()
                }
            }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(mutableState.value)
        mutableState.value = next
        val updatedAt = newestLocalWrite.updateAndGet { previous ->
            maxOf(System.currentTimeMillis(), previous + 1L)
        }
        writes.trySend(next.toEntity(updatedAt))
    }

    private fun readLegacySettings() = AppSettings(
        darkTheme = legacy.getBoolean("dark_theme", false),
        compactPosts = legacy.getBoolean("compact_posts", false),
        autoplayVideo = legacy.getBoolean("autoplay_video", true),
        autoplayOnMetered = legacy.getBoolean("autoplay_metered", false),
        reduceDataOnMetered = legacy.getBoolean("reduce_data_metered", true),
        reduceAnimations = legacy.getBoolean("reduce_animations", false),
        blurMatureMedia = legacy.getBoolean("blur_mature_media", true),
    )
}

private fun AppSettings.toEntity(updatedAt: Long) = AppSettingsEntity(
    darkTheme = darkTheme,
    compactPosts = compactPosts,
    autoplayVideo = autoplayVideo,
    autoplayOnMetered = autoplayOnMetered,
    reduceDataOnMetered = reduceDataOnMetered,
    reduceAnimations = reduceAnimations,
    blurMatureMedia = blurMatureMedia,
    updatedAt = updatedAt,
)

private fun AppSettingsEntity.toDomain() = AppSettings(
    darkTheme = darkTheme,
    compactPosts = compactPosts,
    autoplayVideo = autoplayVideo,
    autoplayOnMetered = autoplayOnMetered,
    reduceDataOnMetered = reduceDataOnMetered,
    reduceAnimations = reduceAnimations,
    blurMatureMedia = blurMatureMedia,
)
