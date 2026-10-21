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
    val stepCal = MutableStateFlow(store.loadStepCal())   // taratura della lunghezza del passo (4 e 5 km/h)
    val stepsEst = MutableStateFlow<Int?>(null)           // passi stimati dall'ultimo azzeramento del tapis
    private var stepBase = 0.0          // passi alla distanza segnata dal tapis (frazionari)
    private var stepShown = 0           // ultimo valore pubblicato (non scende mai, salvo azzeramento)
    private var stepLastDist: Int? = null
    private var stepChangeAt = 0L       // quando la distanza e' cambiata l'ultima volta
    private var stepDelta = 1.0         // ultimo incremento di distanza (m): la "risoluzione" del tapis
    private var stepInterval = 1.5      // ultimo intervallo tra due cambi di distanza (s)
    private var stepSpeed = 0.0

    /** Pubblica i passi: valore alla distanza del tapis + una piccola anticipazione tra due dati (mai oltre un incremento). */
    private fun publishSteps() {
        if (stepLastDist == null) return
        var v = stepBase
        if (stepSpeed >= 0.5) {
            val dt = (SystemClock.elapsedRealtime() - stepChangeAt) / 1000.0
            val extraM = minOf(stepSpeed / 3.6 * dt, stepDelta, stepSpeed / 3.6 * stepInterval)
            v += extraM / stepCal.value.strideM(stepSpeed)
        }
        val r = v.roundToInt()
        if (r > stepShown) stepShown = r
        stepsEst.value = stepShown
    }

    /** Un nuovo dato di distanza dal tapis: i passi seguono SEMPRE la distanza del tapis (che e' quella che conta). */
    private fun syncSteps(d: Int) {
        val now = SystemClock.elapsedRealtime()
        val stride = stepCal.value.strideM(if (stepSpeed > 0.0) stepSpeed else 4.0)
        val prev = stepLastDist
        stepLastDist = d
        if (prev == null || d < prev) {                  // primo dato o tapis azzerato
            stepBase = d / stride
            stepShown = stepBase.roundToInt()
            stepChangeAt = now
            stepDelta = 1.0
            stepInterval = 1.5
            stepsEst.value = stepShown
            return
        }
        if (d == prev) return
        stepBase += (d - prev) / stride
        stepDelta = (d - prev).toDouble()
        stepInterval = ((now - stepChangeAt) / 1000.0).coerceIn(0.5, 10.0)
        stepChangeAt = now
    }
    val maxIncline = MutableStateFlow(store.loadMaxIncline())

    // video della scheda Manuale (indipendente dagli allenamenti)
    // schermo intero in orizzontale: true = l'utente l'ha chiuso (si riattiva ruotando in verticale o col pulsante)
    val fsDismissed = MutableStateFlow(false)
    val manualFsDismissed = MutableStateFlow(false)   // lo stesso, per la scheda Manuale

    val manualVideo = MutableStateFlow(store.loadManualVideo())
    @Volatile var manualVideoPos = 0.0

    fun setManualVideo(v: VideoRef) {
        if (v.source != manualVideo.value.source) manualVideoPos = 0.0
        manualVideo.value = v
        store.saveManualVideo(v)
    }
    val manualSpeed = MutableStateFlow(3.0)
    val manualIncline = MutableStateFlow(0.0)

    init {
        // limiti applicati a ogni comando direttamente nel modulo Bluetooth
        ble.speedLimit = maxSpeed.value
        ble.inclineLimit = maxIncline.value

        // Passi stimati: seguono la distanza segnata dal tapis (se scendi dalla pedana e il tapis ferma il conto, si
        // fermano anche loro). Tra un dato e l'altro avanzano un poco in base alla velocita', senza superare un
        // incremento di distanza. La lunghezza del passo dipende dalla velocita'.
        viewModelScope.launch {
            ble.live.collect { l ->
                stepSpeed = l.speedKmh ?: 0.0
                l.distanceM?.let { syncSteps(it) }
                publishSteps()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(200)
                publishSteps()
            }
        }

        // Se si lascia la scheda "In corso" il lettore video viene chiuso: il video "scorre" comunque in modo
        // virtuale alla velocita' reale del tapis roulant, cosi' il profilo del percorso resta allineato e
        // al ritorno il video riparte dal punto giusto.
        viewModelScope.launch {
            while (true) {
                delay(500)
                val moving = (routeRunning.value && !routePaused.value) ||
                    runner.progress.value.let { it.running && !it.paused }
                if (!moving) continue
                if (SystemClock.elapsedRealtime() - lastPlayerReportMs < 1500) continue   // lettore visibile
                val w = workouts.value.firstOrNull { it.id == activeId.value } ?: continue
                if (!w.video.active) continue
                val speed = ble.live.value.speedKmh ?: 0.0
                if (speed < 0.5) continue
                val rate = ((speed / w.video.refSpeedKmh) * 20).roundToInt() / 20.0
                var pos = videoPosSec + 0.5 * rate
                if (videoDurSec > 1.0 && pos >= videoDurSec) pos %= videoDurSec
                applyVideoPosition(pos)
            }
        }
    }

    // Percorso virtuale: velocita' libera, pendenza guidata dal video
    val routeRunning = MutableStateFlow(false)
    val routePaused = MutableStateFlow(false)
    val routeSpeed = MutableStateFlow(3.0)
    val routeIncline = MutableStateFlow(0.0)
    val routeMessage = MutableStateFlow("")
    val videoPos = MutableStateFlow(0.0)            // posizione del video (s), per la visualizzazione
    @Volatile var videoPosSec = 0.0
    @Volatile var videoDurSec = 0.0                 // durata del video (0 = sconosciuta), per il giro virtuale
    @Volatile private var lastPlayerReportMs = 0L   // ultima volta che il lettore ha riportato la posizione
    private var routeJob: Job? = null
    private var lastProfileIncline: Double? = null
    private var lastInclineSentMs = 0L
    private var lastProfileSpeed: Double? = null   // ultima velocita' obiettivo dal profilo
    private var speedSlewing = false               // true finche' la velocita' non ha raggiunto l'obiettivo
    private var lastSpeedSentMs = 0L

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

    /** Importa un allenamento dal JSON del Treadmill Workout Planner. Restituisce il messaggio da mostrare. */
    fun importWorkout(text: String): String {
        val r = parsePlannerWorkout(text)
        val w = r.workout ?: return r.message
        workouts.update { it + w }
        saveWorkouts()
        return r.message
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
                speedKmh = last?.speedKmh,
            ),
        )
    }

    fun updateRoutePoint(p: RoutePoint) = editWorkout { w ->
        w.copy(route = w.route.map { if (it.id == p.id) p else it })
    }

    fun removeRoutePoint(id: Long) = editWorkout { w -> w.copy(route = w.route.filterNot { it.id == id }) }

    /** Salva (o sostituisce, entro 2 s) un punto del profilo alla posizione corrente del video. */
    fun recordRoutePoint(id: Long, sec: Double, incline: Double, speed: Double) =
        updateWorkout(id) { w ->
            val s = sec.roundToInt().coerceAtLeast(0)
            val others = w.route.filter { abs(it.videoSec - s) > 2 }
            w.copy(route = (others + RoutePoint(videoSec = s, inclinePct = incline, speedKmh = speed)).sortedBy { it.videoSec })
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
    /** Step da cui partire (scelto nella scheda In corso prima di premere Avvia). */
    val startStep = MutableStateFlow(0)

    /** Durante l'allenamento salta subito allo step; da fermo sceglie lo step di partenza. */
    fun selectStep(index: Int) {
        if (runner.progress.value.running) runner.jumpTo(index) else startStep.value = index
    }

    fun startWorkout(id: Long, fromStep: Int = 0) {
        if (runner.progress.value.running || routeRunning.value) return
        val w = workouts.value.firstOrNull { it.id == id } ?: return
        if (w.mode == WorkoutMode.ROUTE) {
            startRoute(id)
        } else {
            activeId.value = id
            resetVideoPosition()
            savedSpeedOffset = 0.0
            savedInclineOffset = 0.0
            runner.start(w.flatSteps(), maxSpeed.value, maxIncline.value, fromStep)
            startStep.value = 0
        }
    }

    // correzioni manuali durante un allenamento a intervalli
    private var savedSpeedOffset = 0.0
    private var savedInclineOffset = 0.0

    fun adjustIntervalSpeed(delta: Double) = runner.adjustSpeed(delta)
    fun adjustIntervalIncline(delta: Double) = runner.adjustIncline(delta)

    /**
     * Scrive nell'allenamento le correzioni fatte a mano: somma la differenza a ogni step (nei limiti impostati).
     * La sessione in corso continua con gli stessi valori; una seconda pressione salva solo le nuove correzioni.
     */
    fun saveIntervalOffsets(id: Long) {
        val ds = runner.speedOffset - savedSpeedOffset
        val di = runner.inclineOffset - savedInclineOffset
        if (ds == 0.0 && di == 0.0) return
        val maxS = maxSpeed.value
        val maxI = maxIncline.value
        updateWorkout(id) { w ->
            w.copy(blocks = w.blocks.map { b ->
                b.copy(steps = b.steps.map { st ->
                    st.copy(
                        speedKmh = ((st.speedKmh + ds).coerceIn(1.0, maxS) * 10).roundToInt() / 10.0,
                        inclinePct = roundIncline((st.inclinePct + di).coerceIn(0.0, maxI)),
                    )
                })
            })
        }
        savedSpeedOffset = runner.speedOffset
        savedInclineOffset = runner.inclineOffset
    }

    private fun resetVideoPosition() {
        videoPosSec = 0.0
        videoPos.value = 0.0
        videoDurSec = 0.0
        lastPlayerReportMs = 0L
    }

    // ---------------------------------------------------------------- percorso virtuale
    fun startRoute(id: Long) {
        if (routeJob?.isActive == true || runner.progress.value.running) return
        val w = workouts.value.firstOrNull { it.id == id } ?: return
        activeId.value = id
        resetVideoPosition()
        lastProfileIncline = null
        lastProfileSpeed = null
        speedSlewing = false
        lastSpeedSentMs = SystemClock.elapsedRealtime()
        routeSpeed.value = routeSpeed.value.coerceIn(1.0, maxSpeed.value)
        w.route.speedAt(0.0)?.let { v0 ->
            val c = ((v0 * 10).roundToInt() / 10.0).coerceIn(1.0, maxSpeed.value)
            routeSpeed.value = c
            lastProfileSpeed = c
        }
        routeMessage.value = "Avvio..."
        routePaused.value = false
        routeRunning.value = true
        routeJob = viewModelScope.launch {
            ble.requestControl()
            ble.start()
            ble.awaitBeltMoving()
            val prof = w.route.inclineAt(0.0)
            val inc0 = roundIncline(prof ?: routeIncline.value).coerceIn(0.0, maxIncline.value)
            routeIncline.value = inc0
            if (prof != null) lastProfileIncline = inc0
            lastInclineSentMs = SystemClock.elapsedRealtime()
            ble.setSpeed(routeSpeed.value)
            ble.setIncline(inc0)
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
                ble.awaitBeltMoving()
                ble.setSpeed(routeSpeed.value)
                ble.setIncline(routeIncline.value)
            }
        }
    }

    fun routeAdjustSpeed(delta: Double) {
        val v = ((routeSpeed.value + delta) * 10).roundToInt() / 10.0
        routeSpeed.value = v.coerceIn(1.0, maxSpeed.value)
        if (routeRunning.value && !routePaused.value) ble.setSpeed(routeSpeed.value)
        autoRecordRoutePoint()
    }

    /**
     * Durante un percorso col video, ogni correzione manuale viene registrata come punto del profilo alla
     * posizione corrente: cosi' non viene piu' sovrascritta dal profilo e resta per i giri successivi.
     */
    private fun autoRecordRoutePoint() {
        if (!routeRunning.value || routePaused.value) return
        val id = activeId.value ?: return
        val w = workouts.value.firstOrNull { it.id == id } ?: return
        if (w.mode != WorkoutMode.ROUTE || !w.video.active) return
        recordRoutePoint(id, videoPosSec, routeIncline.value, routeSpeed.value)
    }

    /** Correzione manuale: vale fino al prossimo cambio di pendenza previsto dal profilo. */
    fun routeAdjustIncline(delta: Double) {
        routeIncline.value = (routeIncline.value + delta).coerceIn(0.0, maxIncline.value)
        lastInclineSentMs = SystemClock.elapsedRealtime()
        if (routeRunning.value && !routePaused.value) ble.setIncline(routeIncline.value)
        autoRecordRoutePoint()
    }

    /** Chiamata dal lettore video (circa 2 volte al secondo) con la posizione in secondi. */
    fun onVideoPosition(sec: Double) {
        lastPlayerReportMs = SystemClock.elapsedRealtime()
        applyVideoPosition(sec)
    }

    private fun applyVideoPosition(sec: Double) {
        videoPosSec = sec
        videoPos.value = sec
        if (!routeRunning.value || routePaused.value) return
        val w = workouts.value.firstOrNull { it.id == activeId.value } ?: return
        if (w.mode != WorkoutMode.ROUTE) return
        val now = SystemClock.elapsedRealtime()

        // Velocita' dal profilo (se i punti la prevedono): al massimo 0,3 km/h ogni 2 s, mai oltre il limite
        w.route.speedAt(sec)?.let { raw ->
            val tgt = ((raw * 10).roundToInt() / 10.0).coerceIn(1.0, maxSpeed.value)
            if ((tgt != lastProfileSpeed || speedSlewing) && now - lastSpeedSentMs >= 2000) {
                val cur = routeSpeed.value
                val next = (((cur + (tgt - cur).coerceIn(-0.3, 0.3)) * 10).roundToInt() / 10.0)
                    .coerceIn(1.0, maxSpeed.value)
                lastProfileSpeed = tgt
                speedSlewing = next != tgt
                lastSpeedSentMs = now
                if (next != cur) {
                    routeSpeed.value = next
                    ble.setSpeed(next)
                }
            }
        }

        // Pendenza dal profilo
        val target = w.route.inclineAt(sec)
            ?.let { roundIncline(it).coerceIn(0.0, maxIncline.value) } ?: return
        if (target == lastProfileIncline) return
        if (now - lastInclineSentMs < 2000) return
        lastProfileIncline = target
        lastInclineSentMs = now
        routeIncline.value = target
        ble.setIncline(target)
    }

    fun setStepCal(cm4: Int, cm5: Int) {
        val c = StepCal(cm4.coerceIn(40, 110), cm5.coerceIn(40, 120))
        stepCal.value = c
        store.saveStepCal(c)
    }

    fun setMaxSpeed(v: Double) {
        val c = v.coerceIn(3.0, 15.0)
        maxSpeed.value = c
        ble.speedLimit = c
        store.saveMaxSpeed(c)
    }

    fun setMaxIncline(v: Double) {
        val c = v.coerceIn(0.0, 15.0)
        maxIncline.value = c
        ble.inclineLimit = c
        store.saveMaxIncline(c)
    }

    // ---------------------------------------------------------------- controllo manuale
    fun manualStart() {
        viewModelScope.launch {
            ble.requestControl()
            ble.start()
            ble.awaitBeltMoving()
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
        manualIncline.value = (manualIncline.value + delta).coerceIn(0.0, maxIncline.value)
        ble.setIncline(manualIncline.value)
    }

    override fun onCleared() {
        ble.disconnect()
    }
}
