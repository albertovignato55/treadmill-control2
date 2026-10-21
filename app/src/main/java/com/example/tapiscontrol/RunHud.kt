package com.example.tapiscontrol

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Dati mostrati sopra l'animazione o il video (tutti facoltativi tranne velocita' e pendenza). */
data class HudData(
    val speedKmh: Double,
    val inclinePct: Double,
    val distanceM: Int? = null,
    val heartRate: Int? = null,
    val caption: String = "",
    val stepRemainingSec: Int? = null,   // tempo allo step successivo (o alla fine, se e' l'ultimo)
    val lastStep: Boolean = false,
    val nextInfo: String? = null,        // velocita' e pendenza dello step successivo
    val elapsedSec: Int? = null,
    val totalRemainingSec: Int? = null,
    val steps: Int? = null,              // passi stimati
)

/**
 * Taratura del passo a due velocita' (4 e 5 km/h): la lunghezza del passo alle altre velocita' si ricava
 * per interpolazione (o estrapolazione, limitata tra 2 e 8 km/h).
 */
data class StepCal(val cm4: Int = 64, val cm5: Int = 76) {
    fun strideM(speedKmh: Double): Double {
        val v = speedKmh.coerceIn(2.0, 8.0)
        return (cm4 + (cm5 - cm4) * (v - 4.0)).coerceIn(35.0, 120.0) / 100.0
    }
}

/** Distanza prevista (m) di un allenamento a intervalli: somma di velocita' x durata di ogni step. */
fun Workout.plannedMeters(): Int =
    flatSteps().sumOf { it.speedKmh * it.durationSec / 3.6 }.let { kotlin.math.round(it).toInt() }

/** Passi previsti di un allenamento a intervalli, con la lunghezza del passo adatta a ogni velocita'. */
fun Workout.plannedSteps(cal: StepCal): Int =
    kotlin.math.round(flatSteps().sumOf { (it.speedKmh * it.durationSec / 3.6) / cal.strideM(it.speedKmh) }).toInt()

/** Costruisce i dati del pannello dai valori live del tapis roulant e dall'avanzamento dell'allenamento. */
internal fun buildHud(
    w: Workout,
    p: WorkoutRunner.Progress,
    live: LiveData,
    isRoute: Boolean,
    steps: List<Step>,
    walkedSteps: Int? = null,
): HudData {
    val intervals = !isRoute && p.running && p.stepIndex >= 0
    val next = if (intervals) steps.getOrNull(p.stepIndex + 1) else null
    return HudData(
        speedKmh = live.speedKmh ?: 0.0,
        inclinePct = live.inclinePct ?: 0.0,
        distanceM = live.distanceM,
        heartRate = live.heartRate?.takeIf { it > 0 },
        caption = if (intervals) "${w.blockNameAt(p.stepIndex)} · step ${p.stepIndex + 1}/${p.stepCount}" else "",
        stepRemainingSec = if (intervals) p.stepRemainingSec else null,
        lastStep = intervals && next == null,
        nextInfo = next?.let { "${fmt(it.speedKmh)} km/h · ${fmt(it.inclinePct)} %" },
        elapsedSec = if (intervals) p.elapsedSec else live.elapsedSec,
        totalRemainingSec = if (intervals) p.totalRemainingSec else null,
        steps = walkedSteps,
    )
}

@Composable
internal fun HudChip(text: String, size: Float = 13f, bold: Boolean = false) {
    Text(
        text,
        color = Color.White,
        fontSize = size.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .background(Color(0xAA000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * Pannello sopra l'immagine: in alto a sinistra velocita', pendenza e battiti; in basso a sinistra nome dello
 * step, tempo al prossimo step, tempo trascorso e rimanente. `scale` ingrandisce i caratteri (schermo intero).
 */
@Composable
fun RunHud(d: HudData, modifier: Modifier = Modifier, scale: Float = 1f, showDistance: Boolean = scale >= 1.2f) {
    val s = 13f * scale
    Box(modifier) {
        Column(
            Modifier.align(Alignment.TopStart).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HudChip("${fmt(d.speedKmh)} km/h", s * 1.35f, bold = true)
            HudChip("pendenza ${d.inclinePct.toInt()} %", s * 1.1f)
            d.heartRate?.let { HudChip("♥ $it bpm", s * 1.1f, bold = true) }
            if (showDistance) d.distanceM?.let { HudChip("distanza ${"%.2f".format(it / 1000.0)} km", s) }
            if (showDistance) d.steps?.let { HudChip("passi ≈ $it", s) }
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (d.caption.isNotEmpty()) HudChip(d.caption, s, bold = true)
            d.stepRemainingSec?.let { rem ->
                val label = if (d.lastStep) "Fine allenamento tra" else "Prossimo step tra"
                val extra = d.nextInfo?.let { " ($it)" } ?: ""
                HudChip("$label ${mmss(rem)}$extra", s * 1.15f, bold = true)
            }
            if (d.elapsedSec != null || d.totalRemainingSec != null) {
                val parts = buildList {
                    d.elapsedSec?.let { add("Totale trascorso ${mmss(it)}") }
                    d.totalRemainingSec?.let { add("Rimane ${mmss(it)}") }
                }
                HudChip(parts.joinToString(" · "), s)
            }
        }
    }
}
