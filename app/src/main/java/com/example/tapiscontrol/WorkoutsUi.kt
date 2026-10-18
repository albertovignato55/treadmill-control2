package com.example.tapiscontrol

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

// ------------------------------------------------------------------ Scheda "Allenamenti"
@Composable
fun WorkoutsTab(vm: TapisViewModel, onGoRun: () -> Unit) {
    val editingId by vm.editingId.collectAsStateWithLifecycle()
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val editing = workouts.firstOrNull { it.id == editingId }
    if (editing == null) WorkoutList(vm, workouts, onGoRun) else WorkoutEditor(vm, editing, onGoRun)
}

@Composable
fun WorkoutList(vm: TapisViewModel, workouts: List<Workout>, onGoRun: () -> Unit) {
    val connected by vm.ble.connected.collectAsStateWithLifecycle()
    val progress by vm.runner.progress.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<Workout?>(null) }
    var showImport by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val inclineRange by vm.ble.inclineRange.collectAsStateWithLifecycle()
    val maxSpeed by vm.maxSpeed.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.newWorkout() }) { Text("+ Nuovo allenamento") }
                OutlinedButton(onClick = { showImport = true }) { Text("Importa JSON") }
            }
        }
        item {
            OutlinedButton(onClick = {
                val prompt = plannerPrompt(maxSpeed, inclineRange?.endInclusive ?: 8.0)
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Prompt Tapis Control", prompt))
                Toast.makeText(ctx, "Prompt copiato: incollalo in una chat con Claude", Toast.LENGTH_LONG).show()
            }) { Text("Copia prompt per Claude") }
        }
        items(workouts, key = { it.id }) { w ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(w.name, style = MaterialTheme.typography.titleMedium)
                    val video = when (w.video.type) {
                        VideoType.NONE -> ""
                        VideoType.LOCAL -> " · video file"
                        VideoType.YOUTUBE -> " · video YouTube"
                    }
                    Text(
                        if (w.mode == WorkoutMode.ROUTE) {
                            "Percorso virtuale · ${w.route.size} punti profilo$video"
                        } else {
                            "${w.totalSec() / 60} min · ${w.blocks.size} blocchi$video"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.startWorkout(w.id); onGoRun() },
                            enabled = connected && !progress.running,
                        ) { Text("Avvia") }
                        OutlinedButton(onClick = { vm.editingId.value = w.id }) { Text("Modifica") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.duplicateWorkout(w.id) }) { Text("Duplica") }
                        TextButton(onClick = { toDelete = w }) { Text("Elimina") }
                    }
                }
            }
        }
    }

    if (showImport) ImportDialog(vm) { showImport = false }

    toDelete?.let { w ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Eliminare \"${w.name}\"?") },
            confirmButton = {
                TextButton(onClick = { vm.deleteWorkout(w.id); toDelete = null }) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annulla") } },
        )
    }
}

@Composable
fun WorkoutEditor(vm: TapisViewModel, w: Workout, onGoRun: () -> Unit) {
    val connected by vm.ble.connected.collectAsStateWithLifecycle()
    val progress by vm.runner.progress.collectAsStateWithLifecycle()
    val library by vm.blockLibrary.collectAsStateWithLifecycle()
    var nameTxt by remember(w.id) { mutableStateOf(w.name) }
    var showLib by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.editingId.value = null }) { Text("← Elenco") }
                Button(
                    onClick = { vm.startWorkout(w.id); onGoRun() },
                    enabled = connected && !progress.running,
                ) { Text("Avvia") }
            }
        }
        item {
            OutlinedTextField(
                value = nameTxt,
                onValueChange = { text -> nameTxt = text; vm.editWorkout { x -> x.copy(name = text) } },
                label = { Text("Nome allenamento") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(WorkoutMode.STEPS to "Intervalli", WorkoutMode.ROUTE to "Percorso video")
                    .forEach { (m, label) ->
                        if (w.mode == m) {
                            Button(onClick = {}) { Text(label) }
                        } else {
                            OutlinedButton(onClick = { vm.setMode(m) }) { Text(label) }
                        }
                    }
            }
        }
        if (w.mode == WorkoutMode.STEPS) {
            item { Text("Durata totale: ${w.totalSec() / 60} min", style = MaterialTheme.typography.titleSmall) }
        }
        item { VideoSection(w, vm) }
        if (w.mode == WorkoutMode.STEPS) {
            itemsIndexed(w.blocks, key = { _, b -> b.id }) { _, b ->
                BlockCard(
                    block = b,
                    onChange = { vm.updateBlock(it) },
                    onDelete = { vm.removeBlock(b.id) },
                    onSaveLibrary = { vm.saveBlockToLibrary(b) },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.addBlock() }) { Text("+ Blocco") }
                    OutlinedButton(onClick = { showLib = true }) { Text("+ Da libreria") }
                }
            }
        } else {
            item { RouteEditor(w, vm) }
        }
    }

    if (showLib) {
        LibraryDialog(
            library = library,
            onInsert = { vm.insertFromLibrary(it) },
            onDelete = { vm.deleteFromLibrary(it) },
            onDismiss = { showLib = false },
        )
    }
}

