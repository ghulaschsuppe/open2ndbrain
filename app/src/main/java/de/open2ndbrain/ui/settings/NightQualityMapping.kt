package de.open2ndbrain.ui.settings

import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.Transcript
import de.open2ndbrain.work.NightQualityStatus

/**
 * „Nachtqualität: 12 von 30 verbessert“ für die Anzeige – nur wenn eingeschaltet, ein Nachtmodell feststeht, es
 * sich vom Tagesmodell unterscheidet und es Aufnahmen im Zeitraum gibt.
 */
fun nightProgress(st: NightQualityStatus): NightProgress? {
    val model = st.modelId ?: return null
    if (!st.enabled || st.sameAsDaytimeModel || st.total == 0) return null
    return NightProgress(st.improved, st.total, ModelCatalog.byId(model)?.displayName ?: model)
}

/**
 * „Nachts verbessert mit <Modell>“: Das Transkript stammt vom Nachtmodell, das nicht das Tagesmodell ist.
 * [activeModelId] = tagsüber genutztes Modell.
 */
fun improvedWith(transcript: Transcript?, night: NightQualityStatus, activeModelId: String?): String? {
    val t = transcript ?: return null
    val model = night.modelId ?: return null
    if (t.asrModelId != model || t.asrModelId == activeModelId) return null
    return ModelCatalog.byId(model)?.displayName ?: model
}
