package dev.readthat.ad.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.viewinterop.AndroidView
import dev.readthat.domain.AdLaunchContext

@Composable
@SuppressLint("SetJavaScriptEnabled")
actual fun PlatformAdLanding(ad: AdLaunchContext, modifier: Modifier) {
    if (!isSecureAdDestination(ad.destinationUrl)) {
        InvalidAdLandingDestination(modifier)
        return
    }
    var webView by remember(ad.adId, ad.destinationUrl) { mutableStateOf<WebView?>(null) }
    var loadFailed by remember(ad.adId, ad.destinationUrl) { mutableStateOf(false) }
    val loadTracker = remember(ad.adId) { AdLandingLoadTracker(ad.adId) }

    Box(modifier) {
        key(ad.adId, ad.destinationUrl) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        settings.setGeolocationEnabled(false)
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.safeBrowsingEnabled = true
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val blocked = !isSecureAdDestination(request.url.toString())
                                if (blocked) {
                                    loadFailed = true
                                    loadTracker.failed()
                                }
                                return blocked
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                loadFailed = false
                                loadTracker.started()
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                if (!loadFailed) loadTracker.succeeded()
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                if (!request.isForMainFrame) return
                                loadFailed = true
                                loadTracker.failed()
                            }

                            override fun onReceivedSslError(
                                view: WebView,
                                handler: SslErrorHandler,
                                error: SslError,
                            ) {
                                // Never offer a certificate bypass for advertiser-controlled content.
                                handler.cancel()
                                loadFailed = true
                                loadTracker.failed()
                            }
                        }
                        loadUrl(ad.destinationUrl)
                    }
                },
            )
        }
        if (loadFailed) {
            AdLandingUnavailable(
                ad = ad,
                onRetry = {
                    loadFailed = false
                    webView?.loadUrl(ad.destinationUrl)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    DisposableEffect(ad.adId, ad.destinationUrl) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
}
