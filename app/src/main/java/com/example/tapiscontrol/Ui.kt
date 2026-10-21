package com.example.tapiscontrol

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.floor
import kotlin.math.roundToInt

internal fun fmt(d: Double): String = if (d == floor(d)) d.toInt().toString() else d.toString()
internal fun mmss(s: Int): String = "%d:%02d".format(s / 60, s % 60)

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun AppRoot(vm: TapisViewModel, onScan: () -> Unit) {
    // rememberSaveable + configChanges nel manifest: ruotando lo schermo si resta sulla stessa scheda
    var tab by rememberSaveable { mutableStateOf(0) }
    val titles = listOf("Connessione", "Manuale", "Allenamenti", "In corso")

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val dismissed by vm.fsDismissed.collectAsStateWithLifecycle()
    val manualDismissed by vm.manualFsDismissed.collectAsStateWithLifecycle()
    val manualVideo by vm.manualVideo.collectAsStateWithLifecycle()
    val activeW = workouts.firstOrNull { it.id == activeId }
    // in verticale lo schermo intero si riarma da solo
    LaunchedEffect(landscape) {
        if (!landscape) {
            vm.fsDismissed.value = false
            vm.manualFsDismissed.value = false
        }
    }
    val runFullscreen = tab == 3 && landscape && activeW != null && activeW.video.active && !dismissed
    val manualFullscreen = tab == 1 && landscape && manualVideo.active && !manualDismissed
    val fullscreen = runFullscreen || manualFullscreen

    // nasconde le barre di sistema (si richiamano con uno swipe dal bordo)
    val view = LocalView.current
    DisposableEffect(fullscreen) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    if (runFullscreen && activeW != null) {
        RunFullscreen(vm, activeW, onExit = { vm.fsDismissed.value = true })
    } else if (manualFullscreen) {
        ManualFullscreen(vm, manualVideo, onExit = { vm.manualFsDismissed.value = true })
    } else {
        Scaffold { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    titles.forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                    }
                }
                when (tab) {
                    0 -> ConnectionTab(vm, onScan)
                    1 -> ManualTab(vm)
                    2 -> WorkoutsTab(vm, onGoRun = { tab = 3 })
                    else -> RunTab(vm)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Connessione
@Composable
fun ConnectionTab(vm: TapisViewModel, onScan: () -> Unit) {
    val ble = vm.ble
    val devices by ble.devices.collectAsStateWithLifecycle()
    val scanning by ble.scanning.collectAsStateWithLifecycle()
    val connected by ble.connected.collectAsStateWithLifecycle()
    val status by ble.status.collectAsStateWithLifecycle()
    val log by ble.log.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Stato: $status", style = MaterialTheme.typography.titleMedium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScan, enabled = !scanning) {
                    Text(if (scanning) "Scansione..." else "Cerca tapis roulant")
                }
                if (connected) OutlinedButton(onClick = { ble.disconnect() }) { Text("Disconnetti") }
            }
        }
        items(devices, key = { it.address }) { d ->
            Card(Modifier.fillMaxWidth().clickable { ble.connect(d.address) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(d.name, style = MaterialTheme.typography.titleSmall)
                    Text("${d.address}   RSSI ${d.rssi}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            val svc by ble.services.collectAsStateWithLifecycle()
            val raw by ble.raw.collectAsStateWithLifecycle()
            if (svc.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Diagnostica Bluetooth (per cercare i passi del tapis)", style = MaterialTheme.typography.titleSmall)
                        raw.forEach { (k, v) ->
                            Text("$k: $v", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        Text("Servizi:", style = MaterialTheme.typography.bodySmall)
                        svc.forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
        item { Text("Log", style = MaterialTheme.typography.titleSmall) }
        items(log.takeLast(60).reversed()) {
            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

// ------------------------------------------------------------------ Manuale
@Composable
fun StepControls(vm: TapisViewModel) {
    val paused by vm.stepsPaused.collectAsStateWithLifecycle()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.toggleStepsPause() }) { Text(if (paused) "▶ Riprendi passi" else "⏸ Pausa passi") }
        OutlinedButton(onClick = { vm.resetSteps() }) { Text("Azzera passi") }
    }
}

@Composable
fun LiveCard(l: LiveData, steps: Int? = null) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Dati dal tapis roulant", style = MaterialTheme.typography.titleSmall)
            Text("Velocità: ${l.speedKmh?.let { fmt(it) } ?: "–"} km/h")
            Text("Inclinazione: ${l.inclinePct?.let { fmt(it) } ?: "–"} %")
            Text("Distanza: ${l.distanceM ?: "–"} m")
            Text("Passi (stima): ${steps ?: "–"}")
            Text("Frequenza cardiaca: ${l.heartRate ?: "–"} bpm")
            Text("Tempo: ${l.elapsedSec?.let { mmss(it) } ?: "–"}")
        }
    }
}

@Composable
fun ManualTab(vm: TapisViewModel) {
    val live by vm.ble.live.collectAsStateWithLifecycle()
    val speed by vm.manualSpeed.collectAsStateWithLifecycle()
    val incline by vm.manualIncline.collectAsStateWithLifecycle()
    val connected by vm.ble.connected.collectAsStateWithLifecycle()
    val maxSpeed by vm.maxSpeed.collectAsStateWithLifecycle()
    val maxIncline by vm.maxIncline.collectAsStateWithLifecycle()
    val cal by vm.stepCal.collectAsStateWithLifecycle()
    val stepsEst by vm.stepsEst.collectAsStateWithLifecycle()
    val mv by vm.manualVideo.collectAsStateWithLifecycle()
    val manualDismissed by vm.manualFsDismissed.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var ytError by remember { mutableStateOf<String?>(null) }

    // il video scorre alla velocita' reale del tapis roulant, rispetto a quella di ripresa
    val liveSpeed = live.speedKmh ?: 0.0
    val vPlaying = liveSpeed >= 0.5
    val vRate = if (vPlaying) ((liveSpeed / mv.refSpeedKmh) * 20).roundToInt() / 20f else 1f

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!connected) Text("Non connesso: vai alla scheda Connessione.")

        if (mv.type == VideoType.ANIMATION) {
            AnimatedRoute(
                speedKmh = liveSpeed,
                inclinePct = live.inclinePct ?: 0.0,
                playing = vPlaying,
                refSpeedKmh = mv.refSpeedKmh,
                startSec = vm.manualVideoPos,
                caption = "",
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                onPosition = { vm.manualVideoPos = it },
            )
        }

        if (mv.source.isNotEmpty()) {
            when (mv.type) {
                VideoType.LOCAL -> LocalVideo(
                    uri = Uri.parse(mv.source),
                    rate = vRate,
                    playing = vPlaying,
                    modifier = Modifier.fillMaxWidth(),
                    startSec = vm.manualVideoPos,
                    onPosition = { vm.manualVideoPos = it },
                )
                VideoType.YOUTUBE -> {
                    val id = youtubeId(mv.source)
                    if (id == null) {
                        Text("Link YouTube non valido")
                    } else {
                        YouTubeVideo(
                            videoId = id,
                            rate = youtubeRate(vRate),
                            playing = vPlaying,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                            onError = { ytError = it },
                            startSec = vm.manualVideoPos,
                            onPosition = { vm.manualVideoPos = it },
                        )
                    }
                    ytError?.let { Text("YouTube ha rifiutato il video (codice $it): potrebbe non essere incorporabile.") }
                }
                VideoType.NONE, VideoType.ANIMATION -> {}
            }
        }

        if (mv.active) {
            if (landscape && manualDismissed) {
                OutlinedButton(onClick = { vm.manualFsDismissed.value = false }) { Text("Schermo intero") }
            } else if (!landscape) {
                Text(
                    "Ruota il telefono in orizzontale per vedere il percorso a schermo intero, con dati e comandi.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        LiveCard(live, stepsEst)
        StepControls(vm)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.manualStart() }, enabled = connected) { Text("Start") }
            OutlinedButton(onClick = { vm.manualStop() }, enabled = connected) { Text("Stop") }
        }

        Text("Velocità target: ${fmt(speed)} km/h", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.adjustSpeed(-1.0) }, enabled = connected) { Text("−1") }
            OutlinedButton(onClick = { vm.adjustSpeed(-0.1) }, enabled = connected) { Text("−0,1") }
            OutlinedButton(onClick = { vm.adjustSpeed(0.1) }, enabled = connected) { Text("+0,1") }
            OutlinedButton(onClick = { vm.adjustSpeed(1.0) }, enabled = connected) { Text("+1") }
        }

        Text("Inclinazione target: ${fmt(incline)} %", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.adjustIncline(-1.0) }, enabled = connected) { Text("−1") }
            OutlinedButton(onClick = { vm.adjustIncline(1.0) }, enabled = connected) { Text("+1") }
        }

        VideoCard(mv, "manual") { vm.setManualVideo(it) }
        if (mv.active) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.setManualVideo(mv.copy(refSpeedKmh = (mv.refSpeedKmh - 0.5).coerceIn(1.0, 30.0))) }) {
                    Text("Ripreso a ${fmt(mv.refSpeedKmh)}: −0,5")
                }
                OutlinedButton(onClick = { vm.setManualVideo(mv.copy(refSpeedKmh = (mv.refSpeedKmh + 0.5).coerceIn(1.0, 30.0))) }) {
                    Text("+0,5")
                }
            }
        }

        Text("Limite massimo di velocità: ${fmt(maxSpeed)} km/h", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = maxSpeed.toFloat(),
            onValueChange = { vm.setMaxSpeed((it * 2).roundToInt() / 2.0) },
            valueRange = 3f..15f,
        )

        Text("Limite massimo di pendenza: ${fmt(maxIncline)} %", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = maxIncline.toFloat(),
            onValueChange = { vm.setMaxIncline(it.roundToInt().toDouble()) },
            valueRange = 0f..15f,
        )

        Text("Passo a 3 km/h: ${cal.cm3} cm", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = cal.cm3.toFloat(),
            onValueChange = { vm.setStepCal(it.roundToInt(), cal.cm4, cal.cm5) },
            valueRange = 35f..100f,
        )
        Text("Passo a 4 km/h: ${cal.cm4} cm", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = cal.cm4.toFloat(),
            onValueChange = { vm.setStepCal(cal.cm3, it.roundToInt(), cal.cm5) },
            valueRange = 40f..110f,
        )
        Text("Passo a 5 km/h: ${cal.cm5} cm", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = cal.cm5.toFloat(),
            onValueChange = { vm.setStepCal(cal.cm3, cal.cm4, it.roundToInt()) },
            valueRange = 40f..120f,
        )
        Text(
            "Il tapis non conta i passi: l'app li stima dalla distanza, con la lunghezza del passo che cambia con la " +
                "velocita' (interpolata tra questi valori). Per tararli: a 3, 4 e 5 km/h conta i passi in 1 minuto " +
                "(entrambi i piedi) e calcola metri al minuto / passi al minuto.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun StepRow(index: Int, s: Step, enabled: Boolean, onChange: (Step) -> Unit, onDelete: () -> Unit) {
    var minTxt by remember { mutableStateOf(fmt(s.durationSec / 60.0)) }
    var spdTxt by remember { mutableStateOf(fmt(s.speedKmh)) }
    var incTxt by remember { mutableStateOf(fmt(s.inclinePct)) }

    fun push() {
        val m = minTxt.replace(',', '.').toDoubleOrNull()
        val v = spdTxt.replace(',', '.').toDoubleOrNull()
        val i = incTxt.replace(',', '.').toDoubleOrNull()
        if (m != null && v != null && i != null && m > 0 && v > 0) {
            onChange(s.copy(durationSec = (m * 60).roundToInt(), speedKmh = v, inclinePct = i))
        }
    }

    val kb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("${index + 1}", Modifier.padding(top = 18.dp))
        OutlinedTextField(
            value = minTxt, onValueChange = { minTxt = it; push() },
            label = { Text("min") }, singleLine = true, enabled = enabled,
            keyboardOptions = kb, modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = spdTxt, onValueChange = { spdTxt = it; push() },
            label = { Text("km/h") }, singleLine = true, enabled = enabled,
            keyboardOptions = kb, modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = incTxt, onValueChange = { incTxt = it; push() },
            label = { Text("%") }, singleLine = true, enabled = enabled,
            keyboardOptions = kb, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDelete, enabled = enabled) { Text("✕") }
    }
}
