package com.example.tapiscontrol

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

fun newStepId(): Long = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)

data class Step(
    val id: Long = newStepId(),
    val durationSec: Int,
    val speedKmh: Double,
    val inclinePct: Double,
)

/** Gruppo di step da ripetere `repeat` volte. */
data class Block(
    val id: Long = newStepId(),
    val name: String = "Blocco",
    val repeat: Int = 1,
    val steps: List<Step>,
)

/** La pendenza di questo tapis roulant cambia di 1 % per volta (console, manubrio e comandi Bluetooth). */
const val INCLINE_STEP = 1.0
fun roundIncline(x: Double): Double = (x / INCLINE_STEP).roundToInt() * INCLINE_STEP

enum class VideoType { NONE, LOCAL, YOUTUBE, ANIMATION }

/** STEPS = allenamento a intervalli; ROUTE = percorso virtuale (pendenza guidata dal video, velocita' libera). */
enum class WorkoutMode { STEPS, ROUTE }

/** Punto del profilo di pendenza: da `videoSec` del video in poi la pendenza tende a `inclinePct`. */
data class RoutePoint(
    val id: Long = newStepId(),
    val videoSec: Int,
    val inclinePct: Double,
    val speedKmh: Double? = null,   // opzionale: se assente la velocita' resta a controllo manuale
)

/** Pendenza (%) alla posizione `sec` del video: interpolazione lineare tra i punti, costante prima/dopo. */
fun List<RoutePoint>.inclineAt(sec: Double): Double? {
    if (isEmpty()) return null
    val p = sortedBy { it.videoSec }
    if (sec <= p.first().videoSec) return p.first().inclinePct
    if (sec >= p.last().videoSec) return p.last().inclinePct
    val i = p.indexOfLast { it.videoSec <= sec }
    val a = p[i]
    val b = p[i + 1]
    val span = (b.videoSec - a.videoSec).toDouble()
    return a.inclinePct + (b.inclinePct - a.inclinePct) * ((sec - a.videoSec) / span)
}

/** Velocita' (km/h) alla posizione `sec` del video: interpolazione tra i punti che hanno una velocita'. */
fun List<RoutePoint>.speedAt(sec: Double): Double? {
    val p = filter { it.speedKmh != null }.sortedBy { it.videoSec }
    if (p.isEmpty()) return null
    if (sec <= p.first().videoSec) return p.first().speedKmh
    if (sec >= p.last().videoSec) return p.last().speedKmh
    val i = p.indexOfLast { it.videoSec <= sec }
    val a = p[i]
    val b = p[i + 1]
    val span = (b.videoSec - a.videoSec).toDouble()
    return a.speedKmh!! + (b.speedKmh!! - a.speedKmh!!) * ((sec - a.videoSec) / span)
}

/** Video collegato a un allenamento. refSpeedKmh = velocita' a cui e' stato girato il video. */
data class VideoRef(
    val type: VideoType = VideoType.NONE,
    val source: String = "",
    val refSpeedKmh: Double = 5.0,
)

/** C'e' un "video" da mostrare: un file/link oppure l'animazione (che non ha sorgente). */
val VideoRef.active: Boolean get() = type == VideoType.ANIMATION || source.isNotEmpty()

data class Workout(
    val id: Long = newStepId(),
    val name: String,
    val blocks: List<Block>,
    val video: VideoRef = VideoRef(),
    val mode: WorkoutMode = WorkoutMode.STEPS,
    val route: List<RoutePoint> = emptyList(),
)

fun Workout.flatSteps(): List<Step> =
    blocks.flatMap { b -> (1..max(1, b.repeat)).flatMap { b.steps } }

/** Nome del blocco a cui appartiene lo step (indice "piatto", ripetizioni comprese). */
fun Workout.blockNameAt(flat: Int): String {
    var i = 0
    for (b in blocks) {
        val n = b.steps.size * b.repeat
        if (flat < i + n) return b.name
        i += n
    }
    return ""
}

fun Workout.totalSec(): Int = flatSteps().sumOf { it.durationSec }

