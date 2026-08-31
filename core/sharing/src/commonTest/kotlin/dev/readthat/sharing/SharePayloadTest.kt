package dev.readthat.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SharePayloadTest {
    @Test
    fun postPayloadNormalizesTitleAndKeepsHttpsUrl() {
        assertEquals(
            SharePayload(
                text = "A post\nhttps://api.test/post/1",
                subject = "A post",
            ),
            SharePayloads.post("  A post  ", "https://api.test/post/1"),
        )
        assertEquals(
            "ReadThat post\nhttp://localhost:8787/post/1",
            SharePayloads.post("", "http://localhost:8787/post/1").text,
        )
    }

    @Test
    fun unsafeOrMalformedPayloadsAreRejected() {
        assertEquals("Post", SharePayloads.post("Post", "http://example.test/post/1").text)
        assertEquals("Post", SharePayloads.post("Post", "https://example.test/${"x".repeat(32 * 1024)}").text)
        assertFailsWith<IllegalArgumentException> { SharePayloads.link("javascript:alert(1)") }
        assertEquals(null, SharePayloads.linkOrNull("javascript:alert(1)"))
        assertFailsWith<IllegalArgumentException> { SharePayload("hello", mimeType = "not a mime") }
    }

    @Test
    fun promotedLinkPayloadKeepsOnlyValidatedHttpsContent() {
        assertEquals(
            SharePayload(
                text = "https://promoted.example.test/landing",
                subject = "Promoted link",
            ),
            SharePayloads.link(
                "  https://promoted.example.test/landing  ",
                "  Promoted link  ",
            ),
        )
        assertEquals(null, SharePayloads.linkOrNull("http://promoted.example.test/landing"))
    }
}
