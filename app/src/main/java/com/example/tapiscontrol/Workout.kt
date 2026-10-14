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

enum class VideoType { NONE, LOCAL, YOUTUBE }

/** STEPS = allenamento a intervalli; ROUTE = percorso virtuale (pendenza guidata dal video, velocita' libera). */
enum class WorkoutMode { STEPS, ROUTE }

/** Punto del profilo di pendenza: da `videoSec` del video in poi la pendenza tende a `inclinePct`. */
data class RoutePoint(
    val id: Long = newStepId(),
    val videoSec: Int,
    val inclinePct: Double,
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

/** Video collegato a un allenamento. refSpeedKmh = velocita' a cui e' stato girato il video. */
data class VideoRef(
    val type: VideoType = VideoType.NONE,
    val source: String = "",
    val refSpeedKmh: Double = 5.0,
)

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
            w.route.forEach { a.put(JSONObject().put("t", it.videoSec).put("i", it.inclinePct)) }
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
                    RoutePoint(videoSec = r.getInt("t"), inclinePct = r.getDouble("i"))
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

    fun loadMaxSpeed(): Double = prefs.getFloat("max", 8f).toDouble()
    fun saveMaxSpeed(v: Double) = prefs.edit().putFloat("max", v.toFloat()).apply()

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
        val message: String = "",
    )

    val progress = MutableStateFlow(Progress())

    /** Durata (s) della rampa all'inizio di ogni step (tranne il primo). */
    var rampSec = 6

    private var job: Job? = null
    @Volatile private var paused = false

    private fun round1(x: Double) = (x * 10).roundToInt() / 10.0

    fun start(steps: List<Step>, maxSpeed: Double) {
        if (steps.isEmpty() || job?.isActive == true) return
        paused = false
        job = scope.launch { run(steps, maxSpeed) }
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

    private suspend fun run(steps: List<Step>, maxSpeed: Double) {
        val total = steps.sumOf { it.durationSec }
        var elapsedTotal = 0
        progress.value = Progress(
            running = true, stepCount = steps.size, totalRemainingSec = total, message = "Avvio...",
        )

        ble.requestControl()
        ble.start()
        delay(START_DELAY_MS)

        var prevSpeed = round1(min(steps[0].speedKmh, maxSpeed))
        var prevInc = steps[0].inclinePct.roundToInt().toDouble()
        var sentSpeed: Double? = null
        var sentInc: Double? = null

        for ((idx, step) in steps.withIndex()) {
            val tgtSpeed = round1(min(step.speedKmh, maxSpeed))
            val tgtInc = step.inclinePct.roundToInt().toDouble()

            for (t in 0 until step.durationSec) {
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
                    delay(START_DELAY_MS)
                    sentSpeed = null
                    sentInc = null
                }

                val f = if (idx == 0 || rampSec <= 0) 1.0
                else ((t + 1).toDouble() / rampSec).coerceAtMost(1.0)
                val sp = round1(prevSpeed + (tgtSpeed - prevSpeed) * f)
                val inc = (prevInc + (tgtInc - prevInc) * f).roundToInt().toDouble()

                if (sp != sentSpeed) { ble.setSpeed(sp); sentSpeed = sp }
                if (inc != sentInc) { ble.setIncline(inc); sentInc = inc }

                progress.value = Progress(
                    running = true,
                    stepIndex = idx,
                    stepCount = steps.size,
                    stepRemainingSec = step.durationSec - t,
                    totalRemainingSec = total - elapsedTotal,
                )
                delay(1000)
                elapsedTotal++
            }
            prevSpeed = tgtSpeed
            prevInc = tgtInc
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
