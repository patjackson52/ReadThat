package dev.readthat.core.ui.typography

import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadThatTextStylesTest {
    @Test
    fun `feed hierarchy stays calibrated to compact Reddit roles`() {
        assertEquals(18.sp, ReadThatTextStyles.feedTitle.fontSize)
        assertEquals(20.sp, ReadThatTextStyles.feedTitle.lineHeight)
        assertEquals(16.sp, ReadThatTextStyles.feedBody.fontSize)
        assertEquals(12.sp, ReadThatTextStyles.feedMetadata.fontSize)
        assertEquals(12.sp, ReadThatTextStyles.feedAction.fontSize)
        assertEquals(11.sp, ReadThatTextStyles.bottomNavigationLabel.fontSize)
    }
}
