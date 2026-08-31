package dev.readthat.search.ui

import dev.readthat.search.domain.SearchComment
import dev.readthat.search.domain.SearchCommunity
import dev.readthat.search.domain.SearchParentPost
import dev.readthat.search.domain.SearchPost
import dev.readthat.search.domain.SearchProfile
import dev.readthat.search.domain.SearchSort
import dev.readthat.search.domain.SearchType
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedSearchUiPolicyTest {
    @Test
    fun `filter choices match result capabilities`() {
        assertEquals(listOf(SearchSort.Relevance), compatibleSorts(SearchType.Communities))
        assertEquals(listOf(SearchSort.Relevance), compatibleSorts(SearchType.Profiles))
        assertEquals(
            listOf(SearchSort.Relevance, SearchSort.Top, SearchSort.New),
            compatibleSorts(SearchType.Comments),
        )
        assertEquals(SearchSort.entries, compatibleSorts(SearchType.Posts))
    }

    @Test
    fun `lazy list keys do not collide between result kinds`() {
        val items = listOf(
            SearchPost("same", "kmp", "a", "text", "post"),
            SearchComment("same", "post", author = "a", body = "comment", post = SearchParentPost("p", "kmp")),
            SearchCommunity("same", "kmp", "KMP"),
            SearchProfile("same", "reader", "Reader"),
        )
        assertEquals(items.size, items.map(::searchItemKey).distinct().size)
    }
}
