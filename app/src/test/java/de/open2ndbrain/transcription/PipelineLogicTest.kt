package de.open2ndbrain.transcription

import de.open2ndbrain.core.RecordingSidecar.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineLogicTest {
    private fun s(sec: Double) = Math.round(sec * 16_000)
    private fun r(a: Double, b: Double) = SampleRange(s(a), s(b))

    // ---------------------------------------------------------------- BlockPlanner

    @Test
    fun blocksMergeShortGapsUpTo20s() {
        val segs = listOf(r(1.0, 3.0), r(4.0, 6.0), r(9.0, 10.0), r(10.5, 25.0), r(26.0, 30.0))
        val b = BlockPlanner.plan(segs, total = s(60.0))
        // 1–6 (Lücke 1 s), 9–25 (Lücke 0,5 s, Länge 16 s), 26–30 würde 21 s ergeben → eigener Block
        assertEquals(3, b.size)
        assertEquals(r(0.8, 6.2), b[0]) // 200 ms Rand
        assertEquals(s(9.0) - s(0.2), b[1].start)
        // Rand zum Nachbarn höchstens bis zur Mitte der Lücke (1 s → je 0,5 s, also volle 0,2 s)
        assertEquals(s(25.2), b[1].end)
        assertEquals(s(25.8), b[2].start)
    }

    @Test
    fun marginIsLimitedByNeighbourAndFileBounds() {
        // Block 1 endet bei 20 s (max. Länge erreicht), Block 2 beginnt 0,1 s später → Rand je 0,05 s
        val b = BlockPlanner.plan(listOf(r(0.05, 20.05), r(20.15, 22.0)), total = s(22.1))
        assertEquals(0L, b[0].start) // nicht vor Dateibeginn
        assertEquals(s(20.1), b[0].end)
        assertEquals(s(20.1), b[1].start)
        assertEquals(s(22.1), b[1].end) // nicht hinter Dateiende
        assertTrue(BlockPlanner.plan(emptyList(), 100).isEmpty())
    }

    // ---------------------------------------------------------------- RangeCollector

    @Test
    fun collectorDeliversOverlappingRangesInOrderOfEnd() {
        val got = ArrayList<Pair<String, FloatArray>>()
        val c = RangeCollector(
            listOf(SampleRange(2, 8) to "a", SampleRange(5, 6) to "b", SampleRange(9, 20) to "c"),
        ) { tag, _, data -> got += tag to data }
        val stream = FloatArray(15) { it.toFloat() }
        var i = 0
        while (i < stream.size) {
            val n = minOf(4, stream.size - i)
            c.accept(stream.copyOfRange(i, i + n), n)
            i += n
        }
        assertEquals(listOf("b", "a"), got.map { it.first })
        assertEquals(listOf(2f, 3f, 4f, 5f, 6f, 7f), got[1].second.toList())
        c.finish() // Strom endet vor Ende von „c“ → gekürzt
        assertEquals("c", got[2].first)
        assertEquals((9..14).map { it.toFloat() }, got[2].second.toList())
        assertTrue(c.done)
    }

    // ---------------------------------------------------------------- FileTimeMapper

    private val segs = listOf(
        Segment(fileOffsetMs = 0, startEpochMs = 1_000_000, durationMs = 5_000),
        Segment(fileOffsetMs = 5_000, startEpochMs = 1_600_000, durationMs = 3_000), // 10 min später
    )

    @Test
    fun mapperFollowsSegments() {
        val m = FileTimeMapper(segs, fallbackStartEpochMs = 0)
        assertEquals(1_000_000L, m.epochOf(0))
        assertEquals(1_004_999L, m.epochOf(4_999))
        assertEquals(1_600_000L, m.epochOf(5_000))
        assertEquals(1_602_000L, m.epochOf(7_000))
        assertEquals(1_603_500L, m.epochOf(8_500)) // hinter dem letzten Segment: fortgesetzt
        assertEquals(0L, m.discontinuityMs(1_000, 4_000))
        assertEquals(595_000L, m.discontinuityMs(4_000, 6_000))
        assertEquals(123_456L, FileTimeMapper(emptyList(), 120_000).epochOf(3_456))
    }

    // ---------------------------------------------------------------- UtteranceBuilder

    private fun t(text: String, startMs: Long, dur: Long = 100) = AsrToken(text, startMs, dur)

    @Test
    fun splitsAtSentenceEndAndKeepsAbbreviationTogether() {
        val tokens = listOf(
            t(" Hallo", 0), t(" Welt", 200), t(".", 300), t(" Wie", 600), t(" geht", 800), t("'s", 900), t("?", 950),
            t(" Z", 1_200), t(".", 1_250), t("B", 1_300), t(".", 1_350), t(" gut", 1_500), t(".", 1_600),
            t(" Dr", 2_000), t(".", 2_050), t(" Müller", 2_100), t(" kam", 2_300), t(".", 2_400), t(" Ende", 2_600),
        )
        val u = UtteranceBuilder.build(tokens, FileTimeMapper(emptyList(), 10_000))
        assertEquals(listOf("Hallo Welt.", "Wie geht's?", "Z.B. gut.", "Dr. Müller kam.", "Ende"), u.map { it.text })
        assertEquals(600L, u[1].fileOffsetMs)
        assertEquals(10_600L, u[1].startEpochMs)
        assertEquals(1_050L - 600, u[1].durationMs)
    }

    @Test
    fun splitsAtSpeakerChangePauseAndMaxLength() {
        val speakerAt: (Long) -> String? = { ms -> if (ms < 1_000) "A" else "B" }
        val tokens = listOf(t(" eins", 0), t(" zwei", 500), t(" drei", 1_000), t(" vier", 4_000))
        val u = UtteranceBuilder.build(tokens, FileTimeMapper(emptyList(), 0), speakerAt)
        assertEquals(listOf("eins zwei", "drei", "vier"), u.map { it.text })
        assertEquals(listOf("A", "B", "B"), u.map { it.speakerId })

        val long = (0 until 100).map { t(" w$it", it * 500L) } // 50 s ohne Satzende
        val lu = UtteranceBuilder.build(long, FileTimeMapper(emptyList(), 0))
        assertTrue(lu.size >= 2)
        assertTrue(lu.all { it.durationMs <= 30_100 })
    }

    @Test
    fun splitsAtWallClockJumpAndDropsBlankText() {
        // Block überspannt Sidecar-Segmentgrenze bei 5 s Dateizeit (Wanduhr springt 10 min)
        val m = FileTimeMapper(segs, 0)
        val tokens = listOf(t(" vor", 4_000), t(" der", 4_500), t(" nach", 5_100), t(" ", 7_000), t("  ", 7_100))
        val u = UtteranceBuilder.build(tokens, m)
        assertEquals(listOf("vor der", "nach"), u.map { it.text })
        assertEquals(1_004_000L, u[0].startEpochMs)
        assertEquals(1_600_100L, u[1].startEpochMs)
        assertTrue(UtteranceBuilder.build(listOf(t(" ", 0)), m).isEmpty())
    }
}
