package com.opendroid.ai.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoCutPlanTest {
    @Test fun parsesValidSecondRanges() {
        assertEquals(
            listOf(VideoCutSegment(0, 5_000), VideoCutSegment(7_000, 12_000)),
            VideoCutPlan.parseSeconds("0-5, 7-12")
        )
    }

    @Test fun rejectsNonFiniteAndMalformedRanges() {
        assertEquals(null, VideoCutPlan.parseSeconds("NaN-5"))
        assertEquals(null, VideoCutPlan.parseSeconds("0-Infinity"))
        assertEquals(null, VideoCutPlan.parseSeconds("0-5,broken"))
        assertEquals(null, VideoCutPlan.parseSeconds("   "))
    }

    @Test fun parsesTimedCaptionsOnOutputTimeline() {
        assertEquals(
            listOf(VideoCaptionCue(0, 2_000, "First line"), VideoCaptionCue(2_000, 4_000, "Second line")),
            VideoCutPlan.parseCaptions("0-2|First line;2-4|Second line")
        )
    }

    @Test fun rejectsInvalidTimedCaptions() {
        assertEquals(null, VideoCutPlan.parseCaptions("NaN-2|Hello"))
        assertEquals(null, VideoCutPlan.parseCaptions("2-1|Backwards"))
        assertEquals(null, VideoCutPlan.parseCaptions("0-2|   "))
        assertEquals(null, VideoCutPlan.parseCaptions("0-3|A;2-4|B"))
    }

    @Test fun acceptsSortedNonOverlappingRanges() {
        assertNull(VideoCutPlan.validate(listOf(VideoCutSegment(0, 5_000), VideoCutSegment(7_000, 12_000)), 20_000))
    }

    @Test fun rejectsEmptyRanges() {
        assertEquals("Add at least one cut range.", VideoCutPlan.validate(emptyList(), 20_000))
    }

    @Test fun rejectsUnsortedOrOverlappingRanges() {
        val error = VideoCutPlan.validate(listOf(VideoCutSegment(5_000, 9_000), VideoCutSegment(8_000, 12_000)), 20_000)
        assertEquals("Cut ranges must be sorted and must not overlap.", error)
    }

    @Test fun rejectsRangeBeyondSourceDuration() {
        val error = VideoCutPlan.validate(listOf(VideoCutSegment(1_000, 21_000)), 20_000)
        assertEquals("Cut 1 exceeds the source video duration.", error)
    }

    @Test fun capsBatchSize() {
        val ranges = (0 until 41).map { VideoCutSegment(it * 2_000L, it * 2_000L + 1_000L) }
        assertEquals("A maximum of 40 cut ranges is supported per export.", VideoCutPlan.validate(ranges, 100_000))
    }
}
