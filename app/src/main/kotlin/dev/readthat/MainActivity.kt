package dev.readthat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.ReportDrawn
import androidx.activity.enableEdgeToEdge
import androidx.metrics.performance.JankStats
import dev.readthat.data.sync.FeedSyncScheduler
import dev.readthat.data.sync.CommunityVisitSyncScheduler
import dev.readthat.data.sync.CommunityMembershipSyncScheduler
import dev.readthat.data.sync.PostUploadScheduler
import dev.readthat.data.sync.SubredditCreationScheduler
import dev.readthat.client.SharedCreationOutcome
import dev.readthat.deeplink.DeepLinkInbox
import dev.readthat.observability.FramePerformanceAggregator
import dev.readthat.ui.app.ReadThatApp
import dev.readthat.compose.AndroidReadThatApp

class MainActivity : ComponentActivity() {
    private val framePerformance = FramePerformanceAggregator()
    private val deepLinks = DeepLinkInbox()
    private lateinit var jankStats: JankStats

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.dataString?.let(deepLinks::offerUrl)
        val performanceSession = if (BuildConfig.READTHAT_USE_SHARED_APP) null
            else (application as ReadThatApplication).newPerformanceSession()
        enableEdgeToEdge()
        setContent {
            if (BuildConfig.READTHAT_USE_SHARED_APP) {
                AndroidReadThatApp(
                    context = applicationContext,
                    baseUrl = BuildConfig.READTHAT_API_BASE_URL,
                    appVersion = BuildConfig.VERSION_NAME,
                    demoUsername = BuildConfig.READTHAT_DEMO_USERNAME,
                    demoPassword = BuildConfig.READTHAT_DEMO_PASSWORD,
                    buildType = BuildConfig.BUILD_TYPE,
                    deepLinks = deepLinks,
                    onCreationQueued = { outcome ->
                        when (outcome) {
                            is SharedCreationOutcome.PostQueued ->
                                PostUploadScheduler.enqueue(applicationContext, outcome.mutationId)
                            is SharedCreationOutcome.CommunityQueued ->
                                SubredditCreationScheduler.enqueue(applicationContext, outcome.mutationId)
                        }
                    },
                    onCommunityVisitQueued = { accountId ->
                        CommunityVisitSyncScheduler.enqueue(applicationContext, accountId)
                    },
                    onCommunityMembershipQueued = { accountId ->
                        CommunityMembershipSyncScheduler.enqueue(applicationContext, accountId)
                    },
                )
            } else {
                ReadThatApp(performanceSession = performanceSession, deepLinks = deepLinks)
            }
            // The static/cached shell is interactive on the first Compose draw;
            // network refinement is deliberately outside the fully-drawn gate.
            ReportDrawn()
        }
        // JankStats requires a DecorView. Compose installs it in setContent, so
        // attaching before that point crashes a cold Activity launch.
        jankStats = JankStats.createAndTrack(window, framePerformance::onFrame)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let(deepLinks::offerUrl)
    }

    override fun onStart() {
        super.onStart()
        // Returning to the app revalidates Room in deferred work. The cached
        // shell remains interactive; this never enters the first-frame path.
        FeedSyncScheduler.enqueueRefresh(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        jankStats.isTrackingEnabled = true
    }

    override fun onPause() {
        jankStats.isTrackingEnabled = false
        framePerformance.flush()
        super.onPause()
    }
}
