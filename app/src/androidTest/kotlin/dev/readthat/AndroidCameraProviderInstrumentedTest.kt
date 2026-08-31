package dev.readthat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.readthat.media.acquisition.finishAndroidCameraCapture
import dev.readthat.media.acquisition.prepareAndroidCameraCapture
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCameraProviderInstrumentedTest {
    @Test
    fun privateProviderCanWriteAndCancellationCleansItsOnlyExposedSubtree() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val capture = prepareAndroidCameraCapture(context)

        assertEquals("content", capture.outputUri.scheme)
        assertEquals("${context.packageName}.readthat-media", capture.outputUri.authority)
        context.contentResolver.openOutputStream(capture.outputUri, "w")!!.use { output ->
            output.write(byteArrayOf(1, 2, 3))
        }

        finishAndroidCameraCapture(context, capture.token, succeeded = false)
        val remaining = File(context.cacheDir, "readthat-camera-captures").listFiles().orEmpty()
        assertTrue("Cancelled camera output must be removed", remaining.isEmpty())
    }
}
