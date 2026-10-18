package com.example.tapiscontrol

import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private const val MILE_KM = 1.609344

class ImportResult(val workout: Workout?, val message: String)

/**
 * Converte il JSON del Treadmill Workout Planner (units, speedUnit, name, segments) in un Workout.
 * - ogni segmento diventa un Blocco;
 * - le rampe (start != end) vengono spezzate in step lunghi quanto speedInterval / inclineInterval
 *   (60 s se mancano), cosi' l'app le esegue gradualmente;
 * - velocita' in "pace" (M:SS) o mph vengono convertite in km/h;
 * - i campi della fascia cardio (targetHRZone, recovery...) vengono ignorati: l'app non li gestisce.
 */
fun parsePlannerWorkout(rawText: String): ImportResult {
    val text = rawText.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val root = try {
        JSONObject(text)
    } catch (e: Exception) {
        return ImportResult(null, "JSON non valido: ${e.message}")
    }

    val segs = root.optJSONArray("segments")
    if (segs == null || segs.length() == 0) return ImportResult(null, "Nessun segmento trovato (chiave \"segments\").")

    val imperial = root.optString("units", "metric") == "imperial"
    val pace = root.optString("speedUnit", "speed") == "pace"
    var ignoredHr = false

    fun toKmh(v: Any?): Double? {
        val raw: Double = when (v) {
            is Number -> {
                val x = v.toDouble()
                if (pace) return null   // in modalita' pace i numeri non sono ammessi
                x
            }
            is String -> {
                if (!pace) return null
                val p = v.trim().split(":")
                if (p.size != 2) return null
                val min = p[0].toIntOrNull() ?: return null
                val sec = p[1].toIntOrNull() ?: return null
                val total = min * 60 + sec
                if (total <= 0) return null
                3600.0 / total   // km/h (o mph se imperial)
            }
            else -> return null
        }
        return if (imperial) raw * MILE_KM else raw
    }

    val blocks = mutableListOf<Block>()
    for (i in 0 until segs.length()) {
        val s = segs.optJSONObject(i) ?: return ImportResult(null, "Segmento ${i + 1} non valido.")
        val label = s.optString("name", "Segmento ${i + 1}")
        val dur = s.optInt("durationSeconds", -1)
        if (dur <= 0) return ImportResult(null, "Segmento ${i + 1} (\"$label\"): durationSeconds mancante o non valido.")
        val sp0 = toKmh(s.opt("startSpeed"))
        val sp1 = toKmh(s.opt("endSpeed"))
        if (sp0 == null || sp1 == null) {
            return ImportResult(null, "Segmento ${i + 1} (\"$label\"): velocita' non valida per speedUnit \"${if (pace) "pace" else "speed"}\".")
        }
        val in0 = s.optDouble("startIncline", Double.NaN)
        val in1 = s.optDouble("endIncline", Double.NaN)
        if (in0.isNaN() || in1.isNaN()) return ImportResult(null, "Segmento ${i + 1} (\"$label\"): pendenza mancante.")
        if (s.has("targetHRZone") || s.has("recoveryHRZone")) ignoredHr = true

        val speedRamps = sp0 != sp1
        val inclineRamps = in0 != in1
        val intervals = buildList {
            if (speedRamps) add(s.optInt("speedInterval", 60))
            if (inclineRamps) add(s.optInt("inclineInterval", 60))
        }.map { max(10, it) }
        val n = if (intervals.isEmpty()) 1 else max(1, ceil(dur.toDouble() / intervals.min()).toInt())

        val base = dur / n
        var rest = dur - base * n
        val steps = (0 until n).map { k ->
            val f = if (n == 1) 0.0 else k.toDouble() / (n - 1)
            val d = base + if (rest > 0) { rest--; 1 } else 0
            Step(
                durationSec = d,
                speedKmh = ((sp0 + (sp1 - sp0) * f) * 10).roundToInt() / 10.0,
                inclinePct = ((in0 + (in1 - in0) * f) * 2).roundToInt() / 2.0,
            )
        }
        blocks += Block(name = label, repeat = 1, steps = steps)
    }

    val name = root.optString("name", "").ifBlank { "Allenamento importato" }
    val w = Workout(name = name, blocks = blocks)
    val total = w.totalSec() / 60
    val maxSp = w.flatSteps().maxOf { it.speedKmh }
    val maxInc = w.flatSteps().maxOf { it.inclinePct }
    val note = if (ignoredHr) " Zone cardio ignorate (non gestite dall'app)." else ""
    return ImportResult(
        w,
        "Importato \"$name\": ${blocks.size} blocchi, $total min, max ${"%.1f".format(maxSp)} km/h, " +
            "max ${"%.1f".format(maxInc)} %.$note",
    )
}

