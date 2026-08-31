package dev.readthat.ad.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import dev.readthat.domain.AdLaunchContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.darwin.NSObject
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

@Composable
@OptIn(ExperimentalForeignApi::class)
actual fun PlatformAdLanding(ad: AdLaunchContext, modifier: Modifier) {
    if (!isSecureAdDestination(ad.destinationUrl)) {
        InvalidAdLandingDestination(modifier)
        return
    }
    var loadFailed by remember(ad.adId, ad.destinationUrl) { mutableStateOf(false) }
    val request = remember(ad.destinationUrl) {
        NSURLRequest(NSURL(string = ad.destinationUrl))
    }
    val webView = remember(ad.adId, ad.destinationUrl) {
        WKWebView(CGRectZero.readValue(), WKWebViewConfiguration())
    }
    val delegate = remember(ad.adId, ad.destinationUrl) {
        HttpsAdNavigationDelegate(
            adId = ad.adId,
            onStarted = { loadFailed = false },
            onFailed = { loadFailed = true },
        )
    }

    DisposableEffect(webView, delegate, request) {
        webView.setNavigationDelegate(delegate)
        webView.loadRequest(request)
        onDispose {
            webView.setNavigationDelegate(null)
            webView.stopLoading()
        }
    }
    Box(modifier) {
        key(ad.adId, ad.destinationUrl) {
            UIKitView(
                factory = { webView },
                update = { it.setHidden(loadFailed) },
                onRelease = { it.stopLoading() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (loadFailed) {
            AdLandingUnavailable(
                ad = ad,
                onRetry = {
                    loadFailed = false
                    webView.loadRequest(request)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class HttpsAdNavigationDelegate(
    adId: String,
    private val onStarted: () -> Unit,
    private val onFailed: () -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {
    private val loadTracker = AdLandingLoadTracker(adId)

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString
        val allowed = url == "about:blank" || url?.let(::isSecureAdDestination) == true
        if (!allowed) {
            loadTracker.failed()
            onFailed()
        }
        decisionHandler(
            if (allowed) WKNavigationActionPolicy.WKNavigationActionPolicyAllow
            else WKNavigationActionPolicy.WKNavigationActionPolicyCancel,
        )
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
        onStarted()
        loadTracker.started()
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        loadTracker.succeeded()
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) {
        loadTracker.failed()
        onFailed()
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
        loadTracker.failed()
        onFailed()
    }
}
