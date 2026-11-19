package de.open2ndbrain.ui.speakers

import androidx.compose.ui.graphics.Color
import de.open2ndbrain.core.SpeakerProfile
import de.open2ndbrain.ui.theme.SpeakerColorsDark

/** Nachschlagetabelle für Sprecher-IDs aus Transkripten (inkl. Aliasse nach dem Zusammenführen). */
class SpeakerDirectory(profiles: List<SpeakerProfile>) {
    private val byId: Map<String, SpeakerProfile> = buildMap {
        for (p in profiles) {
            put(p.id, p)
            for (a in p.aliases) putIfAbsent(a, p)
        }
    }

    fun resolve(id: String?): SpeakerProfile? = id?.let { byId[it] }

    companion object {
        val EMPTY = SpeakerDirectory(emptyList())

        /**
         * Sprecherfarben (dunkles Thema); Zuordnung über die Sprechernummer. In Composables bitte
         * [speakerColor] nutzen – das wählt je nach Thema die passende Helligkeit.
         */
        val PALETTE: List<Color> = SpeakerColorsDark

        fun colorIndex(number: Int): Int = Math.floorMod(number - 1, PALETTE.size)

        /** Ab dieser Gesamtsprechzeit gilt eine Stimme als „viel gehört“. */
        const val FREQUENT_SPEECH_MS = 60_000L

        /**
         * Teilt die Liste für die Anzeige: oben „Ich“, benannte und viel gehörte Stimmen (Ich zuerst, dann benannte,
         * dann zuletzt gehört), darunter „Selten gehört“ (zuletzt gehört zuerst).
         */
        fun splitForDisplay(profiles: List<SpeakerProfile>): Pair<List<SpeakerProfile>, List<SpeakerProfile>> {
            val (main, rare) = profiles.partition { it.isMe || it.name != null || it.totalSpeechMs >= FREQUENT_SPEECH_MS }
            return main.sortedWith(
                compareByDescending<SpeakerProfile> { it.isMe }
                    .thenByDescending { it.name != null }
                    .thenByDescending { it.lastSeenEpochMs },
            ) to rare.sortedByDescending { it.lastSeenEpochMs }
        }

        fun color(profile: SpeakerProfile?): Color = profile?.let { PALETTE[colorIndex(it.number)] } ?: Color.Gray
    }
}