/** Testo da incollare in una chat con Claude per farsi generare un JSON importabile dall'app. */
fun plannerPrompt(maxSpeedKmh: Double, maxInclinePct: Double): String {
    val sp = "%.1f".format(java.util.Locale.US, maxSpeedKmh)
    val inc = "%.1f".format(java.util.Locale.US, maxInclinePct)
    return """
Sei il pianificatore di allenamenti per la mia app Tapis Control (tapis roulant controllato via Bluetooth).
Rispondi sempre in italiano.

PRIMA di generare l'allenamento, fammi queste domande (una alla volta o tutte insieme, come preferisci):
obiettivo (dimagrimento, resistenza, HIIT, recupero), livello, durata in minuti, comfort di velocita',
eventuali limitazioni fisiche (ginocchia, schiena...), peso corporeo (facoltativo, per stimare le calorie).
Poi chiedimi se voglio aggiungere o cambiare qualcosa, e solo dopo genera il JSON.

AVVISI OBBLIGATORI (scrivili prima del JSON):
1. Consulta un medico prima di iniziare qualsiasi nuovo programma di allenamento. Fermati subito se avverti
   capogiri, dolore al petto o mancanza di respiro. Sei responsabile di allenarti entro i tuoi limiti fisici.
2. Come IA posso commettere errori nei calcoli o nel formato: controlla i parametri prima di iniziare.

LIMITI DEL MIO TAPIS ROULANT (non superarli mai):
- velocita' massima $sp km/h, pendenza massima $inc %
- velocita' a passi di 0,1 km/h, pendenza a passi di 0,5 %

FORMATO DI USCITA: un solo oggetto JSON valido, in un blocco di codice, con SOLO queste chiavi.
Radice: "units" (sempre "metric"), "speedUnit" (sempre "speed", velocita' in km/h come numeri), "name", "segments".
Ogni segmento: "name", "durationSeconds" (in SECONDI: 5 minuti = 300), "startSpeed", "endSpeed",
"startIncline", "endIncline", e opzionali "speedInterval" / "inclineInterval" (in secondi).
Non inventare altre chiavi e non usare campi della fascia cardio. Nomi di workout e segmenti in italiano
(es. Riscaldamento, Parte principale, Recupero, Defaticamento).

REGOLE:
- "speedInterval" solo se startSpeed e' diversa da endSpeed; "inclineInterval" solo se startIncline e' diversa
  da endIncline. Se mancano valgono 60 s.
- Se un segmento cambia velocita' o pendenza, deve iniziare con valori diversi da quelli di fine del segmento
  precedente (almeno 0,1 km/h o 0,5 %). Se invece e' un tratto costante voluto (start = end), puo' riprendere
  gli stessi valori.
- Dopo i tratti intensi inserisci un segmento di recupero con intensita' piu' bassa del 15-20 %.
- Con dolore alle ginocchia: pendenze graduali, niente scatti, defaticamento lungo.
- Prima del JSON scrivi in una riga le chiavi che userai e conferma che durationSeconds e' in secondi.
""".trimIndent()
}
