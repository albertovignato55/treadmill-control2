package com.example.tapiscontrol

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val Z_NEAR = 1.5f
private const val Z_FAR = 110f
private const val SPAN = Z_FAR - Z_NEAR
private const val ROAD_HALF = 2.2f

private class Tree(val side: Int, val lateral: Float, val phase: Float, val kind: Int, val size: Float)

private val trees: List<Tree> = run {
    val r = Random(7)
    List(40) { i ->
        Tree(
            side = if (i % 2 == 0) -1 else 1,
            lateral = 3.6f + r.nextFloat() * 5.5f,
            phase = (i / 40f) * SPAN + r.nextFloat() * 1.5f,
            kind = r.nextInt(3),
            size = 0.8f + r.nextFloat() * 0.6f,
        )
    }
}

/**
 * Percorso animato (nessun file video): un sentiero con alberi e colline che scorre alla velocita' REALE del
 * tapis roulant e si inclina con la sua pendenza. Segue quindi in automatico qualsiasi allenamento (e le
 * correzioni manuali). Non finisce mai. `onPosition` riporta l'equivalente in secondi di video alla velocita'
 * di ripresa, cosi' il profilo del percorso funziona come con un video vero.
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
        Canvas(Modifier.matchParentSize()) { drawScene(distance, tilt, speedSmooth) }
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

@Composable
private fun HudChip(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        modifier = Modifier
            .background(Color(0x99000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private fun DrawScope.drawScene(distance: Double, tiltPct: Float, speed: Float) {
    // leggero dondolio a ogni passo (circa 0,75 m)
    val bobAmp = if (speed > 0.5f) size.height * 0.004f else 0f
    val bob = (sin(distance / 0.75 * 2.0 * PI) * bobAmp).toFloat()
    translate(0f, bob) { paintScene(distance, tiltPct) }
}

private fun DrawScope.paintScene(distance: Double, tiltPct: Float) {
    val w = size.width
    val h = size.height
    val f = w * 0.9f           // distanza focale
    val cx = w / 2f
    val cam = 1.6f             // altezza degli occhi (m)
    val d = distance.toFloat()

    val yHor = h * 0.45f
    // in salita il terreno "sale" davanti a te: la linea del terreno si alza, esagerata di 1,5 volte
    val slope = (tiltPct / 100f) * 1.5f
    val yGround = max(h * 0.08f, yHor - slope * f)

    // cielo
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF6FB6F2), Color(0xFFCFE9FB)),
            startY = -h * 0.05f,
            endY = yHor,
        ),
        topLeft = Offset(0f, -h * 0.05f),
        size = Size(w, yHor + h * 0.05f + 2f),
    )
    // sole
    drawCircle(Color(0x33FFF4B0), h * 0.16f, Offset(w * 0.78f, h * 0.15f))
    drawCircle(Color(0xFFFFF3B0), h * 0.055f, Offset(w * 0.78f, h * 0.15f))
    // nuvole
    val cl = w + 240f
    for (i in 0..2) {
        val x = (((i * w * 0.45f - d * 0.6f) % cl) + cl) % cl - 120f
        val y = h * (0.10f + 0.07f * i)
        drawOval(Color(0xCCFFFFFF), Offset(x, y), Size(w * 0.16f, h * 0.05f))
        drawOval(Color(0xCCFFFFFF), Offset(x + w * 0.05f, y - h * 0.02f), Size(w * 0.12f, h * 0.05f))
    }
    // montagne lontane (scorrono pochissimo: effetto profondita')
    ridge(Color(0xFF9DB7D6), h * 0.16f, 0.006f, d * 0.004f, h * 0.07f, yHor)
    ridge(Color(0xFF7FA38A), h * 0.09f, 0.011f, d * 0.009f + 2f, h * 0.02f, yHor)

    // prato
    drawRect(
        Color(0xFF5E9E4A),
        Offset(0f, yGround),
        Size(w, h - yGround + h * 0.05f),
    )
    val band = 3f
    val period = 2f * band
    val off = (distance % period).toFloat()

    // fasce del prato che scorrono verso di te
    var i = 0
    while (true) {
        val zA = -off + i * band
        if (zA >= Z_FAR) break
        val za = max(zA, Z_NEAR)
        val zb = min(zA + band, Z_FAR)
        if (zb > za && i % 2 == 0) {
            val yNear = yGround + cam * f / za
            val yFar = yGround + cam * f / zb
            drawRect(Color(0xFF568F43), Offset(0f, yFar), Size(w, yNear - yFar))
        }
        i++
    }

    // sentiero
    val roadFar = yGround + cam * f / Z_FAR
    val roadNear = yGround + cam * f / Z_NEAR
    val rwFar = ROAD_HALF * f / Z_FAR
    val rwNear = ROAD_HALF * f / Z_NEAR
    val road = Path().apply {
        moveTo(cx - rwFar, roadFar)
        lineTo(cx + rwFar, roadFar)
        lineTo(cx + rwNear, roadNear)
        lineTo(cx - rwNear, roadNear)
        close()
    }
    drawPath(road, Color(0xFFB59A74))
    i = 0
    while (true) {
        val zA = -off + i * band
        if (zA >= Z_FAR) break
        val za = max(zA, Z_NEAR)
        val zb = min(zA + band, Z_FAR)
        if (zb > za && i % 2 == 0) {
            val yNear = yGround + cam * f / za
            val yFar = yGround + cam * f / zb
            val rwN = ROAD_HALF * f / za
            val rwF = ROAD_HALF * f / zb
            val p = Path().apply {
                moveTo(cx - rwF, yFar)
                lineTo(cx + rwF, yFar)
                lineTo(cx + rwN, yNear)
                lineTo(cx - rwN, yNear)
                close()
            }
            drawPath(p, Color(0xFFA68B66))
        }
        i++
    }

    // alberi: vengono verso di te e ricompaiono in fondo
    val ordered = trees
        .map { t -> t to (Z_NEAR + (((t.phase - d) % SPAN) + SPAN) % SPAN) }
        .sortedByDescending { it.second }
    for ((t, z) in ordered) {
        val sc = f / z
        val sx = cx + t.side * t.lateral * sc
        if (sx < -sc * 3f || sx > w + sc * 3f) continue
        val by = yGround + cam * sc
        val alpha = min(1f, (Z_FAR - z) / 25f)
        val trunkH = 1.9f * sc * t.size
        val trunkW = 0.28f * sc * t.size
        val r = 1.5f * sc * t.size
        drawRect(
            Color(0xFF6B4A2B).copy(alpha = alpha),
            Offset(sx - trunkW / 2f, by - trunkH),
            Size(trunkW, trunkH),
        )
        if (t.kind == 2) {
            val g = Color(0xFF276B33).copy(alpha = alpha)
            val tri = Path().apply {
                moveTo(sx, by - trunkH - 2.8f * sc * t.size)
                lineTo(sx - r * 0.9f, by - trunkH * 0.8f)
                lineTo(sx + r * 0.9f, by - trunkH * 0.8f)
                close()
            }
            drawPath(tri, g)
        } else {
            val g = (if (t.kind == 0) Color(0xFF2F7A34) else Color(0xFF3E8E3A)).copy(alpha = alpha)
            drawCircle(g, r, Offset(sx, by - trunkH - r * 0.5f))
            drawCircle(g, r * 0.8f, Offset(sx - r * 0.6f, by - trunkH - r * 0.1f))
            drawCircle(g, r * 0.8f, Offset(sx + r * 0.6f, by - trunkH - r * 0.1f))
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
