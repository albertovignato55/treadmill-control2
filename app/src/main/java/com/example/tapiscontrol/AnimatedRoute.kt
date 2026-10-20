package com.example.tapiscontrol

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val Z_NEAR = 1.2f
private const val Z_FAR = 140f
private const val SPAN = Z_FAR - Z_NEAR
private const val ROAD_HALF = 2.0f
private const val BAND = 1.5f          // metri di mezza fascia del terreno (fascia completa = 3 m)
private const val STRIPE_END = 45f     // oltre questa distanza le fasce alternate sfumano

private val SKY_TOP = Color(0xFF4F9FE8)
private val SKY_HORIZON = Color(0xFFD9EDFA)
private val FOG = Color(0xFFC9E0D2)

/** Elemento del paesaggio. kind: 0-1 alberi tondi, 2 pino, 3 cespuglio, 4 sasso, 5 fiori. */
private class Scenery(val side: Int, val lateral: Float, val phase: Float, val kind: Int, val size: Float, val hue: Int)

private val scenery: List<Scenery> = run {
    val r = Random(11)
    val out = ArrayList<Scenery>()
    for (i in 0 until 64) {
        out += Scenery(
            side = if (i % 2 == 0) -1 else 1,
            lateral = 3.4f + r.nextFloat() * 9f,
            phase = (i / 64f) * SPAN + r.nextFloat() * 1.5f,
            kind = r.nextInt(3),
            size = 0.8f + r.nextFloat() * 0.7f,
            hue = r.nextInt(3),
        )
    }
    for (i in 0 until 54) {
        out += Scenery(
            side = if (r.nextBoolean()) -1 else 1,
            lateral = 2.5f + r.nextFloat() * 7f,
            phase = r.nextFloat() * SPAN,
            kind = 3 + r.nextInt(3),
            size = 0.6f + r.nextFloat() * 0.8f,
            hue = r.nextInt(3),
        )
    }
    out
}

// Percorso sinuoso e colline dolci, funzioni della distanza percorsa (metri).
// Curve ben visibili: due onde sovrapposte (S, tornantini dolci) piu' una lenta per variare la direzione
private fun curveAt(zw: Double) =
    18.0 * sin(zw * 0.0314) + 5.0 * sin(zw * 0.062 + 1.3) + 12.0 * sin(zw * 0.0107 + 2.0)
private fun curveSlope(zw: Double) =
    18.0 * 0.0314 * cos(zw * 0.0314) + 5.0 * 0.062 * cos(zw * 0.062 + 1.3) + 12.0 * 0.0107 * cos(zw * 0.0107 + 2.0)
private fun elevAt(zw: Double) = 0.7 * sin(zw * 0.0698 + 0.4) + 0.35 * sin(zw * 0.17 + 0.7)
private fun elevSlope(zw: Double) = 0.7 * 0.0698 * cos(zw * 0.0698 + 0.4) + 0.35 * 0.17 * cos(zw * 0.17 + 0.7)

/** Proiezione prospettica: la camera segue la tangente del sentiero e l'inclinazione del terreno. */
private class Proj(val d: Double, val f: Float, val cx: Float, val yHor: Float, val cam: Float) {
    private val c0 = curveAt(d)
    private val cs = curveSlope(d)
    private val e0 = elevAt(d)
    private val es = elevSlope(d)
    fun lateral(zr: Float): Float = (curveAt(d + zr) - c0 - cs * zr).toFloat()
    fun elev(zr: Float): Float = (elevAt(d + zr) - e0 - es * zr).toFloat()
    fun y(zr: Float): Float = yHor + (cam - elev(zr)) * f / zr
    fun x(zr: Float, off: Float): Float = cx + (lateral(zr) + off) * f / zr
}

private fun fogT(z: Float): Float = ((z - 12f) / (Z_FAR - 12f)).coerceIn(0f, 1f).pow(1.3f) * 0.85f

/**
 * Percorso animato (nessun file video): sentiero con curve, colline, alberi e nuvole che scorre alla velocita'
 * REALE del tapis roulant e si inclina con la sua pendenza. Segue quindi in automatico qualsiasi allenamento (e le
 * correzioni manuali). Non finisce mai. `onPosition` riporta l'equivalente in secondi di video alla velocita'
 * di ripresa, cosi' il profilo del percorso funziona come con un video vero.
 * La scena si adatta a qualsiasi proporzione dello schermo (anche a tutto schermo in orizzontale).
 * `overlay = false` non disegna nessun dato (li mostra il chiamante).
 */
