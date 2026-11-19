package de.open2ndbrain.models

import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.ModelKind
import de.open2ndbrain.core.ModelSpec
import de.open2ndbrain.core.ModelState

/** Reine Logik der Spracherkennungs-Modellauswahl (unit-getestet). */
object AsrSelection {

    /** Empfohlene Erstausstattung: Standard-ASR + Pflichtmodelle (VAD) + Sprechererkennung. */
    val RECOMMENDED: List<ModelSpec>
        get() = listOfNotNull(ModelCatalog.byId(ModelCatalog.DEFAULT_ASR_ID)) + ModelCatalog.all.filter { it.kind != ModelKind.Asr }

    val RECOMMENDED_BYTES: Long get() = RECOMMENDED.sumOf { it.sizeBytes }

    /**
     * Tatsächlich genutztes ASR-Modell:
     * - das gewählte ([chosen], null = nie gewählt → Standard), wenn es installiert ist;
     * - sonst ein anderes installiertes ASR-Modell (Standard zuerst, dann Katalogreihenfolge) – so bleiben
     *   Bestandsinstallationen mit nur Parakeet funktionsfähig und die Transkription läuft weiter, während das
     *   gewählte Modell noch lädt;
     * - sonst das gewählte bzw. der Standard (nicht installiert → „Modell fehlt“).
     */
    fun effective(chosen: String?, installed: Set<String>): String {
        val wanted = chosen?.takeIf { id -> ModelCatalog.asrModels.any { it.id == id } } ?: ModelCatalog.DEFAULT_ASR_ID
        if (wanted in installed) return wanted
        val order = listOf(ModelCatalog.DEFAULT_ASR_ID) + ModelCatalog.asrModels.map { it.id }
        return order.firstOrNull { it in installed } ?: wanted
    }

    fun installedIds(states: Map<String, ModelState>): Set<String> = states.filterValues { it is ModelState.Installed }.keys

    /** Transkription bereit = irgendein ASR-Modell (siehe [effective]) + VAD installiert. */
    fun ready(states: Map<String, ModelState>): Boolean {
        val installed = installedIds(states)
        return ModelCatalog.SILERO_VAD.id in installed && ModelCatalog.asrModels.any { it.id in installed }
    }

    /**
     * Einmaliger Vorschlag „Neues, besseres Deutsch-Modell verfügbar“: nur für Bestandsinstallationen, die nie
     * selbst gewählt haben und ein anderes Modell als den Standard nutzen.
     */
    fun upgradeSuggestion(chosen: String?, dismissed: Boolean, installed: Set<String>): ModelSpec? {
        if (chosen != null || dismissed) return null
        if (ModelCatalog.DEFAULT_ASR_ID in installed) return null
        if (ModelCatalog.asrModels.none { it.id in installed }) return null // Neuinstallation: normale Einrichtung
        return ModelCatalog.byId(ModelCatalog.DEFAULT_ASR_ID)
    }

    /**
     * Geschätzte Rechenzeit für [audioMs] Audio: gemessener Echtzeitfaktor dieses Geräts, sonst der Benchmark des
     * Modells × [PHONE_FACTOR] (Messungen auf dem PC, Handys sind langsamer).
     */
    fun estimateMs(audioMs: Long, measuredRtf: Float?, spec: ModelSpec?): Long {
        val rtf = measuredRtf?.toDouble() ?: ((spec?.benchmark?.realTimeFactor ?: DEFAULT_RTF) * PHONE_FACTOR)
        return (audioMs * rtf).toLong()
    }

    private const val DEFAULT_RTF = 0.1
    const val PHONE_FACTOR = 3.0
}
