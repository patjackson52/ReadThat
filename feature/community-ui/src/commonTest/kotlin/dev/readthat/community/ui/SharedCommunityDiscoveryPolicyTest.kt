package dev.readthat.community.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedCommunityDiscoveryPolicyTest {
    @Test
    fun memberCountsStayCompactAndDeterministicAcrossPlatforms() {
        assertEquals("0", compactMemberCount(-1))
        assertEquals("999", compactMemberCount(999))
        assertEquals("1.2K", compactMemberCount(1_250))
        assertEquals("42K", compactMemberCount(42_001))
        assertEquals("1.5M", compactMemberCount(1_500_000))
    }
}