@Composable
fun AnimatedRoute(
    speedKmh: Double,
    inclinePct: Double,
    playing: Boolean,
    refSpeedKmh: Double,
    startSec: Double,
    caption: String,
    modifier: Modifier = Modifier,
    hud: HudData? = null,
    hudScale: Float = 1f,
    overlay: Boolean = true,
    onPosition: (Double) -> Unit = {},
) {
    val speedNow by rememberUpdatedState(speedKmh)
    val inclineNow by rememberUpdatedState(inclinePct)
    val playingNow by rememberUpdatedState(playing)
    val refNow by rememberUpdatedState(refSpeedKmh)
    val posCb by rememberUpdatedState(onPosition)

    var distance by remember { mutableDoubleStateOf(startSec * refSpeedKmh / 3.6) }   // metri percorsi
    var tilt by remember { mutableFloatStateOf(inclinePct.toFloat()) }                // pendenza "ammorbidita"
    var speedSmooth by remember { mutableFloatStateOf(0f) }                           // km/h ammorbiditi
    var animT by remember { mutableFloatStateOf(0f) }                                 // secondi (uccelli, ecc.)

    LaunchedEffect(Unit) {
        var last = 0L
        var acc = 0.0
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000.0).coerceIn(0.0, 0.1)
                    val target = if (playingNow) speedNow.toFloat() else 0f
                    speedSmooth += (target - speedSmooth) * min(1.0, dt * 3.0).toFloat()
                    tilt += (inclineNow.toFloat() - tilt) * min(1.0, dt * 2.0).toFloat()
                    distance += speedSmooth / 3.6 * dt
                    animT += dt.toFloat()
                    acc += dt
                    if (acc >= 0.5) {
                        acc = 0.0
                        posCb(distance / (refNow / 3.6))
                    }
                }
                last = now
            }
        }
    }

    Box(modifier) {
        Canvas(Modifier.matchParentSize()) { drawScene(distance, tilt, speedSmooth, animT) }
        if (overlay) {
            if (hud != null) {
                RunHud(hud, Modifier.matchParentSize(), hudScale)
            } else {
                Column(
                    Modifier.align(Alignment.TopStart).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    HudChip("${fmt(if (playing) speedKmh else 0.0)} km/h")
                    HudChip("pendenza ${inclinePct.roundToInt()} %")
                    if (caption.isNotEmpty()) HudChip(caption)
                }
            }
        }
    }
}

private fun DrawScope.drawScene(distance: Double, tiltPct: Float, speed: Float, t: Float) {
    // leggero dondolio a ogni passo (circa 0,75 m)
    val bobAmp = if (speed > 0.5f) size.height * 0.004f else 0f
    val bob = (sin(distance / 0.75 * 2.0 * PI) * bobAmp).toFloat()
    translate(0f, bob) { paintScene(distance, tiltPct, t) }
}

