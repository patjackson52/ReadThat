package dev.readthat.client

import dev.readthat.search.domain.SearchComment
import dev.readthat.search.domain.SearchCommunity
import dev.readthat.search.domain.SearchParentPost
import dev.readthat.search.domain.SearchPost
import dev.readthat.search.domain.SearchProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedSearchPolicyTest {
    @Test
    fun `storage identities include the item kind`() {
        assertEquals("post:same", searchItemStorageId(SearchPost("same", "kmp", "a", "text", "p")))
        assertEquals(
            "comment:same",
            searchItemStorageId(
                SearchComment("same", "post", author = "a", body = "c", post = SearchParentPost("p", "kmp")),
            ),
        )
        assertEquals("community:same", searchItemStorageId(SearchCommunity("same", "kmp", "KMP")))
        assertEquals("profile:same", searchItemStorageId(SearchProfile("same", "reader", "Reader")))
    }
}
