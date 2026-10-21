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

private val MPAD = PaddingValues(horizontal = 8.dp, vertical = 2.dp)

@Composable
private fun MBtn(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(32.dp),
        contentPadding = MPAD,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
    ) { Text(text, fontSize = 12.sp) }
}

/**
 * Schermo intero della scheda Manuale (telefono in orizzontale): animazione o video a tutto schermo con i dati
 * (velocita', pendenza, battiti, distanza, tempo trascorso) e i comandi manuali (Start/Stop, velocita', pendenza).
 */
@Composable
fun ManualFullscreen(vm: TapisViewModel, mv: VideoRef, onExit: () -> Unit) {
    val live by vm.ble.live.collectAsStateWithLifecycle()
    val connected by vm.ble.connected.collectAsStateWithLifecycle()
    val speedTarget by vm.manualSpeed.collectAsStateWithLifecycle()
    val inclineTarget by vm.manualIncline.collectAsStateWithLifecycle()
    val stepLen by vm.stepLengthCm.collectAsStateWithLifecycle()
    var showControls by rememberSaveable { mutableStateOf(true) }
    var ytError by remember(mv.source) { mutableStateOf<String?>(null) }

    val speed = live.speedKmh ?: 0.0
    val playing = speed >= 0.5
    val rate = if (playing) ((speed / mv.refSpeedKmh) * 20).roundToInt() / 20f else 1f
    val hud = HudData(
        speedKmh = speed,
        inclinePct = live.inclinePct ?: 0.0,
        distanceM = live.distanceM,
        heartRate = live.heartRate?.takeIf { it > 0 },
        elapsedSec = live.elapsedSec,
        steps = stepsFromMeters(live.distanceM, stepLen),
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (mv.type) {
                VideoType.ANIMATION -> AnimatedRoute(
                    speedKmh = speed,
                    inclinePct = live.inclinePct ?: 0.0,
                    playing = playing,
                    refSpeedKmh = mv.refSpeedKmh,
                    startSec = vm.manualVideoPos,
                    caption = "",
                    modifier = Modifier.fillMaxSize(),
                    overlay = false,
                    onPosition = { vm.manualVideoPos = it },
                )
                VideoType.LOCAL -> LocalVideo(
                    uri = Uri.parse(mv.source),
                    rate = rate,
                    playing = playing,
                    startSec = vm.manualVideoPos,
                    onPosition = { vm.manualVideoPos = it },
                )
                VideoType.YOUTUBE -> {
                    val id = youtubeId(mv.source)
                    if (id != null) {
                        YouTubeVideo(
                            videoId = id,
                            rate = youtubeRate(rate),
                            playing = playing,
                            modifier = Modifier.aspectRatio(16f / 9f),
                            onError = { ytError = it },
                            startSec = vm.manualVideoPos,
                            onPosition = { vm.manualVideoPos = it },
                        )
                    }
                }
                VideoType.NONE -> {}
            }
        }

        RunHud(hud, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout), scale = 0.95f, showDistance = true)

        ytError?.let {
            Box(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.displayCutout).padding(8.dp)) {
                HudChip("YouTube ha rifiutato il video (codice $it)", 14f)
            }
        }
        if (!connected) {
            Box(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.displayCutout).padding(8.dp)) {
                HudChip("Non connesso: vai alla scheda Connessione", 14f)
            }
        }

        Row(
            Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.displayCutout).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MBtn(if (showControls) "Nascondi comandi" else "Comandi") { showControls = !showControls }
            MBtn("✕ Esci") { onExit() }
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
                    Button(onClick = { vm.manualStart() }, enabled = connected, contentPadding = MPAD, modifier = Modifier.height(32.dp)) { Text("Start", fontSize = 12.sp) }
                    MBtn("Stop", enabled = connected) { vm.manualStop() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Velocità ${fmt(speedTarget)} km/h", color = Color.White, fontSize = 12.sp)
                    MBtn("−1", connected) { vm.adjustSpeed(-1.0) }
                    MBtn("−0,1", connected) { vm.adjustSpeed(-0.1) }
                    MBtn("+0,1", connected) { vm.adjustSpeed(0.1) }
                    MBtn("+1", connected) { vm.adjustSpeed(1.0) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Pendenza ${fmt(inclineTarget)} %", color = Color.White, fontSize = 12.sp)
                    MBtn("−1 %", connected) { vm.adjustIncline(-1.0) }
                    MBtn("+1 %", connected) { vm.adjustIncline(1.0) }
                }
            }
        }
    }
}