private fun DrawScope.paintScene(distance: Double, tiltPct: Float, t: Float) {
    val w = size.width
    val h = size.height
    val f = h * 1.6f                 // distanza focale basata sull'altezza: stessa scena a ogni proporzione
    val cx = w / 2f
    val cam = 1.6f                   // altezza degli occhi (m)
    val dF = distance.toFloat()

    // in salita il terreno "sale" davanti a te: la linea dell'orizzonte si alza (effetto esagerato di 1,5 volte)
    val slope = (tiltPct / 100f) * 1.5f
    val yHor = max(h * 0.10f, h * 0.45f - slope * f)
    val proj = Proj(distance, f, cx, yHor, cam)

    // ---------------------------------------------------------------- cielo
    drawRect(
        brush = Brush.verticalGradient(listOf(SKY_TOP, SKY_HORIZON), startY = 0f, endY = yHor),
        topLeft = Offset(0f, -h * 0.05f),
        size = Size(w, yHor + h * 0.05f + 2f),
    )
    drawCircle(Color(0x33FFF4B0), h * 0.17f, Offset(w * 0.76f, h * 0.17f))
    drawCircle(Color(0x55FFF4B0), h * 0.10f, Offset(w * 0.76f, h * 0.17f))
    drawCircle(Color(0xFFFFF6C4), h * 0.055f, Offset(w * 0.76f, h * 0.17f))

    // nuvole (due strati a velocita' diverse)
    val cl = w + h * 0.8f
    for (i in 0..4) {
        val layer = if (i < 3) 0.6f else 0.3f
        val x = (((i * w * 0.38f - dF * layer - t * 4f) % cl) + cl) % cl - h * 0.4f
        val y = h * (0.07f + 0.055f * (i % 3)) + (if (i >= 3) h * 0.03f else 0f)
        val cw = h * (0.30f - 0.04f * (i % 3))
        val cloud = Color(0xCCFFFFFF)
        drawOval(cloud, Offset(x, y), Size(cw, cw * 0.30f))
        drawOval(cloud, Offset(x + cw * 0.22f, y - cw * 0.10f), Size(cw * 0.55f, cw * 0.30f))
        drawOval(cloud, Offset(x + cw * 0.45f, y + cw * 0.02f), Size(cw * 0.5f, cw * 0.22f))
    }

    // uccelli lontani
    for (i in 0..2) {
        val bx = (((i * w * 0.31f + t * (18f + i * 7f)) % (w + 120f)) + (w + 120f)) % (w + 120f) - 60f
        val by = h * (0.13f + 0.07f * i) + sin(t * 1.3f + i) * h * 0.012f
        val flap = sin(t * 7f + i * 2f) * h * 0.012f
        val sw = h * 0.016f
        val bc = Color(0xCC2B3A4A)
        drawLine(bc, Offset(bx - sw, by - flap), Offset(bx, by), strokeWidth = 2.2f)
        drawLine(bc, Offset(bx, by), Offset(bx + sw, by - flap), strokeWidth = 2.2f)
    }

    // montagne e colline lontane (scorrono pochissimo: effetto profondita')
    ridge(Color(0xFFA9C0DB), h * 0.17f, 0.0055f, dF * 0.003f, h * 0.075f, yHor)
    ridge(Color(0xFF8DB0A0), h * 0.10f, 0.010f, dF * 0.007f + 2f, h * 0.03f, yHor)
    ridge(Color(0xFF6E9F7C), h * 0.05f, 0.020f, dF * 0.015f + 5f, h * 0.008f, yHor)

    // ---------------------------------------------------------------- terreno
    drawRect(FOG, Offset(0f, yHor), Size(w, h - yHor + 2f))

    // confini delle strisce, dalla piu' vicina alla piu' lontana: fasce fisse nel mondo (3 m) per il movimento
    val bounds = ArrayList<Float>(96)
    bounds += Z_NEAR
    var k = ceil((distance + Z_NEAR) / BAND).toLong()
    var zr = (k * BAND - distance).toFloat()
    while (zr < STRIPE_END - 1f) {
        if (zr > bounds[bounds.size - 1] + 0.05f) bounds += zr
        k++
        zr = (k * BAND - distance).toFloat()
    }
    bounds += STRIPE_END
    val ratio = (Z_FAR / STRIPE_END).pow(1f / 22f)
    var zg = STRIPE_END
    repeat(22) {
        zg *= ratio
        bounds += min(zg, Z_FAR)
    }
    val n = bounds.size
    val ys = FloatArray(n) { proj.y(bounds[it]) }
    val xs = FloatArray(n) { proj.x(bounds[it], 0f) }

    // elementi del paesaggio, dal piu' lontano al piu' vicino
    val items = scenery
        .map { it to (Z_NEAR + (((it.phase - dF) % SPAN) + SPAN) % SPAN) }
        .sortedByDescending { it.second }
    var ip = 0

    val grassA = Color(0xFF63A64C)
    val grassB = Color(0xFF559443)
    val grassAvg = lerp(grassA, grassB, 0.5f)
    val roadA = Color(0xFFBDA37C)
    val roadB = Color(0xFFAB9069)
    val roadAvg = lerp(roadA, roadB, 0.5f)
    val shoulder = Color(0xFF88985A)

    for (i in n - 2 downTo 0) {
        val za = bounds[i]
        val zb = bounds[i + 1]
        val yN = ys[i]
        val yF = ys[i + 1]
        val zMid = (za + zb) / 2f
        val parity = (floor((distance + zMid) / BAND).toLong() and 1L) == 0L
        val contrast = ((STRIPE_END - zMid) / 15f).coerceIn(0f, 1f)
        val fog = fogT(zMid)
        val gCol = lerp(lerp(grassAvg, if (parity) grassA else grassB, contrast), FOG, fog)
        val rCol = lerp(lerp(roadAvg, if (parity) roadA else roadB, contrast), FOG, fog)
        val sCol = lerp(shoulder, FOG, fog)

        val bottom = max(yN, yF) + 1f
        if (bottom > yF) {
            drawRect(gCol, Offset(0f, yF), Size(w, bottom - yF))
            val hwN = ROAD_HALF * f / za
            val hwF = ROAD_HALF * f / zb
            val sh = Path().apply {
                moveTo(xs[i + 1] - hwF * 1.22f, yF)
                lineTo(xs[i + 1] + hwF * 1.22f, yF)
                lineTo(xs[i] + hwN * 1.22f, bottom)
                lineTo(xs[i] - hwN * 1.22f, bottom)
                close()
            }
            drawPath(sh, sCol)
            val rd = Path().apply {
                moveTo(xs[i + 1] - hwF, yF)
                lineTo(xs[i + 1] + hwF, yF)
                lineTo(xs[i] + hwN, bottom)
                lineTo(xs[i] - hwN, bottom)
                close()
            }
            drawPath(rd, rCol)
        }

        // elementi che stanno su questa striscia
        while (ip < items.size && items[ip].second >= za) {
            drawScenery(items[ip].first, items[ip].second, proj, w)
            ip++
        }
    }
    // eventuali elementi rimasti (molto vicini)
    while (ip < items.size) {
        drawScenery(items[ip].first, items[ip].second, proj, w)
        ip++
    }
}

