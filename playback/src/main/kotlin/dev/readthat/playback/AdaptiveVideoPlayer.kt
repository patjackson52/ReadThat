package dev.readthat.playback

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.StatFs
import android.view.LayoutInflater
import android.view.ViewTreeObserver
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import dev.readthat.shared.AppSettings
import dev.readthat.shared.ConnectionKind
import dev.readthat.shared.DeviceTier
import dev.readthat.shared.VideoPlaybackPolicy
import dev.readthat.shared.VideoPolicyResolver

data class AdaptiveVideoSource(
    val hlsUrl: String?,
    val fallbackUrl: String?,
    /** Stable media id; unlike a signed delivery URL it survives token rotation. */
    val cacheKey: String? = null,
)

data class AndroidNetworkSnapshot(
    val connection: ConnectionKind,
    val dataSaverEnabled: Boolean,
)

@Composable
fun rememberAndroidNetworkSnapshot(): AndroidNetworkSnapshot {
    val context = LocalContext.current.applicationContext
    val connectivity = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    fun current(): AndroidNetworkSnapshot {
        val network = connectivity.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        // INTERNET alone includes captive portals and links that have not proven end-to-end
        // reachability. Do not create autoplay traffic until Android validates the default path.
        val online = capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val unmetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val connection = when {
            !online -> ConnectionKind.Offline
            unmetered -> ConnectionKind.Unmetered
            else -> ConnectionKind.Metered
        }
        return AndroidNetworkSnapshot(
            connection = connection,
            dataSaverEnabled = connectivity.restrictBackgroundStatus ==
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
        )
    }

    var snapshot by remember(connectivity) { mutableStateOf(current()) }
    DisposableEffect(connectivity) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { snapshot = current() }
            override fun onLost(network: Network) { snapshot = current() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                snapshot = current()
            }
        }
        val dataSaverReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                snapshot = current()
            }
        }
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            callback,
        )
        val dataSaverFilter = IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(dataSaverReceiver, dataSaverFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(dataSaverReceiver, dataSaverFilter)
        }
        onDispose {
            connectivity.unregisterNetworkCallback(callback)
            context.unregisterReceiver(dataSaverReceiver)
        }
    }
    return snapshot
}

@Composable
fun rememberVideoPlaybackPolicy(settings: AppSettings): VideoPlaybackPolicy {
    val context = LocalContext.current.applicationContext
    val network = rememberAndroidNetworkSnapshot()
    val facts = remember(context) {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tier = when {
            activity.isLowRamDevice -> DeviceTier.LowMemory
            activity.memoryClass >= 512 -> DeviceTier.HighEnd
            else -> DeviceTier.Standard
        }
        tier to StatFs(context.cacheDir.absolutePath).availableBytes
    }
    return remember(settings, network, facts) {
        VideoPolicyResolver.resolve(
            settings = settings,
            connection = network.connection,
            dataSaverEnabled = network.dataSaverEnabled ||
                (settings.reduceDataOnMetered && network.connection == ConnectionKind.Metered),
            deviceTier = facts.first,
            availableCacheBytes = facts.second,
        )
    }
}

/**
 * A view lease on the process-wide player. Feed and detail never construct or
 * release ExoPlayer; changing destinations transfers its TextureView target.
 */
@Composable
@OptIn(UnstableApi::class)
fun AdaptiveVideoPlayer(
    source: AdaptiveVideoSource,
    policy: VideoPlaybackPolicy,
    autoplay: Boolean,
    muted: Boolean,
    showControls: Boolean,
    role: VideoPlaybackRole = VideoPlaybackRole.Feed,
    continueExistingPlayback: Boolean = false,
    modifier: Modifier = Modifier,
    onFirstFrame: () -> Unit = {},
    onPlaybackState: (VideoPlaybackSnapshot) -> Unit = {},
    onError: (PlaybackException) -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val rootView = LocalView.current
    if (source.hlsUrl == null && source.fallbackUrl == null) return
    val owner = remember { Any() }
    var lifecycleResumed by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var windowFocused by remember(rootView) { mutableStateOf(rootView.hasWindowFocus()) }
    val playbackVisible = lifecycleResumed && windowFocused

    SideEffect {
        VideoPlaybackCoordinator.attach(
            context = context,
            owner = owner,
            source = source,
            policy = policy,
            role = role,
            autoplay = autoplay,
            muted = muted,
            showControls = showControls,
            continueExistingPlayback = continueExistingPlayback,
            lifecycleStarted = playbackVisible,
            onFirstFrame = onFirstFrame,
            onPlaybackState = onPlaybackState,
            onError = onError,
        )
    }
    LaunchedEffect(source.stableKey) {
        if (VideoPlaybackCoordinator.hasRendered(source)) onFirstFrame()
    }
    DisposableEffect(owner, lifecycle, rootView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    lifecycleResumed = true
                    VideoPlaybackCoordinator.setLifecycleStarted(owner, windowFocused)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    lifecycleResumed = false
                    VideoPlaybackCoordinator.setLifecycleStarted(owner, false)
                }
                else -> Unit
            }
        }
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            windowFocused = focused
            VideoPlaybackCoordinator.setLifecycleStarted(owner, lifecycleResumed && focused)
        }
        // Activity recreation can grant focus in the narrow interval between
        // remember(rootView) sampling hasWindowFocus() and registering the
        // listener below. Re-sample on the view queue to close that race; without
        // it the replacement PlayerView remains attached but playback stays
        // lifecycle-gated until a later background/foreground transition.
        val synchronizeWindowFocus = Runnable {
            val focused = rootView.hasWindowFocus()
            windowFocused = focused
            VideoPlaybackCoordinator.setLifecycleStarted(owner, lifecycleResumed && focused)
        }
        lifecycle.addObserver(observer)
        rootView.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        rootView.post(synchronizeWindowFocus)
        onDispose {
            rootView.removeCallbacks(synchronizeWindowFocus)
            lifecycle.removeObserver(observer)
            if (rootView.viewTreeObserver.isAlive) {
                rootView.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            }
            VideoPlaybackCoordinator.detach(owner)
        }
    }
    AndroidView(
        factory = { viewContext ->
            (LayoutInflater.from(viewContext).inflate(R.layout.video_player_texture, null) as PlayerView).also {
                VideoPlaybackCoordinator.bindView(context, policy, owner, it)
            }
        },
        update = { view ->
            VideoPlaybackCoordinator.bindView(context, policy, owner, view)
        },
        onRelease = { view -> VideoPlaybackCoordinator.unbindView(owner, view) },
        modifier = modifier,
    )
}
