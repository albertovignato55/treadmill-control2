package com.example.tapiscontrol

import java.util.UUID

/** Costanti e parsing dello standard Bluetooth FTMS (Fitness Machine Service, 0x1826). */
object Ftms {
    private fun uuid(id: String): UUID = UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")

    val SERVICE: UUID = uuid("1826")
    val FEATURE: UUID = uuid("2acc")
    val TREADMILL_DATA: UUID = uuid("2acd")
    val SPEED_RANGE: UUID = uuid("2ad4")
    val INCLINE_RANGE: UUID = uuid("2ad5")
    val CONTROL_POINT: UUID = uuid("2ad9")
    val CCCD: UUID = uuid("2902")

    const val OP_REQUEST_CONTROL = 0x00
    const val OP_SET_SPEED = 0x02      // uint16, unita' 0.01 km/h
    const val OP_SET_INCLINE = 0x03    // sint16, unita' 0.1 %
    const val OP_START = 0x07
    const val OP_STOP = 0x08           // parametro: 0x01 = stop, 0x02 = pausa
    const val OP_RESPONSE = 0x80
}

data class LiveData(
    val speedKmh: Double? = null,
    val inclinePct: Double? = null,
    val distanceM: Int? = null,
    val heartRate: Int? = null,
    val elapsedSec: Int? = null,
)

/** Decodifica la caratteristica "Treadmill Data" (0x2ACD). */
fun parseTreadmillData(b: ByteArray): LiveData {
    var i = 0
    fun u8(): Int { val v = b[i].toInt() and 0xFF; i += 1; return v }
    fun u16(): Int {
        val v = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        i += 2; return v
    }
    fun s16(): Int = u16().toShort().toInt()
    fun u24(): Int {
        val v = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16)
        i += 3; return v
    }

    var speed: Double? = null
    var incline: Double? = null
    var dist: Int? = null
    var hr: Int? = null
    var elapsed: Int? = null
    try {
        val flags = u16()
        if ((flags and 0x0001) == 0) speed = u16() / 100.0
        if ((flags and 0x0002) != 0) u16()                       // velocita' media
        if ((flags and 0x0004) != 0) dist = u24()                // distanza totale (m)
        if ((flags and 0x0008) != 0) {                           // inclinazione + angolo rampa
            val inc = s16(); s16()
            if (inc != 0x7FFF) incline = inc / 10.0
        }
        if ((flags and 0x0010) != 0) { u16(); u16() }            // dislivello +/-
        if ((flags and 0x0020) != 0) u8()                        // passo istantaneo
        if ((flags and 0x0040) != 0) u8()                        // passo medio
        if ((flags and 0x0080) != 0) { u16(); u16(); u8() }      // energia
        if ((flags and 0x0100) != 0) hr = u8()                   // frequenza cardiaca
        if ((flags and 0x0200) != 0) u8()                        // MET
        if ((flags and 0x0400) != 0) elapsed = u16()             // tempo trascorso (s)
    } catch (_: IndexOutOfBoundsException) {
        // pacchetto piu' corto del previsto: tengo quello che ho letto
    }
    return LiveData(speed, incline, dist, hr, elapsed)
}
