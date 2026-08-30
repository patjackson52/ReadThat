package dev.readthat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.ReportDrawn
import androidx.activity.enableEdgeToEdge
import androidx.metrics.performance.JankStats
import dev.readthat.data.sync.FeedSyncScheduler
import dev.readthat.observability.FramePerformanceAggregator
import dev.readthat.ui.app.ReadThatApp

class MainActivity : ComponentActivity() {
    private val framePerformance = FramePerformanceAggregator()
    private lateinit var jankStats: JankStats

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val performanceSession = (application as ReadThatApplication).newPerformanceSession()
        CommentsGraph.initialize(applicationContext)
        enableEdgeToEdge()
        setContent {
            ReadThatApp(performanceSession = performanceSession)
            // The static/cached shell is interactive on the first Compose draw;
            // network refinement is deliberately outside the fully-drawn gate.
            ReportDrawn()
        }
        // JankStats requires a DecorView. Compose installs it in setContent, so
        // attaching before that point crashes a cold Activity launch.
        jankStats = JankStats.createAndTrack(window, framePerformance::onFrame)
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
