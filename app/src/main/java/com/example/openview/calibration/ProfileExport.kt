package com.example.openview.calibration

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exporta los perfiles de calibración como CSV (una fila por baliza y escenario) para analizarlos en el PC. */
object ProfileExport {

    const val HEADER =
        "beacon,phone,quality,contact_rssi,a1m,path_loss_n,fit_resid_sd,asymmetry_db,mean_rate_per_s," +
            "scenario,kind,distance_m,angle_deg,samples,median_rssi,sd_rssi,rate_per_s"

    fun lines(profiles: List<BeaconProfile>): List<String> {
        fun num(v: Double?) = v?.let { "%.2f".format(Locale.US, it) } ?: ""
        val out = mutableListOf(HEADER)
        profiles.forEach { p ->
            val head = listOf(
                p.beacon, p.phoneModel.replace(",", " "), p.quality.name, num(p.contactRssi), num(p.a1m), num(p.pathLossN),
                num(p.fitResidualSd), num(p.asymmetryDb), num(p.meanRatePerS)
            ).joinToString(",")
            p.results.forEach { r ->
                out += head + "," + listOf(
                    r.scenarioId, r.kind.name, num(r.distanceM), r.angleDeg.toString(), r.n.toString(),
                    num(r.median), num(r.sd), num(r.ratePerS)
                ).joinToString(",")
            }
        }
        return out
    }

    fun exportAndShare(context: Context, profiles: List<BeaconProfile>) {
        if (profiles.isEmpty()) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(context.cacheDir, "openview_perfiles_$stamp.csv")
        file.bufferedWriter().use { w ->
            w.write("sep=,\n")
            lines(profiles).forEach { w.write(it); w.write("\n") }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Exportar perfiles de calibración"))
    }
}
