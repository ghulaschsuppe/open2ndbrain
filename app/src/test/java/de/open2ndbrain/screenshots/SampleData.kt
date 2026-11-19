package de.open2ndbrain.screenshots

import de.open2ndbrain.core.Marker
import de.open2ndbrain.core.MarkerType
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.SpeakerProfile
import de.open2ndbrain.core.Transcript
import de.open2ndbrain.core.Utterance
import de.open2ndbrain.ui.recordings.DayGroup
import de.open2ndbrain.ui.recordings.RecordingsData
import de.open2ndbrain.ui.recordings.TranscriptBadge
import de.open2ndbrain.ui.recordings.TranscriptLine
import de.open2ndbrain.ui.recordings.TranscriptPreview
import de.open2ndbrain.ui.speakers.SpeakerDirectory
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Beispieldaten für die Screenshots (erfunden). */
object SampleData {
    private val zone = ZoneId.systemDefault()
    val today: LocalDate = LocalDate.of(2025, 11, 19)
    private fun at(day: LocalDate, h: Int, m: Int = 0, s: Int = 0) =
        LocalDateTime.of(day.year, day.month, day.dayOfMonth, h, m, s).atZone(zone).toInstant().toEpochMilli()

    private fun profile(id: String, number: Int, name: String?, me: Boolean = false, speechMin: Long, lastSeen: Long) = SpeakerProfile(
        id = id, name = name, number = number, isMe = me, embedding = emptyList(), sampleCount = 10,
        totalSpeechMs = speechMin * 60_000, firstSeenEpochMs = lastSeen - 86_400_000L * 20, lastSeenEpochMs = lastSeen,
    )

    val speakers = listOf(
        profile("s1", 1, "Jonas", me = true, speechMin = 412, lastSeen = at(today, 14, 52)),
        profile("s2", 2, "Mira Hoffmann", speechMin = 236, lastSeen = at(today, 14, 40)),
        profile("s3", 3, "Ali", speechMin = 128, lastSeen = at(today, 11, 5)),
        profile("s4", 4, null, speechMin = 41, lastSeen = at(today.minusDays(1), 17, 20)),
        profile("s5", 5, "Paula", speechMin = 18, lastSeen = at(today.minusDays(2), 9, 30)),
        profile("s6", 6, null, speechMin = 0, lastSeen = at(today.minusDays(3), 9, 30)),
    )
    val directory = SpeakerDirectory(speakers)

    private fun dir(day: LocalDate) = File("/data/audio/%04d/%02d/%02d".format(day.year, day.monthValue, day.dayOfMonth))
    private fun rec(day: LocalDate, hour: Int, minutes: Long, mb: Double, inProgress: Boolean = false) = RecordingFile(
        audio = File(dir(day), "%s_%02d-00.ogg".format(day, hour) + if (inProgress) ".part" else ""),
        sidecar = null,
        hourStartEpochMs = at(day, hour),
        durationMs = minutes * 60_000,
        sizeBytes = (mb * 1024 * 1024).toLong(),
        inProgress = inProgress,
    )

    val live = rec(today, 15, 7, 0.8, inProgress = true)
    val h14 = rec(today, 14, 41, 4.6)
    val h13 = rec(today, 13, 18, 2.1)
    val h11 = rec(today, 11, 33, 3.7)
    val y17 = rec(today.minusDays(1), 17, 25, 2.9)
    val y9 = rec(today.minusDays(1), 9, 12, 1.4)

    val meeting = Marker("m1", MarkerType.Meeting, at(today, 14, 5), at(today, 14, 48), title = "Wochenplanung Team", createdAtEpochMs = at(today, 14, 5))
    val bookmark = Marker("b1", MarkerType.Bookmark, at(today, 14, 22, 30), at(today, 14, 23), note = "Budget klären", createdAtEpochMs = at(today, 14, 23))

    val data = RecordingsData(
        days = listOf(
            DayGroup(today, listOf(live, h14, h13, h11), live.durationMs + h14.durationMs + h13.durationMs + h11.durationMs, live.sizeBytes + h14.sizeBytes + h13.sizeBytes + h11.sizeBytes),
            DayGroup(today.minusDays(1), listOf(y17, y9), y17.durationMs + y9.durationMs, y17.sizeBytes + y9.sizeBytes),
        ),
        meetings = mapOf(today to listOf(meeting)),
        badges = mapOf(
            h14.audio.path to TranscriptBadge.Done,
            h13.audio.path to TranscriptBadge.Transcribing,
            h11.audio.path to TranscriptBadge.Done,
            y17.audio.path to TranscriptBadge.Done,
            y9.audio.path to TranscriptBadge.Waiting,
        ),
        audioDeleted = emptySet(),
        bookmarks = mapOf(h14.audio.path to 1),
    )

    val previews = mapOf(
        h14.audio.path to TranscriptPreview(
            "Okay, dann fangen wir an. Erster Punkt ist die Planung für Dezember – Mira, magst du kurz sagen, wo wir beim Budget stehen?",
            listOf("s1", "s2", "s3"), 48,
        ),
        h11.audio.path to TranscriptPreview(
            "Ich hab den Termin beim Zahnarzt auf Donnerstag verschoben, dann passt das mit dem Abholen am Nachmittag.",
            listOf("s1", "s3"), 12,
        ),
        y17.audio.path to TranscriptPreview(
            "Wenn wir am Wochenende fahren, sollten wir die Fahrräder schon Freitag aufladen.",
            listOf("s1", "s4"), 9,
        ),
    )

    private fun u(h: Int, m: Int, s: Int, offsetS: Long, durS: Long, speaker: String?, text: String) =
        Utterance(fileOffsetMs = offsetS * 1000, durationMs = durS * 1000, startEpochMs = at(today, h, m, s), text = text, speakerId = speaker)

    val utterances = listOf(
        u(14, 5, 12, 312, 6, "s1", "Okay, dann fangen wir an. Erster Punkt ist die Planung für Dezember."),
        u(14, 5, 19, 319, 5, "s1", "Mira, magst du kurz sagen, wo wir beim Budget stehen?"),
        u(14, 5, 26, 326, 14, "s2", "Klar. Wir liegen etwa zehn Prozent unter Plan, vor allem weil die Messe ausgefallen ist. Das gibt uns etwas Luft für die neuen Laptops."),
        u(14, 5, 41, 341, 9, "s2", "Ich würde vorschlagen, dass wir die Bestellung noch vor Weihnachten rausschicken."),
        u(14, 5, 52, 352, 7, "s3", "Klingt gut. Brauchen wir dafür noch eine Freigabe von der Geschäftsführung?"),
        u(14, 6, 1, 361, 11, "s1", "Ab tausend Euro ja. Ich kümmere mich darum und schicke euch bis Freitag eine kurze Zusammenfassung."),
        u(14, 6, 14, 374, 6, "s2", "Super, dann setze ich das auf die Liste für nächste Woche."),
    )

    val transcript = Transcript(
        audioFileName = h14.audio.name,
        asrModelId = "parakeet-tdt-0.6b-v3",
        speakerModelId = "wespeaker",
        createdAtEpochMs = at(today, 15, 2),
        audioDurationMs = h14.durationMs,
        processingMs = 300_000,
        utterances = utterances,
    )

    val meetingLines = utterances.map { TranscriptLine(h14.audio, it) }
}
