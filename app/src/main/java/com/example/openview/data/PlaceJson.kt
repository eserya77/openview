package com.example.openview.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON del lugar que entrega la empresa (formato «openview-lugar», versión 1). Ejemplo mínimo:
 *
 * ```
 * {
 *   "formato": "openview-lugar", "version": 1,
 *   "id": "plaza_san_miguel", "nombre": "Plaza San Miguel", "empresa": "…", "entrada": "NAV_BLE_01",
 *   "balizas": [ {"baliza": "NAV_BLE_01", "nombre": "Entrada", "x": 0, "y": 0, "mirando": 0, "aviso": "…"}, … ],
 *   "conexiones": [ ["NAV_BLE_01", "NAV_BLE_02"], … ],
 *   "rutas": [ {"desde": "NAV_BLE_01", "hasta": "NAV_BLE_03", "pasos": ["NAV_BLE_01", "NAV_BLE_02", "NAV_BLE_03"],
 *               "indicaciones": ["…", "…"]}, … ]
 * }
 * ```
 * x, y en metros (x a la derecha, y hacia delante). «mirando», «aviso», «conexiones», «rutas» e «indicaciones» son
 * opcionales: sin conexiones se puede ir directo entre cualquier par; sin indicaciones se generan desde el plano.
 */
object PlaceJson {
    const val FORMAT = "openview-lugar"

    class InvalidPlace(msg: String) : Exception(msg)

    fun parse(text: String): SharedPlace {
        val o = try { JSONObject(text) } catch (e: Exception) { throw InvalidPlace("El archivo no es un JSON válido.") }
        val fmt = o.optString("formato", FORMAT)
        if (fmt != FORMAT) throw InvalidPlace("Formato «$fmt» desconocido (se espera «$FORMAT»).")
        val arr = o.optJSONArray("balizas") ?: throw InvalidPlace("Falta la lista «balizas».")
        val beacons = (0 until arr.length()).map { i ->
            val b = arr.getJSONObject(i)
            PlaceBeacon(
                beacon = b.optString("baliza").ifBlank { throw InvalidPlace("La baliza ${i + 1} no tiene «baliza».") }.uppercase(),
                name = b.optString("nombre").ifBlank { b.optString("baliza") },
                x = b.optDouble("x", 0.0), y = b.optDouble("y", 0.0),
                facing = if (b.has("mirando")) b.optDouble("mirando") else null,
                note = b.optString("aviso").ifBlank { null }
            )
        }
        if (beacons.size < 2) throw InvalidPlace("El lugar necesita al menos 2 balizas.")
        if (beacons.map { it.beacon }.toSet().size != beacons.size) throw InvalidPlace("Hay balizas repetidas.")
        val known = beacons.map { it.beacon }.toSet()
        fun check(id: String): String = id.uppercase().also { if (it !in known) throw InvalidPlace("«$id» no está en la lista de balizas.") }
        val links = o.optJSONArray("conexiones")?.let { a ->
            (0 until a.length()).map { i -> a.getJSONArray(i).let { p -> check(p.getString(0)) to check(p.getString(1)) } }
        } ?: emptyList()
        val routes = o.optJSONArray("rutas")?.let { a ->
            (0 until a.length()).map { i ->
                val r = a.getJSONObject(i)
                val pasos = r.optJSONArray("pasos")?.let { p -> (0 until p.length()).map { check(p.getString(it)) } }
                val from = check(r.optString("desde", pasos?.firstOrNull() ?: ""))
                val to = check(r.optString("hasta", pasos?.lastOrNull() ?: ""))
                PlaceRoute(from, to, pasos ?: listOf(from, to),
                    r.optJSONArray("indicaciones")?.let { d -> (0 until d.length()).map { d.getString(it) } })
            }
        } ?: emptyList()
        val name = o.optString("nombre").ifBlank { throw InvalidPlace("Falta el «nombre» del lugar.") }
        return SharedPlace(
            id = o.optString("id").ifBlank { name.lowercase().replace(Regex("[^a-z0-9]+"), "_") },
            name = name, beacons = beacons, links = links, routes = routes,
            entrance = o.optString("entrada").ifBlank { null }?.let(::check),
            company = o.optString("empresa").ifBlank { null },
            version = o.optInt("version", 1)
        )
    }

    fun write(p: SharedPlace): String {
        val o = JSONObject()
        o.put("formato", FORMAT); o.put("version", p.version)
        o.put("id", p.id); o.put("nombre", p.name)
        p.company?.let { o.put("empresa", it) }
        p.entrance?.let { o.put("entrada", it) }
        o.put("balizas", JSONArray().apply {
            p.beacons.forEach { b ->
                put(JSONObject().apply {
                    put("baliza", b.beacon); put("nombre", b.name); put("x", b.x); put("y", b.y)
                    b.facing?.let { put("mirando", it) }; b.note?.let { put("aviso", it) }
                })
            }
        })
        if (p.links.isNotEmpty()) o.put("conexiones", JSONArray().apply { p.links.forEach { (a, b) -> put(JSONArray().put(a).put(b)) } })
        o.put("rutas", JSONArray().apply {
            p.routes.forEach { r ->
                put(JSONObject().apply {
                    put("desde", r.from); put("hasta", r.to); put("pasos", JSONArray(r.path))
                    r.directions?.let { put("indicaciones", JSONArray(it)) }
                })
            }
        })
        return o.toString(2)
    }
}
