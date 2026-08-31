package dev.readthat.ad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.readthat.domain.AdLaunchContext
import dev.readthat.networking.TransportSecurityPolicy
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductEventReason
import dev.readthat.observability.ProductSurface
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val adDestinationSecurity = TransportSecurityPolicy()

internal fun isSecureAdDestination(url: String): Boolean = adDestinationSecurity.isHttps(url)

/** HTTPS-only native landing surface shared by both application composition roots. */
@Composable
expect fun PlatformAdLanding(ad: AdLaunchContext, modifier: Modifier = Modifier)

@Composable
internal fun InvalidAdLandingDestination(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text("This destination cannot be opened securely.")
    }
}

@Composable
internal fun AdLandingUnavailable(
    ad: AdLaunchContext,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Portfolio page unavailable", style = MaterialTheme.typography.titleLarge)
        Text(
            "${ad.displayDomain} could not be loaded. The video remains available while you retry.",
            modifier = Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
            Text("Retry")
        }
    }
}

/** One monotonic, once-per-navigation telemetry policy for WebView and WKWebView. */
internal class AdLandingLoadTracker(
    private val adId: String,
    private val record: (ProductEvent) -> Unit = ProductAnalytics::record,
) {
    private var startedAt: TimeMark? = null

    fun started() {
        startedAt = TimeSource.Monotonic.markNow()
    }

    fun succeeded() = finish(null)

    fun failed() = finish(ProductEventReason.ERROR)

    private fun finish(reason: ProductEventReason?) {
        val timer = startedAt ?: return
        startedAt = null
        record(ProductEvent(
            name = ProductEventName.AD_LANDING_LOAD,
            surface = ProductSurface.AD_DETAIL,
            contentId = adId,
            contentType = ProductContentType.AD,
            reason = reason,
            durationMs = timer.elapsedNow().inWholeMilliseconds.coerceAtLeast(0L),
        ))
    }
}
