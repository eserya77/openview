package com.example.openview.calibration

import org.json.JSONArray
import org.json.JSONObject

/** (De)serialización del perfil de una baliza, para guardarlo junto al catálogo y exportarlo. */
object ProfileJson {

    fun toJson(p: BeaconProfile): JSONObject = JSONObject().apply {
        put("beacon", p.beacon); put("phone", p.phoneModel); put("at", p.createdAtMs)
        put("contact", p.contactRssi ?: JSONObject.NULL); put("contactSd", p.contactSd ?: JSONObject.NULL)
        put("a1m", p.a1m ?: JSONObject.NULL); put("n", p.pathLossN ?: JSONObject.NULL)
        put("resid", p.fitResidualSd ?: JSONObject.NULL); put("asym", p.asymmetryDb ?: JSONObject.NULL)
        put("rate", p.meanRatePerS); put("quality", p.quality.name); put("tx", p.txLevel ?: JSONObject.NULL)
        put("reasons", JSONArray().also { a -> p.reasons.forEach { a.put(it) } })
        put("results", JSONArray().also { a ->
            p.results.forEach { r ->
                a.put(JSONObject().put("id", r.scenarioId).put("kind", r.kind.name).put("d", r.distanceM)
                    .put("angle", r.angleDeg).put("n", r.n).put("median", r.median).put("sd", r.sd).put("rate", r.ratePerS))
            }
        })
    }

    fun fromJson(o: JSONObject): BeaconProfile {
        fun dbl(k: String): Double? = if (o.isNull(k) || !o.has(k)) null else o.getDouble(k)
        val results = o.getJSONArray("results").let { a ->
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let { r ->
                    ScenarioResult(r.getString("id"), ScenarioKind.valueOf(r.getString("kind")), r.getDouble("d"),
                        r.getInt("angle"), r.getInt("n"), r.getDouble("median"), r.getDouble("sd"), r.getDouble("rate"))
                }
            }
        }
        val reasons = o.getJSONArray("reasons").let { a -> (0 until a.length()).map { a.getString(it) } }
        return BeaconProfile(
            o.getString("beacon"), o.optString("phone", ""), o.optLong("at", 0L), results,
            dbl("contact"), dbl("contactSd"), dbl("a1m"), dbl("n"), dbl("resid"), dbl("asym"),
            o.optDouble("rate", 0.0), Quality.valueOf(o.getString("quality")), reasons,
            if (o.has("tx") && !o.isNull("tx")) o.getInt("tx") else null
        )
    }
}