private fun DrawScope.drawScenery(o: Scenery, z: Float, proj: Proj, w: Float) {
    val sc = proj.f / z
    val sx = proj.x(z, o.side * o.lateral)
    if (sx < -sc * 4f || sx > w + sc * 4f) return
    val by = proj.y(z)
    val fog = fogT(z)
    val alpha = min(1f, (Z_FAR - z) / 30f)
    fun col(c: Color) = lerp(c, FOG, fog).copy(alpha = alpha)

    when (o.kind) {
        0, 1, 2 -> {
            val trunkH = 1.9f * sc * o.size
            val trunkW = 0.26f * sc * o.size
            val r = 1.5f * sc * o.size
            // ombra
            drawOval(Color(0x33000000).copy(alpha = 0.2f * alpha), Offset(sx - r * 0.9f, by - r * 0.12f), Size(r * 1.8f, r * 0.30f))
            drawRect(col(Color(0xFF6B4A2B)), Offset(sx - trunkW / 2f, by - trunkH), Size(trunkW, trunkH))
            if (o.kind == 2) {
                val g1 = col(Color(0xFF1F5E30))
                val g2 = col(Color(0xFF2B7A3D))
                val h1 = 2.4f * sc * o.size
                val tri1 = Path().apply {
                    moveTo(sx, by - trunkH - h1 * 1.25f)
                    lineTo(sx - r * 0.75f, by - trunkH - h1 * 0.15f)
                    lineTo(sx + r * 0.75f, by - trunkH - h1 * 0.15f)
                    close()
                }
                val tri2 = Path().apply {
                    moveTo(sx, by - trunkH - h1 * 0.8f)
                    lineTo(sx - r * 1.0f, by - trunkH * 0.85f)
                    lineTo(sx + r * 1.0f, by - trunkH * 0.85f)
                    close()
                }
                drawPath(tri2, g1)
                drawPath(tri1, g2)
            } else {
                val dark = when (o.hue) {
                    0 -> Color(0xFF2F7A34)
                    1 -> Color(0xFF3E8E3A)
                    else -> Color(0xFF4C8B2E)
                }
                val cy = by - trunkH - r * 0.5f
                drawCircle(col(dark), r, Offset(sx, cy))
                drawCircle(col(dark), r * 0.8f, Offset(sx - r * 0.65f, cy + r * 0.35f))
                drawCircle(col(dark), r * 0.8f, Offset(sx + r * 0.65f, cy + r * 0.35f))
                drawCircle(col(lerp(dark, Color.White, 0.22f)), r * 0.55f, Offset(sx - r * 0.3f, cy - r * 0.3f))
            }
        }
        3 -> { // cespuglio
            val r = 0.55f * sc * o.size
            val g = col(if (o.hue == 0) Color(0xFF3A7D36) else Color(0xFF4A8F3C))
            drawCircle(g, r, Offset(sx, by - r * 0.7f))
            drawCircle(g, r * 0.8f, Offset(sx - r * 0.9f, by - r * 0.45f))
            drawCircle(g, r * 0.8f, Offset(sx + r * 0.9f, by - r * 0.45f))
        }
        4 -> { // sasso
            val r = 0.45f * sc * o.size
            drawOval(col(Color(0xFF8A8A84)), Offset(sx - r, by - r * 1.1f), Size(r * 2f, r * 1.3f))
            drawOval(col(Color(0xFFA9A9A2)), Offset(sx - r * 0.7f, by - r * 1.0f), Size(r * 1.2f, r * 0.7f))
        }
        else -> { // fiori
            val r = 0.12f * sc * o.size
            val c = when (o.hue) {
                0 -> Color(0xFFF2D14B)
                1 -> Color(0xFFF2F2F2)
                else -> Color(0xFFD9548B)
            }
            for (j in 0..2) {
                drawCircle(col(c), max(r, 1.2f), Offset(sx + (j - 1) * r * 3.2f, by - r * (1.2f + j % 2)))
            }
        }
    }
}

private fun DrawScope.ridge(color: Color, amp: Float, k: Float, shift: Float, base: Float, yHor: Float) {
    val w = size.width
    val p = Path()
    p.moveTo(0f, yHor + 4f)
    var x = 0f
    while (x <= w + 8f) {
        val y = yHor - base - amp * (
            0.55f * sin(x * k + shift) +
                0.30f * sin(x * k * 2.3f + shift * 1.7f) +
                0.15f * sin(x * k * 5.1f)
            )
        p.lineTo(x, y)
        x += 8f
    }
    p.lineTo(w + 8f, yHor + 4f)
    p.close()
    drawPath(p, color)
}
