package com.example.tapiscontrol

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

private val BTN_PAD = PaddingValues(horizontal = 8.dp, vertical = 2.dp)

@Composable
private fun SmallOutlined(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = BTN_PAD,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
    ) { Text(text, fontSize = 12.sp) }
}

/**
 * Schermo intero (telefono in orizzontale): animazione o video a tutto schermo con i dati sopra
 * (velocita', pendenza, battiti, tempo al prossimo step, tempo trascorso e rimanente) e i comandi essenziali.
 */
@Composable
fun RunFullscreen(vm: TapisViewModel, w: Workout, onExit: () -> Unit) {
    val p by vm.runner.progress.collectAsStateWithLifecycle()
    val live by vm.ble.live.collectAsStateWithLifecycle()
    val connected by vm.ble.connected.collectAsStateWithLifecycle()
    val routeRunning by vm.routeRunning.collectAsStateWithLifecycle()
    val routePaused by vm.routePaused.collectAsStateWithLifecycle()
    val routeMsg by vm.routeMessage.collectAsStateWithLifecycle()
    val startStep by vm.startStep.collectAsStateWithLifecycle()
    val stepLen by vm.stepLengthCm.collectAsStateWithLifecycle()
    var showControls by rememberSaveable { mutableStateOf(true) }
    var ytError by remember(w.id) { mutableStateOf<String?>(null) }

    val isRoute = w.mode == WorkoutMode.ROUTE
    val running = if (isRoute) routeRunning else p.running
    val paused = if (isRoute) routePaused else p.paused
    val speed = live.speedKmh ?: 0.0
    val playing = running && !paused && speed >= 0.5
    val ref = w.video.refSpeedKmh
    val rate = if (speed >= 0.5) ((speed / ref) * 20).roundToInt() / 20f else 1f
    val steps = remember(w) { w.flatSteps() }
    val hud = buildHud(w, p, live, isRoute, steps, stepLen)
    val message = if (isRoute) routeMsg else p.message

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (w.video.type) {
                VideoType.ANIMATION -> AnimatedRoute(
                    speedKmh = speed,
                    inclinePct = live.inclinePct ?: 0.0,
                    playing = playing,
                    refSpeedKmh = ref,
                    startSec = vm.videoPosSec,
                    caption = "",
                    modifier = Modifier.fillMaxSize(),
                    overlay = false,
                    onPosition = { vm.onVideoPosition(it) },
                )
                VideoType.LOCAL -> LocalVideo(
                    uri = Uri.parse(w.video.source),
                    rate = rate,
                    playing = playing,
                    startSec = vm.videoPosSec,
                    onDuration = { vm.videoDurSec = it },
                    onPosition = { vm.onVideoPosition(it) },
                )
                VideoType.YOUTUBE -> {
                    val id = youtubeId(w.video.source)
                    if (id != null) {
                        YouTubeVideo(
                            videoId = id,
                            rate = youtubeRate(rate),
                            playing = playing,
                            modifier = Modifier.aspectRatio(16f / 9f),
                            onError = { ytError = it },
                            startSec = vm.videoPosSec,
                            onDuration = { vm.videoDurSec = it },
                            onPosition = { vm.onVideoPosition(it) },
                        )
                    }
                }
                VideoType.NONE -> {}
            }
        }

        RunHud(hud, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout), scale = 0.95f, showDistance = true)

        val note = ytError?.let { "YouTube ha rifiutato il video (codice $it)" } ?: message
        if (note.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.displayCutout).padding(8.dp),
            ) { HudChip(note, 14f) }
        }

        Row(
            Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.displayCutout).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallOutlined(if (showControls) "Nascondi comandi" else "Comandi") { showControls = !showControls }
            SmallOutlined("✕ Esci") { onExit() }
        }

        if (showControls) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(8.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(12.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!running) {
                        Button(
                            onClick = { if (isRoute) vm.startRoute(w.id) else vm.startWorkout(w.id, startStep) },
                            enabled = connected,
                            modifier = Modifier.height(32.dp),
        contentPadding = BTN_PAD,
                        ) { Text(if (!isRoute && startStep > 0) "Avvia dallo step ${startStep + 1}" else "Avvia", fontSize = 12.sp) }
                    } else {
                        Button(
                            onClick = { if (isRoute) vm.routePauseResume() else vm.runner.pauseResume() },
                            modifier = Modifier.height(32.dp),
        contentPadding = BTN_PAD,
                        ) { Text(if (paused) "Riprendi" else "Pausa", fontSize = 12.sp) }
                        SmallOutlined("Stop") { if (isRoute) vm.stopRoute() else vm.runner.stop() }
                    }
                }
                if (running && !isRoute) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Step ${p.stepIndex + 1}/${p.stepCount}", color = Color.White, fontSize = 12.sp)
                        SmallOutlined("⏮ Precedente") { vm.selectStep((p.stepIndex - 1).coerceAtLeast(0)) }
                        SmallOutlined("Successivo ⏭") { vm.selectStep((p.stepIndex + 1).coerceAtMost(p.stepCount - 1)) }
                    }
                }
                if (running) {
                    val big = if (isRoute) 1.0 else 0.5
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Velocità", color = Color.White, fontSize = 12.sp)
                        SmallOutlined("−${fmt(big)}") { if (isRoute) vm.routeAdjustSpeed(-big) else vm.adjustIntervalSpeed(-big) }
                        SmallOutlined("−0,1") { if (isRoute) vm.routeAdjustSpeed(-0.1) else vm.adjustIntervalSpeed(-0.1) }
                        SmallOutlined("+0,1") { if (isRoute) vm.routeAdjustSpeed(0.1) else vm.adjustIntervalSpeed(0.1) }
                        SmallOutlined("+${fmt(big)}") { if (isRoute) vm.routeAdjustSpeed(big) else vm.adjustIntervalSpeed(big) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Pendenza", color = Color.White, fontSize = 12.sp)
                        SmallOutlined("−1 %") { if (isRoute) vm.routeAdjustIncline(-1.0) else vm.adjustIntervalIncline(-1.0) }
                        SmallOutlined("+1 %") { if (isRoute) vm.routeAdjustIncline(1.0) else vm.adjustIntervalIncline(1.0) }
                    }
                }
            }
        }
    }
}
