package dev.readthat

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.network.NetworkRequest
import dev.readthat.networking.TransportRequest
import dev.readthat.networking.UnifiedCoilNetworkClient
import dev.readthat.networking.UnifiedTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnifiedTransportInstrumentedTest {
    @Test
    @UnstableApi
    fun apiCoilAndMedia3ReuseOneEdgeEngine() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val health = "${BuildConfig.READTHAT_API_BASE_URL}/health"
        UnifiedTransport.initialize(context, setOf(BuildConfig.READTHAT_API_BASE_URL))

        val apiProtocols = buildList {
            repeat(3) {
                val response = UnifiedTransport.execute(context, TransportRequest(health))
                assertEquals(200, response.status)
                add(response.protocol)
            }
        }
        val afterApi = UnifiedTransport.debugSnapshot()
        assertNotEquals(0, afterApi.engineIdentity)

        val coilStatus = UnifiedCoilNetworkClient(context).executeRequest(NetworkRequest(health)) {
            it.code
        }
        assertEquals(200, coilStatus)

        val hlsUrl = InstrumentationRegistry.getArguments().getString("hlsUrl")
        val hlsProtocols = hlsUrl?.let { url ->
            buildList {
                repeat(3) {
                    val response = UnifiedTransport.execute(context, TransportRequest(url))
                    assertEquals(200, response.status)
                    assertTrue(response.body.decodeToString().startsWith("#EXTM3U"))
                    add(response.protocol)
                }
            }
        }

        val feed = UnifiedTransport.execute(
            context,
            TransportRequest("${BuildConfig.READTHAT_API_BASE_URL}/v1/feed?limit=20"),
        ).body.decodeToString()
        val imageUrl = Regex("https://imagedelivery\\.net/[^\\\"]+")
            .find(feed)
            ?.value
            ?.replace("\\u0026", "&")
        val imageProtocols = imageUrl?.let { url ->
            buildList {
                repeat(2) {
                    val response = UnifiedTransport.execute(context, TransportRequest(url))
                    assertEquals(200, response.status)
                    assertTrue(response.headers["content-type"]?.any { it.startsWith("image/") } == true)
                    add(response.protocol)
                }
            }
        }

        val mediaSource = UnifiedTransport.mediaDataSourceFactory(context).createDataSource()
        try {
            mediaSource.open(DataSpec(Uri.parse(hlsUrl ?: health)))
            val firstBytes = ByteArray(64)
            assertTrue(mediaSource.read(firstBytes, 0, firstBytes.size) > 0)
        } finally {
            mediaSource.close()
        }

        val afterAll = UnifiedTransport.debugSnapshot()
        assertEquals(afterApi.engineIdentity, afterAll.engineIdentity)
        assertTrue(afterAll.completedRequests >= 4)
        assertTrue(apiProtocols.all { it == "h3" || it == "h2" })
        assertTrue(hlsProtocols.orEmpty().all { it == "h3" || it == "h2" })
        assertTrue(imageProtocols.orEmpty().all { it == "h3" || it == "h2" })
        println("TRANSPORT_PROBE implementation=${afterAll.implementation} " +
            "engine=${afterAll.engineIdentity} apiProtocols=$apiProtocols " +
            "videoProtocols=$hlsProtocols imageProtocols=$imageProtocols last=${afterAll.lastProtocol}")
    }
}