fun Block.freshIds(): Block = copy(id = newStepId(), steps = steps.map { it.copy(id = newStepId()) })

/** Salvataggio locale di allenamenti, libreria blocchi e limite di velocita'. */
class WorkoutStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("workouts", Context.MODE_PRIVATE)

    private fun stepToJson(s: Step) =
        JSONObject().put("d", s.durationSec).put("s", s.speedKmh).put("i", s.inclinePct)

    private fun stepFromJson(o: JSONObject) =
        Step(durationSec = o.getInt("d"), speedKmh = o.getDouble("s"), inclinePct = o.getDouble("i"))

    private fun blockToJson(b: Block) = JSONObject()
        .put("n", b.name)
        .put("r", b.repeat)
        .put("steps", JSONArray().also { a -> b.steps.forEach { a.put(stepToJson(it)) } })

    private fun blockFromJson(o: JSONObject): Block {
        val a = o.getJSONArray("steps")
        return Block(
            name = o.optString("n", "Blocco"),
            repeat = o.optInt("r", 1),
            steps = (0 until a.length()).map { stepFromJson(a.getJSONObject(it)) },
        )
    }

    private fun workoutToJson(w: Workout) = JSONObject()
        .put("id", w.id)
        .put("n", w.name)
        .put("b", JSONArray().also { a -> w.blocks.forEach { a.put(blockToJson(it)) } })
        .put("vt", w.video.type.name)
        .put("vs", w.video.source)
        .put("vr", w.video.refSpeedKmh)
        .put("m", w.mode.name)
        .put("rt", JSONArray().also { a ->
            w.route.forEach { pt ->
                val o = JSONObject().put("t", pt.videoSec).put("i", pt.inclinePct)
                pt.speedKmh?.let { o.put("v", it) }
                a.put(o)
            }
        })

    private fun workoutFromJson(o: JSONObject): Workout {
        val a = o.getJSONArray("b")
        return Workout(
            id = o.getLong("id"),
            name = o.getString("n"),
            blocks = (0 until a.length()).map { blockFromJson(a.getJSONObject(it)) },
            video = VideoRef(
                type = VideoType.valueOf(o.optString("vt", "NONE")),
                source = o.optString("vs", ""),
                refSpeedKmh = o.optDouble("vr", 5.0),
            ),
            mode = try {
                WorkoutMode.valueOf(o.optString("m", "STEPS"))
            } catch (e: Exception) {
                WorkoutMode.STEPS
            },
            route = o.optJSONArray("rt")?.let { a ->
                (0 until a.length()).map {
                    val r = a.getJSONObject(it)
                    RoutePoint(
                        videoSec = r.getInt("t"),
                        inclinePct = r.getDouble("i"),
                        speedKmh = if (r.has("v")) r.getDouble("v") else null,
                    )
                }
            } ?: emptyList(),
        )
    }

    fun loadWorkouts(): List<Workout> {
        val raw = prefs.getString("workouts", null) ?: return listOf(sample())
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { workoutFromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            listOf(sample())
        }
    }

    fun saveWorkouts(list: List<Workout>) {
        val a = JSONArray()
        list.forEach { a.put(workoutToJson(it)) }
        prefs.edit().putString("workouts", a.toString()).apply()
    }

    fun loadBlockLibrary(): List<Block> {
        val raw = prefs.getString("blocklib", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { blockFromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveBlockLibrary(list: List<Block>) {
        val a = JSONArray()
        list.forEach { a.put(blockToJson(it)) }
        prefs.edit().putString("blocklib", a.toString()).apply()
    }

    fun loadStepLen(): Int = prefs.getInt("steplen", 70)
    fun saveStepLen(cm: Int) = prefs.edit().putInt("steplen", cm).apply()

    fun loadMaxSpeed(): Double = prefs.getFloat("max", 8f).toDouble()
    fun saveMaxSpeed(v: Double) = prefs.edit().putFloat("max", v.toFloat()).apply()

    fun loadManualVideo(): VideoRef = VideoRef(
        type = try { VideoType.valueOf(prefs.getString("mvt", "NONE") ?: "NONE") } catch (_: Exception) { VideoType.NONE },
        source = prefs.getString("mvs", "") ?: "",
        refSpeedKmh = prefs.getFloat("mvr", 5f).toDouble(),
    )

    fun saveManualVideo(v: VideoRef) = prefs.edit()
        .putString("mvt", v.type.name).putString("mvs", v.source).putFloat("mvr", v.refSpeedKmh.toFloat()).apply()

    fun loadMaxIncline(): Double = prefs.getFloat("maxinc", 8f).toDouble()
    fun saveMaxIncline(v: Double) = prefs.edit().putFloat("maxinc", v.toFloat()).apply()

    private fun sample() = Workout(
        name = "Camminata base",
        blocks = listOf(
            Block(name = "Riscaldamento", steps = listOf(Step(durationSec = 300, speedKmh = 4.0, inclinePct = 0.0))),
            Block(
                name = "Intervalli", repeat = 4,
                steps = listOf(
                    Step(durationSec = 120, speedKmh = 5.5, inclinePct = 2.0),
                    Step(durationSec = 60, speedKmh = 4.0, inclinePct = 0.0),
                ),
            ),
            Block(name = "Defaticamento", steps = listOf(Step(durationSec = 180, speedKmh = 3.5, inclinePct = 0.0))),
        ),
    )
}

private const val START_DELAY_MS = 4000L     // attesa dopo "Start" (molti tapis fanno un conto alla rovescia)
private const val COOLDOWN_SPEED = 2.0

/** Esegue in automatico la lista di step, con rampe graduali tra uno step e l'altro. */
class WorkoutRunner(private val ble: TreadmillBle, private val scope: CoroutineScope) {

    data class Progress(
        val running: Boolean = false,
        val paused: Boolean = false,
        val stepIndex: Int = -1,
        val stepCount: Int = 0,
        val stepRemainingSec: Int = 0,
        val totalRemainingSec: Int = 0,
        val elapsedSec: Int = 0,          // tempo reale trascorso (la pausa non conta)
        val message: String = "",
    )

    val progress = MutableStateFlow(Progress())

    /** Durata (s) della rampa all'inizio di ogni step (tranne il primo). */
    var rampSec = 6

    private var job: Job? = null
    @Volatile private var paused = false
    @Volatile private var jumpTarget: Int? = null   // step (indice piatto) a cui saltare subito

    /** Salta subito allo step indicato (anche in pausa), senza attendere gli step intermedi. */
    fun jumpTo(index: Int) {
        if (job?.isActive == true) jumpTarget = index
    }

    // Correzioni manuali: si sommano a velocita' e pendenza di OGNI step da qui alla fine, cosi' non si perdono.
    @Volatile var speedOffset = 0.0
        private set
    @Volatile var inclineOffset = 0.0
        private set
    val speedOffsetFlow = MutableStateFlow(0.0)
    val inclineOffsetFlow = MutableStateFlow(0.0)

    fun adjustSpeed(delta: Double) {
        speedOffset = round1((speedOffset + delta).coerceIn(-5.0, 5.0))
        speedOffsetFlow.value = speedOffset
    }

    fun adjustIncline(delta: Double) {
        inclineOffset = roundHalf((inclineOffset + delta).coerceIn(-8.0, 8.0))
        inclineOffsetFlow.value = inclineOffset
    }

    private fun round1(x: Double) = (x * 10).roundToInt() / 10.0
    private fun roundHalf(x: Double) = roundIncline(x)   // nome storico: ora arrotonda al passo del tapis (1 %)

    fun start(steps: List<Step>, maxSpeed: Double, maxIncline: Double, startIndex: Int = 0) {
        if (steps.isEmpty() || job?.isActive == true) return
        paused = false
        jumpTarget = null
        speedOffset = 0.0
        inclineOffset = 0.0
        speedOffsetFlow.value = 0.0
        inclineOffsetFlow.value = 0.0
        job = scope.launch { run(steps, maxSpeed, maxIncline, startIndex.coerceIn(0, steps.lastIndex)) }
    }

    fun pauseResume() {
        if (job?.isActive != true) return
        paused = !paused
        progress.update { it.copy(paused = paused) }
        if (paused) ble.pause()
    }

    fun stop() {
        val wasRunning = progress.value.running
        job?.cancel()
        job = null
        paused = false
        if (wasRunning) ble.stop()
        progress.value = Progress(message = "Fermato")
    }

    private suspend fun run(steps: List<Step>, maxSpeed: Double, maxIncline: Double, startIndex: Int) {
        val total = steps.sumOf { it.durationSec }
        var elapsedTotal = steps.take(startIndex).sumOf { it.durationSec }
        progress.value = Progress(
            running = true, stepCount = steps.size, totalRemainingSec = total - elapsedTotal, message = "Avvio...",
        )

        ble.requestControl()
        ble.start()
        ble.awaitBeltMoving()

        var prevSpeed = round1(min(steps[startIndex].speedKmh, maxSpeed))
        var prevInc = roundHalf(min(steps[startIndex].inclinePct, maxIncline))
        var sentSpeed: Double? = null
        var sentInc: Double? = null

        var realElapsed = 0
        var idx = startIndex
        var firstStep = true   // il primo step applicato parte subito, senza rampa
        while (idx < steps.size) {
            val step = steps[idx]
            var tgtSpeed = prevSpeed
            var tgtInc = prevInc
            var jumpedTo: Int? = null

            var t = 0
            while (t < step.durationSec) {
                val j0 = jumpTarget
                if (j0 != null) {
                    jumpTarget = null
                    if (j0 in steps.indices) { jumpedTo = j0; break }
                }
                tgtSpeed = round1((step.speedKmh + speedOffset).coerceIn(1.0, maxSpeed))
                tgtInc = roundHalf((step.inclinePct + inclineOffset).coerceIn(0.0, maxIncline))
                if (!ble.connected.value) {
                    progress.value = Progress(
                        message = "Connessione persa! Il tapis roulant mantiene l'ultima velocita': " +
                            "fermalo con la chiave di sicurezza.",
                    )
                    return
                }
                if (paused) {
                    while (paused) delay(250)
                    ble.start()
                    ble.awaitBeltMoving()
                    sentSpeed = null
                    sentInc = null
                    if (jumpTarget != null) continue   // salto richiesto durante la pausa
                }

                val f = if (firstStep || rampSec <= 0) 1.0
                else ((t + 1).toDouble() / rampSec).coerceAtMost(1.0)
                val sp = round1(prevSpeed + (tgtSpeed - prevSpeed) * f)
                val inc = roundHalf(prevInc + (tgtInc - prevInc) * f)

                if (sp != sentSpeed) { ble.setSpeed(sp); sentSpeed = sp }
                if (inc != sentInc) { ble.setIncline(inc); sentInc = inc }

                progress.value = Progress(
                    running = true,
                    stepIndex = idx,
                    stepCount = steps.size,
                    stepRemainingSec = step.durationSec - t,
                    totalRemainingSec = total - elapsedTotal,
                    elapsedSec = realElapsed,
                )
                delay(1000)
                elapsedTotal++
                realElapsed++
                t++
            }

            if (jumpedTo != null) {
                // si riparte con una rampa dai valori che il tapis sta eseguendo adesso
                prevSpeed = sentSpeed ?: prevSpeed
                prevInc = sentInc ?: prevInc
                idx = jumpedTo
                elapsedTotal = steps.take(idx).sumOf { it.durationSec }
                firstStep = false
                continue
            }
            prevSpeed = tgtSpeed
            prevInc = tgtInc
            idx++
            firstStep = false
        }

        // Defaticamento graduale, poi stop
        progress.value = Progress(running = true, stepIndex = steps.size - 1, stepCount = steps.size,
            message = "Defaticamento...")
        ble.setIncline(0.0)
        var s = prevSpeed
        while (s > COOLDOWN_SPEED) {
            s = max(COOLDOWN_SPEED, s - 1.0)
            ble.setSpeed(s)
            delay(3000)
        }
        delay(3000)
        ble.stop()
        progress.value = Progress(message = "Allenamento completato")
    }
}
