package com.example.openview.share

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Protocolo para pasar las rutas del guardia al teléfono de la persona ciega por Bluetooth, sin internet.
 *
 * ANUNCIO (datos de fabricante 0xFFFF, 6 bytes): [0xC5, tipo, hash hi, hash lo, código hi, código lo]
 *  - tipo 1 ANNOUNCE: el guardia anuncia que tiene rutas (escenario 1). El nombre del lugar va en la respuesta de escaneo.
 *  - tipo 2 REQUEST:  la persona ciega pide las rutas con un código de 4 cifras (escenario 2).
 *  - tipo 3 DIRECT:   el guardia responde a ESE código: el teléfono que lo pidió descarga solo, sin preguntar.
 *  El primer byte 0xC5 (> 99) hace que el escáner de balizas no lo confunda con una baliza NAV_BLE_xx.
 *
 * DESCARGA (GATT, el guardia es el servidor): se lee INFO (tamaño, hash, nombre); luego, por cada trozo, se escribe su
 * número en INDEX y se lee DATA. El JSON viaja comprimido con gzip y se comprueba con el hash.
 *
 * Kotlin puro: se prueba en tests.
 */
object ShareProtocol {
    const val COMPANY_ID = 0xFFFF
    const val MARKER: Byte = 0xC5.toByte()

    const val TYPE_ANNOUNCE = 1
    const val TYPE_REQUEST = 2
    const val TYPE_DIRECT = 3

    val SERVICE_UUID: UUID = UUID.fromString("6f70656e-7669-6577-0000-000000000001")
    val INFO_UUID: UUID = UUID.fromString("6f70656e-7669-6577-0000-000000000002")
    val INDEX_UUID: UUID = UUID.fromString("6f70656e-7669-6577-0000-000000000003")
    val DATA_UUID: UUID = UUID.fromString("6f70656e-7669-6577-0000-000000000004")
    /** UUID corto de los datos de servicio con el nombre del lugar (respuesta de escaneo). */
    val NAME_UUID: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")

    /** Bytes por trozo: cabe en una sola lectura con MTU 517; con MTU menor Android hace lecturas largas. */
    const val CHUNK = 480
    /** Bytes máximos del nombre en la respuesta de escaneo (31 − cabeceras). */
    const val NAME_MAX_BYTES = 24

    data class Advert(val type: Int, val hash: Int, val code: Int)

    fun encode(a: Advert): ByteArray = byteArrayOf(
        MARKER, a.type.toByte(), (a.hash shr 8).toByte(), a.hash.toByte(), (a.code shr 8).toByte(), a.code.toByte()
    )

    fun decode(b: ByteArray?): Advert? {
        if (b == null || b.size < 6 || b[0] != MARKER) return null
        val type = b[1].toInt()
        if (type !in TYPE_ANNOUNCE..TYPE_DIRECT) return null
        return Advert(type, u16(b, 2), u16(b, 4))
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    /** Nombre recortado a [NAME_MAX_BYTES] bytes UTF-8 sin partir caracteres. */
    fun nameBytes(name: String): ByteArray {
        var s = name
        while (s.toByteArray(Charsets.UTF_8).size > NAME_MAX_BYTES) s = s.dropLast(1)
        return s.toByteArray(Charsets.UTF_8)
    }

    /** CRC-16/CCITT-FALSE: identifica un JSON concreto (si el guardia cambia las rutas, cambia el hash). */
    fun hash(bytes: ByteArray): Int {
        var crc = 0xFFFF
        for (x in bytes) {
            crc = crc xor ((x.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }

    fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }

    /** INFO: [tamaño comprimido (4)][hash del JSON (2)][trozo (2)][nombre UTF-8]. */
    fun info(payloadSize: Int, hash: Int, name: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8).take(200).toByteArray()
        return byteArrayOf(
            (payloadSize shr 24).toByte(), (payloadSize shr 16).toByte(), (payloadSize shr 8).toByte(), payloadSize.toByte(),
            (hash shr 8).toByte(), hash.toByte(), (CHUNK shr 8).toByte(), CHUNK.toByte()
        ) + n
    }

    data class Info(val size: Int, val hash: Int, val chunk: Int, val name: String) {
        val chunks: Int get() = (size + chunk - 1) / chunk
    }

    fun parseInfo(b: ByteArray): Info? {
        if (b.size < 8) return null
        val size = (u16(b, 0) shl 16) or u16(b, 2)
        return Info(size, u16(b, 4), u16(b, 6), String(b, 8, b.size - 8, Charsets.UTF_8)).takeIf { it.size in 1..2_000_000 && it.chunk > 0 }
    }

    fun chunk(payload: ByteArray, index: Int): ByteArray {
        val from = (index * CHUNK).coerceAtMost(payload.size)
        return payload.copyOfRange(from, (from + CHUNK).coerceAtMost(payload.size))
    }

    /** Código de 4 cifras para el escenario 2 (sin ceros a la izquierda, fácil de dictar). */
    fun newCode(random: kotlin.random.Random = kotlin.random.Random.Default): Int = random.nextInt(1000, 10000)

    /** «4 7 2 1»: así la voz lo dice cifra a cifra. */
    fun spoken(code: Int): String = code.toString().toCharArray().joinToString(" ")
}
