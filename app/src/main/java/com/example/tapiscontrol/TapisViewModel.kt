package com.example.tapiscontrol

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class TapisViewModel(app: Application) : AndroidViewModel(app) {

    val ble = TreadmillBle(app)
    val runner = WorkoutRunner(ble, viewModelScope)
    private val store = WorkoutStore(app)

    val workouts = MutableStateFlow(store.loadWorkouts())
    val blockLibrary = MutableStateFlow(store.loadBlockLibrary())
    val editingId = MutableStateFlow<Long?>(null)   // allenamento aperto nell'editor
    val activeId = MutableStateFlow<Long?>(null)    // allenamento in corso / selezionato
    val maxSpeed = MutableStateFlow(store.loadMaxSpeed())
    val manualSpeed = MutableStateFlow(3.0)
    val manualIncline = MutableStateFlow(0.0)

    // Percorso virtuale: velocita' libera, pendenza guidata dal video
    val routeRunning = MutableStateFlow(false)
    val routePaused = MutableStateFlow(false)
    val routeSpeed = MutableStateFlow(3.0)
    val routeIncline = MutableStateFlow(0.0)
    val routeMessage = MutableStateFlow("")
    val videoPos = MutableStateFlow(0.0)            // posizione del video (s), per la visualizzazione
    @Volatile var videoPosSec = 0.0
    private var routeJob: Job? = null
    private var lastProfileIncline: Int? = null
    private var lastInclineSentMs = 0L

    // ---------------------------------------------------------------- allenamenti
    private fun saveWorkouts() = store.saveWorkouts(workouts.value)

    private fun updateWorkout(id: Long, f: (Workout) -> Workout) {
        workouts.update { l -> l.map { if (it.id == id) f(it) else it } }
        saveWorkouts()
    }

    fun editWorkout(f: (Workout) -> Workout) {
        editingId.value?.let { updateWorkout(it, f) }
    }

    fun newWorkout() {
        val w = Workout(
            name = "Nuovo allenamento",
            blocks = listOf(
                Block(name = "Blocco 1", steps = listOf(Step(durationSec = 300, speedKmh = 4.0, inclinePct = 0.0))),
            ),
        )
        workouts.update { it + w }
        saveWorkouts()
        editingId.value = w.id
    }

    fun duplicateWorkout(id: Long) {
        val w = workouts.value.firstOrNull { it.id == id } ?: return
        val copy = w.copy(id = newStepId(), name = w.name + " (copia)", blocks = w.blocks.map { it.freshIds() })
        workouts.update { it + copy }
        saveWorkouts()
    }

    fun deleteWorkout(id: Long) {
        workouts.update { l -> l.filterNot { it.id == id } }
        saveWorkouts()
        if (editingId.value == id) editingId.value = null
    }

    fun addBlock() = editWorkout { w ->
        w.copy(
            blocks = w.blocks + Block(
                name = "Blocco ${w.blocks.size + 1}",
                steps = listOf(Step(durationSec = 120, speedKmh = 4.0, inclinePct = 0.0)),
            ),
        )
    }

    fun updateBlock(b: Block) = editWorkout { w ->
        w.copy(blocks = w.blocks.map { if (it.id == b.id) b else it })
    }

    fun removeBlock(id: Long) = editWorkout { w -> w.copy(blocks = w.blocks.filterNot { it.id == id }) }

    fun setVideo(v: VideoRef) = editWorkout { it.copy(video = v) }

    fun setMode(m: WorkoutMode) = editWorkout { it.copy(mode = m) }

    fun addRoutePoint() = editWorkout { w ->
        val last = w.route.maxByOrNull { it.videoSec }
        w.copy(
            route = w.route + RoutePoint(
                videoSec = (last?.videoSec ?: -60) + 60,
                inclinePct = last?.inclinePct ?: 0.0,
            ),
        )
    }

    fun updateRoutePoint(p: RoutePoint) = editWorkout { w ->
        w.copy(route = w.route.map { if (it.id == p.id) p else it })
    }

    fun removeRoutePoint(id: Long) = editWorkout { w -> w.copy(route = w.route.filterNot { it.id == id }) }

    /** Salva (o sostituisce, entro 2 s) un punto del profilo alla posizione corrente del video. */
    fun recordRoutePoint(id: Long, sec: Double, incline: Double) =
        updateWorkout(id) { w ->
            val s = sec.roundToInt().coerceAtLeast(0)
            val others = w.route.filter { abs(it.videoSec - s) > 2 }
            w.copy(route = (others + RoutePoint(videoSec = s, inclinePct = incline)).sortedBy { it.videoSec })
        }

    fun setVideoRef(id: Long, ref: Double) =
        updateWorkout(id) { it.copy(video = it.video.copy(refSpeedKmh = ref.coerceIn(1.0, 30.0))) }

    // ---------------------------------------------------------------- libreria blocchi
    fun saveBlockToLibrary(b: Block) {
        blockLibrary.update { it + b.freshIds() }
        store.saveBlockLibrary(blockLibrary.value)
    }

    fun insertFromLibrary(index: Int) {
        val b = blockLibrary.value.getOrNull(index) ?: return
        editWorkout { w -> w.copy(blocks = w.blocks + b.freshIds()) }
    }

    fun deleteFromLibrary(index: Int) {
        blockLibrary.update { l -> l.filterIndexed { i, _ -> i != index } }
        store.saveBlockLibrary(blockLibrary.value)
    }

    // ---------------------------------------------------------------- esecuzione
    fun startWorkout(id: Long) {
        if (runner.progress.value.running || routeRunning.value) return
        val w = workouts.value.firstOrNull { it.id == id } ?: return
        if (w.mode == WorkoutMode.ROUTE) {
            startRoute(id)
        } else {
            activeId.value = id
            runner.start(w.flatSteps(), maxSpeed.value)
        }
    }

    // ---------------------------------------------------------------- percorso virtuale
    fun startRoute(id: Long) {
        if (routeJob?.isActive == true || runner.progress.value.running) return
        val w = workouts.value.firstOrNull { it.id == id } ?: return
        activeId.value = id
        lastProfileIncline = null
        routeSpeed.value = routeSpeed.value.coerceIn(1.0, maxSpeed.value)
        routeMessage.value = "Avvio..."
        routePaused.value = false
        routeRunning.value = true
        routeJob = viewModelScope.launch {
            ble.requestControl()
            ble.start()
            delay(4000)
            val prof = w.route.inclineAt(0.0)
            val inc0 = (prof ?: routeIncline.value).roundToInt().coerceIn(0, 15)
            routeIncline.value = inc0.toDouble()
            if (prof != null) lastProfileIncline = inc0
            lastInclineSentMs = SystemClock.elapsedRealtime()
            ble.setSpeed(routeSpeed.value)
            ble.setIncline(inc0.toDouble())
            routeMessage.value = ""
            while (ble.connected.value) delay(1000)
            routeRunning.value = false
            routeMessage.value = "Connessione persa! Il tapis roulant mantiene l'ultima velocita': " +
                "fermalo con la chiave di sicurezza."
        }
    }

    fun stopRoute() {
        val was = routeRunning.value
        routeJob?.cancel()
        routeJob = null
        routeRunning.value = false
        routePaused.value = false
        if (was) ble.stop()
        routeMessage.value = "Fermato"
    }

    fun routePauseResume() {
        if (!routeRunning.value) return
        if (!routePaused.value) {
            routePaused.value = true
            ble.pause()
        } else {
            routePaused.value = false
            viewModelScope.launch {
                ble.start()
                delay(4000)
                ble.setSpeed(routeSpeed.value)
                ble.setIncline(routeIncline.value)
            }
        }
    }

    fun routeAdjustSpeed(delta: Double) {
        val v = ((routeSpeed.value + delta) * 10).roundToInt() / 10.0
        routeSpeed.value = v.coerceIn(1.0, maxSpeed.value)
        if (routeRunning.value && !routePaused.value) ble.setSpeed(routeSpeed.value)
    }

    /** Correzione manuale: vale fino al prossimo cambio di pendenza previsto dal profilo. */
    fun routeAdjustIncline(delta: Double) {
        routeIncline.value = (routeIncline.value + delta).coerceIn(0.0, 15.0)
        lastInclineSentMs = SystemClock.elapsedRealtime()
        if (routeRunning.value && !routePaused.value) ble.setIncline(routeIncline.value)
    }

    /** Chiamata dal lettore video (circa 2 volte al secondo) con la posizione in secondi. */
    fun onVideoPosition(sec: Double) {
        videoPosSec = sec
        videoPos.value = sec
        if (!routeRunning.value || routePaused.value) return
        val w = workouts.value.firstOrNull { it.id == activeId.value } ?: return
        if (w.mode != WorkoutMode.ROUTE) return
        val target = w.route.inclineAt(sec)?.roundToInt()?.coerceIn(0, 15) ?: return
        if (target == lastProfileIncline) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastInclineSentMs < 2000) return
        lastProfileIncline = target
        lastInclineSentMs = now
        routeIncline.value = target.toDouble()
        ble.setIncline(target.toDouble())
    }

    fun setMaxSpeed(v: Double) {
        val c = v.coerceIn(3.0, 15.0)
        maxSpeed.value = c
        store.saveMaxSpeed(c)
    }

    // ---------------------------------------------------------------- controllo manuale
    fun manualStart() {
        viewModelScope.launch {
            ble.requestControl()
            ble.start()
            delay(4000)
            ble.setSpeed(manualSpeed.value)
            ble.setIncline(manualIncline.value)
        }
    }

    fun manualStop() = ble.stop()

    fun adjustSpeed(delta: Double) {
        val v = ((manualSpeed.value + delta) * 10).roundToInt() / 10.0
        manualSpeed.value = v.coerceIn(1.0, maxSpeed.value)
        ble.setSpeed(manualSpeed.value)
    }

    fun adjustIncline(delta: Double) {
        manualIncline.value = (manualIncline.value + delta).coerceIn(0.0, 15.0)
        ble.setIncline(manualIncline.value)
    }

    override fun onCleared() {
        ble.disconnect()
    }
}