@Composable
fun BlockCard(block: Block, onChange: (Block) -> Unit, onDelete: () -> Unit, onSaveLibrary: () -> Unit) {
    var nameTxt by remember(block.id) { mutableStateOf(block.name) }
    var repTxt by remember(block.id) { mutableStateOf(block.repeat.toString()) }
    var saved by remember(block.id) { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = nameTxt,
                    onValueChange = { text -> nameTxt = text; onChange(block.copy(name = text)) },
                    label = { Text("Blocco") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = repTxt,
                    onValueChange = { text ->
                        repTxt = text
                        text.toIntOrNull()?.let { n -> if (n in 1..99) onChange(block.copy(repeat = n)) }
                    },
                    label = { Text("Ripeti ×") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            block.steps.forEachIndexed { i, s ->
                key(s.id) {
                    StepRow(
                        index = i,
                        s = s,
                        enabled = true,
                        onChange = { ns ->
                            onChange(block.copy(steps = block.steps.map { if (it.id == ns.id) ns else it }))
                        },
                        onDelete = { onChange(block.copy(steps = block.steps.filterNot { it.id == s.id })) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    val last = block.steps.lastOrNull()
                    val new = last?.copy(id = newStepId())
                        ?: Step(durationSec = 120, speedKmh = 4.0, inclinePct = 0.0)
                    onChange(block.copy(steps = block.steps + new))
                }) { Text("+ Step") }
                TextButton(onClick = { onSaveLibrary(); saved = true }) {
                    Text(if (saved) "Salvato ✓" else "In libreria")
                }
                TextButton(onClick = onDelete) { Text("Elimina") }
            }
        }
    }
}

@Composable
fun LibraryDialog(
    library: List<Block>,
    onInsert: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Libreria blocchi") },
        text = {
            if (library.isEmpty()) {
                Text("Vuota. Usa \"In libreria\" su un blocco per salvarlo qui.")
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(library) { i, b ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text("${b.name} · ${b.repeat}× · ${b.steps.size} step")
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { onInsert(i); onDismiss() }) { Text("Inserisci") }
                                TextButton(onClick = { onDelete(i) }) { Text("Elimina") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
    )
}

@Composable
fun VideoSection(w: Workout, vm: TapisViewModel) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            vm.setVideo(w.video.copy(type = VideoType.LOCAL, source = uri.toString()))
        }
    }
    var linkTxt by remember(w.id, w.video.type) { mutableStateOf(w.video.source) }
    var refTxt by remember(w.id) { mutableStateOf(fmt(w.video.refSpeedKmh)) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Video (opzionale)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    VideoType.NONE to "Nessuno",
                    VideoType.LOCAL to "File",
                    VideoType.YOUTUBE to "YouTube",
                ).forEach { (t, label) ->
                    if (w.video.type == t) {
                        Button(onClick = {}) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { vm.setVideo(w.video.copy(type = t, source = "")) }) {
                            Text(label)
                        }
                    }
                }
            }
            when (w.video.type) {
                VideoType.NONE -> {}
                VideoType.LOCAL -> {
                    OutlinedButton(onClick = { picker.launch(arrayOf("video/*")) }) { Text("Scegli file video") }
                    Text(if (w.video.source.isEmpty()) "Nessun file scelto" else "File scelto ✓")
                }
                VideoType.YOUTUBE -> {
                    OutlinedTextField(
                        value = linkTxt,
                        onValueChange = { text -> linkTxt = text; vm.setVideo(w.video.copy(source = text.trim())) },
                        label = { Text("Link YouTube") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (w.video.source.isNotEmpty() && youtubeId(w.video.source) == null) {
                        Text("Link non riconosciuto")
                    }
                }
            }
            if (w.video.type != VideoType.NONE) {
                OutlinedTextField(
                    value = refTxt,
                    onValueChange = { text ->
                        refTxt = text
                        text.replace(',', '.').toDoubleOrNull()?.let { v ->
                            if (v in 1.0..30.0) vm.setVideo(w.video.copy(refSpeedKmh = v))
                        }
                    },
                    label = { Text("Velocità di ripresa del video (km/h)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "A che passo cammina chi ha girato il video. Potrai calibrarlo mentre guardi.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Profilo pendenza (modalita' percorso)
/** "1:30" oppure "1,5" (minuti) -> secondi. */
private fun parseMinSec(t: String): Int? {
    val s = t.trim().replace(',', '.')
    if (s.contains(':')) {
        val parts = s.split(':')
        if (parts.size != 2) return null
        val m = parts[0].toIntOrNull() ?: return null
        val sec = parts[1].toIntOrNull() ?: return null
        if (m < 0 || sec !in 0..59) return null
        return m * 60 + sec
    }
    val m = s.toDoubleOrNull() ?: return null
    if (m < 0) return null
    return (m * 60).roundToInt()
}

@Composable
fun RoutePointRow(p: RoutePoint, onChange: (RoutePoint) -> Unit, onDelete: () -> Unit) {
    var tTxt by remember(p.id) { mutableStateOf(mmss(p.videoSec)) }
    var iTxt by remember(p.id) { mutableStateOf(fmt(p.inclinePct)) }
    var sTxt by remember(p.id) { mutableStateOf(p.speedKmh?.let { fmt(it) } ?: "") }

    fun push() {
        val t = parseMinSec(tTxt)
        val i = iTxt.replace(',', '.').toDoubleOrNull()
        val sp = sTxt.replace(',', '.').trim()
        val v = if (sp.isEmpty()) null else sp.toDoubleOrNull()
        val vOk = sp.isEmpty() || (v != null && v in 1.0..15.0)
        if (t != null && i != null && i in 0.0..15.0 && vOk) {
            onChange(p.copy(videoSec = t, inclinePct = i, speedKmh = v))
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = tTxt, onValueChange = { tTxt = it; push() },
            label = { Text("minuto (m:ss)") }, singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = iTxt, onValueChange = { iTxt = it; push() },
            label = { Text("pendenza %") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = sTxt, onValueChange = { sTxt = it; push() },
            label = { Text("km/h") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDelete) { Text("✕") }
    }
}

@Composable
fun RouteEditor(w: Workout, vm: TapisViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Profilo del percorso (pendenza e velocità)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Pendenza e velocità cambiano da sole in base al punto del video: tra due punti vengono interpolate " +
                    "(la velocità al massimo di 0,3 km/h ogni 2 s, e mai oltre il limite impostato). " +
                    "Lascia vuota la velocità di un punto se vuoi gestirla tu a mano. Puoi scrivere i punti qui, " +
                    "oppure registrarli guardando il video dalla scheda In corso (pulsante \"Registra punto qui\").",
                style = MaterialTheme.typography.bodySmall,
            )
            w.route.forEach { p ->
                key(p.id) {
                    RoutePointRow(
                        p = p,
                        onChange = { vm.updateRoutePoint(it) },
                        onDelete = { vm.removeRoutePoint(p.id) },
                    )
                }
            }
            TextButton(onClick = { vm.addRoutePoint() }) { Text("+ Punto") }
        }
    }
}

// ------------------------------------------------------------------ Scheda "In corso"
@Composable
fun PositionText(vm: TapisViewModel) {
    val pos by vm.videoPos.collectAsStateWithLifecycle()
    Text("Posizione video: ${mmss(pos.toInt())}", style = MaterialTheme.typography.bodySmall)
}

@Composable
fun RouteControls(vm: TapisViewModel, w: Workout, connected: Boolean) {
    val running by vm.routeRunning.collectAsStateWithLifecycle()
    val paused by vm.routePaused.collectAsStateWithLifecycle()
    val speed by vm.routeSpeed.collectAsStateWithLifecycle()
    val incline by vm.routeIncline.collectAsStateWithLifecycle()
    val msg by vm.routeMessage.collectAsStateWithLifecycle()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PositionText(vm)
            if (msg.isNotEmpty()) Text(msg)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!running) {
                    Button(onClick = { vm.startRoute(w.id) }, enabled = connected) { Text("Avvia") }
                } else {
                    Button(onClick = { vm.routePauseResume() }) { Text(if (paused) "Riprendi" else "Pausa") }
                    OutlinedButton(onClick = { vm.stopRoute() }) { Text("Stop") }
                }
            }

            Text("Velocità target: ${fmt(speed)} km/h", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.routeAdjustSpeed(-1.0) }) { Text("−1") }
                OutlinedButton(onClick = { vm.routeAdjustSpeed(-0.1) }) { Text("−0,1") }
                OutlinedButton(onClick = { vm.routeAdjustSpeed(0.1) }) { Text("+0,1") }
                OutlinedButton(onClick = { vm.routeAdjustSpeed(1.0) }) { Text("+1") }
            }

            Text("Pendenza target: ${fmt(incline)} %", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.routeAdjustIncline(-1.0) }) { Text("−1") }
                OutlinedButton(onClick = { vm.routeAdjustIncline(1.0) }) { Text("+1") }
            }

            if (w.video.source.isNotEmpty()) {
                OutlinedButton(onClick = { vm.recordRoutePoint(w.id, vm.videoPosSec, incline, speed) }) {
                    Text("Registra punto qui (velocità + pendenza)")
                }
            }
            Text(
                "Punti nel profilo: ${w.route.size}. Se correggi velocità o pendenza a mano, vale fino al " +
                    "prossimo cambio previsto dal profilo.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun RunTab(vm: TapisViewModel) {
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val p by vm.runner.progress.collectAsStateWithLifecycle()
    val live by vm.ble.live.collectAsStateWithLifecycle()
    val connected by vm.ble.connected.collectAsStateWithLifecycle()
    val routeRunning by vm.routeRunning.collectAsStateWithLifecycle()
    val routePaused by vm.routePaused.collectAsStateWithLifecycle()
    val w = workouts.firstOrNull { it.id == activeId }
    var ytError by remember(activeId) { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (w == null) {
            Text("Nessun allenamento selezionato. Scegline uno nella scheda Allenamenti e premi Avvia.")
        } else {
            Text(w.name, style = MaterialTheme.typography.titleLarge)

            val isRoute = w.mode == WorkoutMode.ROUTE
            val running = if (isRoute) routeRunning else p.running
            val paused = if (isRoute) routePaused else p.paused

            val speed = live.speedKmh ?: 0.0
            val playing = running && !paused && speed >= 0.5
            val ref = w.video.refSpeedKmh
            val rate = if (speed >= 0.5) ((speed / ref) * 20).roundToInt() / 20f else 1f

            if (w.video.source.isNotEmpty()) {
                when (w.video.type) {
                    VideoType.LOCAL ->
                        LocalVideo(
                            uri = Uri.parse(w.video.source),
                            rate = rate,
                            playing = playing,
                            modifier = Modifier.fillMaxWidth(),
                            startSec = vm.videoPosSec,
                            onDuration = { vm.videoDurSec = it },
                            onPosition = { vm.onVideoPosition(it) },
                        )
                    VideoType.YOUTUBE -> {
                        val id = youtubeId(w.video.source)
                        if (id == null) {
                            Text("Link YouTube non valido")
                        } else {
                            YouTubeVideo(
                                videoId = id,
                                rate = youtubeRate(rate),
                                playing = playing,
                                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                                onError = { ytError = it },
                                startSec = vm.videoPosSec,
                                onDuration = { vm.videoDurSec = it },
                                onPosition = { vm.onVideoPosition(it) },
                            )
                        }
                        ytError?.let {
                            Text("YouTube ha rifiutato il video (codice $it): potrebbe non essere incorporabile.")
                        }
                    }
                    VideoType.NONE -> {}
                }
            }

            LiveCard(live)

            if (isRoute) {
                RouteControls(vm, w, connected)
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (p.running && p.stepIndex >= 0) {
                            Text("Step ${p.stepIndex + 1}/${p.stepCount} · restano ${mmss(p.stepRemainingSec)}")
                            Text("Totale rimanente ${mmss(p.totalRemainingSec)}")
                        }
                        if (p.message.isNotEmpty()) Text(p.message)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!p.running) {
                                Button(onClick = { vm.startWorkout(w.id) }, enabled = connected) { Text("Avvia") }
                            } else {
                                Button(onClick = { vm.runner.pauseResume() }) {
                                    Text(if (p.paused) "Riprendi" else "Pausa")
                                }
                                OutlinedButton(onClick = { vm.runner.stop() }) { Text("Stop") }
                            }
                        }
                    }
                }
            }

            if (w.video.source.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Calibra video: ripreso a ${fmt(ref)} km/h", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.setVideoRef(w.id, ref - 0.5) }) { Text("−0,5") }
                            OutlinedButton(onClick = { vm.setVideoRef(w.id, ref + 0.5) }) { Text("+0,5") }
                        }
                        Text(
                            "Se il video scorre troppo veloce, aumenta il valore; se è troppo lento, diminuiscilo.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}


/** Importa un allenamento dal JSON del Treadmill Workout Planner: da file oppure incollando il testo. */
@Composable
fun ImportDialog(vm: TapisViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                msg = ""
            } catch (e: Exception) {
                msg = "Impossibile leggere il file: ${e.message}"
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importa allenamento (JSON)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Scegli file .json") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; msg = "" },
                    label = { Text("...oppure incolla il JSON") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp),
                )
                if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (done) {
                TextButton(onClick = onDismiss) { Text("Chiudi") }
            } else {
                TextButton(
                    onClick = {
                        msg = vm.importWorkout(text)
                        done = msg.startsWith("Importato")
                    },
                    enabled = text.isNotBlank(),
                ) { Text("Importa") }
            }
        },
        dismissButton = { if (!done) TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
