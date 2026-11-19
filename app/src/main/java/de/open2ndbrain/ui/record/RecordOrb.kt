package de.open2ndbrain.ui.record

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.open2ndbrain.R
import de.open2ndbrain.service.DisplayStatus
import de.open2ndbrain.ui.theme.O2bTheme

/**
 * Großer runder Aufnahme-Knopf mit „atmendem“ Ring. Der Ring folgt dem Pegel; bei erkannter Sprache leuchtet
 * er zusätzlich mint. Die Endlos-Animation existiert nur während „Nimmt auf“ – ist der Bildschirm nicht sichtbar,
 * liefert Compose keine Frames und die Animation ruht (Akku).
 */
@Composable
fun RecordOrb(
    status: DisplayStatus,
    running: Boolean,
    level: Float,
    speech: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = O2bTheme.extra
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val live = status == DisplayStatus.Recording
    val shownLevel by animateFloatAsState(if (live) level.coerceIn(0f, 1f) else 0f, tween(180), label = "orbLevel")
    val speechGlow by animateFloatAsState(if (live && speech) 1f else 0f, tween(400), label = "orbSpeech")
    val accent by animateColorAsState(
        when {
            live -> extra.live
            status == DisplayStatus.Paused -> scheme.secondary
            status == DisplayStatus.Error -> scheme.error
            running -> scheme.outline
            else -> scheme.primary
        },
        label = "orbAccent",
    )
    val container by animateColorAsState(
        if (running) scheme.surfaceContainerHighest else scheme.primary,
        label = "orbContainer",
    )
    val iconTint = if (running) accent else scheme.onPrimary
    val breath: State<Float> = if (live) breathing() else remember { ZeroState }

    val label = stringResource(if (running) R.string.record_stop else R.string.record_start)
    val stateText = stringResource(if (live) R.string.status_recording else R.string.status_off)

    Box(modifier.size(232.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = 74.dp.toPx()
            val maxExtra = 30.dp.toPx()
            val b = breath.value
            // Weicher Lichthof (immer, bei Aufnahme stärker)
            drawCircle(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = if (live) 0.20f else 0.12f), Color.Transparent),
                    center,
                    size.minDimension / 2,
                ),
            )
            if (live) {
                // Atmender Außenring (langsam), darin die Pegel-Scheibe (folgt der Lautstärke)
                drawCircle(
                    accent.copy(alpha = 0.14f + 0.12f * b),
                    base + maxExtra * (0.78f + 0.18f * b),
                    style = Stroke(1.dp.toPx()),
                )
                val halo = base + 5.dp.toPx() + maxExtra * 0.85f * shownLevel
                drawCircle(accent.copy(alpha = 0.16f), halo)
                drawCircle(accent.copy(alpha = 0.45f), halo, style = Stroke(1.5.dp.toPx()))
                if (speechGlow > 0f) {
                    // Sprache erkannt: zusätzlicher Mint-Saum
                    drawCircle(scheme.primary.copy(alpha = 0.7f * speechGlow), halo + 4.dp.toPx(), style = Stroke(2.dp.toPx()))
                }
            } else {
                drawCircle(accent.copy(alpha = 0.35f), base + 8.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            }
        }
        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            shape = CircleShape,
            color = container,
            shadowElevation = if (running) 0.dp else 6.dp,
            modifier = Modifier
                .size(148.dp)
                .semantics {
                    contentDescription = label
                    stateDescription = stateText
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (running) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(60.dp),
                )
            }
        }
    }
}

private object ZeroState : State<Float> {
    override val value: Float = 0f
}

@Composable
private fun breathing(): State<Float> {
    val transition = rememberInfiniteTransition(label = "breath")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathValue",
    )
}
