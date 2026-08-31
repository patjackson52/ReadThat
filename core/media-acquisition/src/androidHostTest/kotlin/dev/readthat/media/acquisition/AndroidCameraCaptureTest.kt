package dev.readthat.media.acquisition

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidCameraCaptureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val durableFiles = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        durableFiles.forEach(File::delete)
        File(context.cacheDir, "readthat-camera-captures").deleteRecursively()
    }

    @Test
    fun cancellationRemovesPrivateCameraOutput() {
        val token = UUID.randomUUID().toString()
        val source = cameraCaptureFile(context, token).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        assertNull(finishAndroidCameraCapture(context, token, succeeded = false))
        assertFalse(source.exists())
    }

    @Test
    fun successfulFullResolutionOutputIsValidatedAndMadeDurable() {
        val token = UUID.randomUUID().toString()
        val source = cameraCaptureFile(context, token).apply { parentFile?.mkdirs() }
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        source.outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
        }
        bitmap.recycle()

        val media = assertNotNull(finishAndroidCameraCapture(context, token, succeeded = true))
        durableFiles += File(media.localPath)
        assertEquals(640, media.width)
        assertEquals(360, media.height)
        assertEquals("image/jpeg", media.mimeType)
        assertTrue(File(media.localPath).isFile)
        assertTrue(File(media.localPath).toPath().startsWith(context.noBackupFilesDir.toPath()))
        assertFalse(source.exists())
    }

    @Test
    fun emptyCameraOutputIsRejectedAndRemoved() {
        val token = UUID.randomUUID().toString()
        val source = cameraCaptureFile(context, token).apply {
            parentFile?.mkdirs()
            createNewFile()
        }

        assertFailsWith<IllegalArgumentException> {
            finishAndroidCameraCapture(context, token, succeeded = true)
        }
        assertFalse(source.exists())
    }

    @Test
    fun tokenCannotEscapeThePrivateCameraDirectory() {
        assertFailsWith<IllegalArgumentException> {
            finishAndroidCameraCapture(context, "../pending-uploads/anything", succeeded = false)
        }
    }
}
