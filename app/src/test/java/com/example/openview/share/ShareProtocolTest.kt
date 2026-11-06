package com.example.openview.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareProtocolTest {

    @Test fun `anuncio ida y vuelta`() {
        val a = ShareProtocol.Advert(ShareProtocol.TYPE_DIRECT, 0xBEEF, 4721)
        val b = ShareProtocol.encode(a)
        assertEquals(6, b.size)
        assertEquals(a, ShareProtocol.decode(b))
    }

    @Test fun `el escaner de balizas no lo confunde con una baliza`() {
        // BleScanner toma el primer byte como número de baliza si está en 1..99: 0xC5 no lo está.
        val first = ShareProtocol.encode(ShareProtocol.Advert(1, 0, 0))[0].toInt()
        assertEquals(false, first in 1..99)
        // Y un anuncio de baliza [id, fw, tx] no se toma por uno de rutas.
        assertNull(ShareProtocol.decode(byteArrayOf(3, 3, 5, 0, 0, 0)))
    }

    @Test fun `hash CRC-16 CCITT`() {
        assertEquals(0x29B1, ShareProtocol.hash("123456789".toByteArray()))
    }

    @Test fun `json por trozos se reconstruye igual`() {
        val json = (1..400).joinToString(",", "[", "]") { """{"baliza":"NAV_BLE_0${it % 9}","nombre":"Punto ñ $it"}""" }.toByteArray()
        val zipped = ShareProtocol.gzip(json)
        val info = ShareProtocol.parseInfo(ShareProtocol.info(zipped.size, ShareProtocol.hash(json), "Plaza San Miguel"))!!
        assertEquals("Plaza San Miguel", info.name)
        val back = (0 until info.chunks).map { ShareProtocol.chunk(zipped, it) }.reduce { a, b -> a + b }
        assertArrayEquals(zipped, back)
        val out = ShareProtocol.gunzip(back)
        assertArrayEquals(json, out)
        assertEquals(info.hash, ShareProtocol.hash(out))
    }

    @Test fun `nombre recortado sin partir caracteres`() {
        val b = ShareProtocol.nameBytes("Plaza San Miguel · Baño accesible")
        assertEquals(true, b.size <= ShareProtocol.NAME_MAX_BYTES)
        assertEquals(true, String(b, Charsets.UTF_8).startsWith("Plaza San Miguel"))
    }

    @Test fun `codigo de 4 cifras dicho cifra a cifra`() {
        val c = ShareProtocol.newCode(kotlin.random.Random(1))
        assertEquals(true, c in 1000..9999)
        assertEquals("4 7 2 1", ShareProtocol.spoken(4721))
    }
}
