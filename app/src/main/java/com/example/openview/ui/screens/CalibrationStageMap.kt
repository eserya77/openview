package com.example.openview.ui.screens

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.example.openview.calibration.BeaconProfile
import com.example.openview.calibration.CalibrationScenario
import com.example.openview.calibration.ScenarioKind
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private class StageColors(
    val primary: Color, val ink: Color, val muted: Color, val good: Color,
    val beacon: Color, val tape: Color, val bg: Color
)

/**
 * Mini mapa animado, visto desde arriba, de lo que hay que hacer en el paso actual:
 *  - contacto / distancias: la persona con el móvil se acerca o aleja a lo largo de una cinta métrica;
 *  - ángulos: la persona CAMINA en arco alrededor de la baliza (no gira sobre sí misma), siempre a 1 m;
 *  - cuerpo en medio: la persona se da la vuelta para tapar la baliza con el cuerpo.
 * Mientras se espera (no se mide) repite la demostración; al medir se queda quieta en su sitio y de la baliza salen ondas.
 */
@Composable
internal fun CalibrationStageMap(
    scenario: CalibrationScenario,
    previous: CalibrationScenario?,
    beaconName: String,
    measuring: Boolean,
    stable: Boolean,
    modifier: Modifier = Modifier
) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(scenario.id, measuring) {
        if (measuring) { t.snapTo(1f); return@LaunchedEffect }
        while (true) {
            t.snapTo(0f)
            delay(600)
            t.animateTo(1f, tween(2200, easing = FastOutSlowInEasing))
            delay(1800)
        }
    }
    val infinite = rememberInfiniteTransition(label = "stage")
    val wave by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "wave")

    val colors = StageColors(
        primary = MaterialTheme.colorScheme.primary,
        ink = MaterialTheme.colorScheme.onSurface,
        muted = MaterialTheme.colorScheme.onSurfaceVariant,
        good = AdminColors.goodFill,
        beacon = AdminColors.beacon,
        tape = AdminColors.tape,
        bg = MaterialTheme.colorScheme.surfaceVariant
    )
    val waveColor = if (measuring || stable) colors.good else colors.primary
    val name = beaconName.takeLast(2)

    Canvas(
        modifier
            .fillMaxWidth().height(240.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.bg.copy(alpha = .55f))
    ) {
        when (scenario.kind) {
            ScenarioKind.ANGLE -> ringScene(scenario, previous, t.value, wave, waveColor, colors, name, measuring)
            else -> lineScene(scenario, previous, t.value, wave, waveColor, colors, name, measuring)
        }
        text("vista desde arriba", 12f, 20f, colors.muted, 11f, align = Paint.Align.LEFT)
    }
}

// ───────────────────────── utilidades de dibujo ─────────────────────────

private fun DrawScope.text(s: String, x: Float, y: Float, color: Color, sizeDp: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER) {
    drawContext.canvas.nativeCanvas.drawText(
        s, x, y,
        Paint().apply {
            isAntiAlias = true; this.color = color.toArgb(); textSize = sizeDp.dp.toPx(); textAlign = align
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    )
}

private fun fmtM(v: Double): String = when {
    v < 0.02 -> "contacto"
    v < 1.0 -> "${Math.round(v * 100)} cm"
    abs(v - Math.round(v)) < 0.05 -> "${Math.round(v)} m"
    else -> "%.1f m".format(v)
}

/** La baliza vista desde arriba: placa, conector USB a la izquierda y extremo de la antena a la derecha. */
private fun DrawScope.beaconIcon(cx: Float, cy: Float, c: StageColors, name: String, showEnds: Boolean) {
    val hw = 17.dp.toPx(); val hh = 9.dp.toPx()
    // USB
    drawRoundRect(Color(0xFF9AA5B1), Offset(cx - hw - 6.dp.toPx(), cy - 4.dp.toPx()), Size(8.dp.toPx(), 8.dp.toPx()), CornerRadius(2.dp.toPx()))
    // placa
    drawRoundRect(c.beacon, Offset(cx - hw, cy - hh), Size(hw * 2, hh * 2), CornerRadius(4.dp.toPx()))
    text(name, cx - 2.dp.toPx(), cy + 4.dp.toPx(), Color.White, 11f, bold = true)
    // antena (zigzag) en el extremo derecho
    val p = Path().apply {
        moveTo(cx + hw, cy)
        lineTo(cx + hw + 4.dp.toPx(), cy - 5.dp.toPx()); lineTo(cx + hw + 8.dp.toPx(), cy + 5.dp.toPx()); lineTo(cx + hw + 12.dp.toPx(), cy - 5.dp.toPx()); lineTo(cx + hw + 15.dp.toPx(), cy)
    }
    drawPath(p, c.beacon, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    if (showEnds) {
        text("USB", cx - hw - 2.dp.toPx(), cy + 26.dp.toPx(), c.muted, 10f)
        text("antena", cx + hw + 8.dp.toPx(), cy + 26.dp.toPx(), c.muted, 10f)
    }
}

/** Persona vista desde arriba: cuerpo (círculo) y móvil (rectángulo). */
private fun DrawScope.person(bodyX: Float, phoneX: Float, y: Float, color: Color, ghost: Boolean) {
    val r = 12.dp.toPx()
    val phoneW = 8.dp.toPx(); val phoneH = 18.dp.toPx()
    if (ghost) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
        drawCircle(color.copy(alpha = .8f), r + 3.dp.toPx(), Offset(bodyX, y), style = Stroke(2.dp.toPx(), pathEffect = dash))
        drawRoundRect(color.copy(alpha = .8f), Offset(phoneX - phoneW / 2, y - phoneH / 2), Size(phoneW, phoneH), CornerRadius(2.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = dash))
    } else {
        drawCircle(color, r, Offset(bodyX, y))
        drawRoundRect(Color(0xFF263238), Offset(phoneX - phoneW / 2, y - phoneH / 2), Size(phoneW, phoneH), CornerRadius(2.dp.toPx()))
        drawRoundRect(Color(0xFF80DEEA), Offset(phoneX - phoneW / 2 + 1.5.dp.toPx(), y - phoneH / 2 + 2.dp.toPx()), Size(phoneW - 3.dp.toPx(), phoneH - 4.dp.toPx()), CornerRadius(1.dp.toPx()))
    }
}

private fun DrawScope.waves(cx: Float, cy: Float, maxR: Float, wave: Float, color: Color) {
    repeat(3) { i ->
        val f = (wave + i / 3f) % 1f
        drawCircle(color.copy(alpha = (1f - f) * .35f), radius = 24.dp.toPx() + f * maxR, center = Offset(cx, cy), style = Stroke(2.dp.toPx()))
    }
}

// ───────────────────────── escena en línea (contacto / distancias / cuerpo) ─────────────────────────

private fun DrawScope.lineScene(sc: CalibrationScenario, prev: CalibrationScenario?, t: Float, wave: Float, waveColor: Color, c: StageColors, name: String, measuring: Boolean) {
    val dTarget = sc.distanceM
    val dStart = when {
        sc.kind == ScenarioKind.BODY -> dTarget
        prev != null && prev.id != sc.id && (prev.kind == ScenarioKind.FRONT || prev.kind == ScenarioKind.CONTACT) -> prev.distanceM
        else -> dTarget + max(0.6, dTarget * 0.5)
    }
    val dNow = if (sc.kind == ScenarioKind.BODY) dTarget else lerp(dStart.toFloat(), dTarget.toFloat(), t).toDouble()
    val viewMax = max(max(dTarget, dStart), 0.6) * 1.06

    val x0 = 62.dp.toPx()
    val y = size.height * 0.60f
    val beaconHalf = 17.dp.toPx() + 15.dp.toPx()   // placa + antena
    val usable = size.width - x0 - beaconHalf - 56.dp.toPx()
    val scale = (usable / viewMax).toFloat()

    fun anchorX(d: Double) = x0 + beaconHalf + d.toFloat() * scale

    waves(x0, y, size.width * 0.75f, wave, waveColor)

    // eje y marcas de la cinta
    drawLine(c.muted.copy(alpha = .35f), Offset(x0, y), Offset(size.width - 10.dp.toPx(), y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())))
    val step = when { viewMax <= 1.6 -> 0.1; viewMax <= 3.5 -> 0.5; else -> 1.0 }
    val labelEvery = if (step < 0.5) 0.5 else 1.0
    var k = 0
    while (k * step <= viewMax + 1e-6) {
        val v = k * step
        val x = anchorX(v)
        val major = abs(v / labelEvery - Math.round(v / labelEvery)) < 1e-6
        drawLine(c.muted.copy(alpha = if (major) .8f else .4f), Offset(x, y + 4.dp.toPx()), Offset(x, y + (if (major) 12 else 8).dp.toPx()), strokeWidth = 1.dp.toPx())
        if (major && v > 0.0) text(fmtM(v), x, y + 26.dp.toPx(), c.muted, 10f)
        k++
    }

    beaconIcon(x0, y, c, name, showEnds = true)

    // posición final (fantasma) y posición actual
    val bodyOffset = 24.dp.toPx()
    fun drawPerson(d: Double, phoneSide: Float, ghost: Boolean) {
        val a = anchorX(d)
        val bodyX = if (sc.kind == ScenarioKind.BODY) a + 14.dp.toPx() else a + 6.dp.toPx() + bodyOffset
        val phoneX = if (sc.kind == ScenarioKind.BODY) bodyX + phoneSide * bodyOffset else a + 6.dp.toPx()
        person(bodyX, phoneX, y, if (ghost) c.primary else c.primary, ghost)
    }
    if (!measuring) drawPerson(dTarget, if (sc.kind == ScenarioKind.BODY) 1f else -1f, ghost = true)
    drawPerson(dNow, if (sc.kind == ScenarioKind.BODY) lerp(-1f, 1f, t) else -1f, ghost = false)

    // cinta métrica amarilla con la distancia actual
    val tapeY = y - 34.dp.toPx()
    val endX = anchorX(dNow)
    drawLine(c.tape, Offset(x0, tapeY), Offset(endX, tapeY), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
    drawLine(c.tape, Offset(x0, tapeY - 8.dp.toPx()), Offset(x0, tapeY + 8.dp.toPx()), strokeWidth = 2.dp.toPx())
    drawLine(c.tape, Offset(endX, tapeY - 8.dp.toPx()), Offset(endX, tapeY + 8.dp.toPx()), strokeWidth = 2.dp.toPx())
    val shown = if (abs(dNow - dTarget) < 0.005) dTarget else dNow
    val label = if (sc.kind == ScenarioKind.BODY) "1 m" else fmtM(shown)
    text(label, (x0 + endX) / 2f, tapeY - 14.dp.toPx(), c.ink, 15f, bold = true)

    if (sc.kind == ScenarioKind.CONTACT) text("toca la antena", anchorX(0.0) + 40.dp.toPx(), y - 58.dp.toPx(), c.primary, 12f, bold = true)
    if (sc.kind == ScenarioKind.BODY) text("baliza → cuerpo → móvil", size.width / 2f, size.height - 12.dp.toPx(), c.primary, 12f, bold = true)
}

// ───────────────────────── escena en anillo (ángulos) ─────────────────────────

private fun DrawScope.ringScene(sc: CalibrationScenario, prev: CalibrationScenario?, t: Float, wave: Float, waveColor: Color, c: StageColors, name: String, measuring: Boolean) {
    val cx = size.width / 2f
    val cy = size.height / 2f + 6.dp.toPx()
    val r = min(size.width / 2f - 46.dp.toPx(), size.height / 2f - 38.dp.toPx())

    val target = sc.angleDeg.toFloat()
    val start = if (prev != null && prev.kind == ScenarioKind.ANGLE && prev.id != sc.id) prev.angleDeg.toFloat() else 0f
    val now = if (measuring) target else lerp(start, target, t)

    waves(cx, cy, r * 1.4f, wave, waveColor)

    // círculo de 1 m
    drawCircle(c.muted.copy(alpha = .5f), r, Offset(cx, cy), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))))

    // tramo que hay que recorrer
    drawArc(c.primary.copy(alpha = .40f), startAngle = start, sweepAngle = target - start, useCenter = false, topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))

    // las 4 marcas: 0°, 90°, 180°, 270°
    listOf(0, 90, 180, 270).forEach { a ->
        val rad = a * PI.toFloat() / 180f
        val px = cx + r * cos(rad); val py = cy + r * sin(rad)
        val isTarget = a == sc.angleDeg
        drawCircle(if (isTarget) c.primary else c.muted, if (isTarget) 6.dp.toPx() else 4.dp.toPx(), Offset(px, py))
        val lx = cx + (r + 26.dp.toPx()) * cos(rad)
        val ly = cy + (r + 26.dp.toPx()) * sin(rad) + 4.dp.toPx()
        text(if (a == 0) "0° frente" else "$a°", lx, ly, if (isTarget) c.primary else c.muted, if (isTarget) 13f else 11f, bold = isTarget)
    }

    // baliza en el centro
    beaconIcon(cx, cy, c, name, showEnds = false)

    // persona sobre el círculo, con el móvil hacia la baliza
    fun place(deg: Float, ghost: Boolean) {
        val rad = deg * PI.toFloat() / 180f
        val ux = cos(rad); val uy = sin(rad)
        val bx = cx + r * ux; val by = cy + r * uy
        // el móvil va entre la persona y la baliza
        val phoneX = bx - ux * 20.dp.toPx(); val phoneY = by - uy * 20.dp.toPx()
        val ph = 8.dp.toPx(); val pw = 18.dp.toPx()
        if (ghost) {
            drawCircle(c.primary.copy(alpha = .8f), 15.dp.toPx(), Offset(bx, by), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))))
        } else {
            drawCircle(c.primary, 12.dp.toPx(), Offset(bx, by))
            drawRoundRect(Color(0xFF263238), Offset(phoneX - ph / 2, phoneY - pw / 2), Size(ph, pw), CornerRadius(2.dp.toPx()))
            // línea de la cinta (1 m) entre baliza y persona
            drawLine(c.tape, Offset(cx + 26.dp.toPx() * ux, cy + 26.dp.toPx() * uy), Offset(phoneX - ux * 8.dp.toPx(), phoneY - uy * 8.dp.toPx()), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
    if (!measuring) place(target, ghost = true)
    place(now, ghost = false)

    text("siempre a 1 m · tú caminas en arco", cx, size.height - 8.dp.toPx(), c.primary, 12f, bold = true)
}

// ───────────────────────── gráfico del perfil ─────────────────────────

/** RSSI medido (puntos con su dispersión) frente a la distancia, y la curva ajustada. */
@Composable
internal fun ProfileChart(profile: BeaconProfile, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val warn = AdminColors.warn
    val pts = profile.results.filter { it.kind == ScenarioKind.FRONT || it.kind == ScenarioKind.CONTACT }.sortedBy { it.distanceM }
    val ring = profile.results.filter { it.kind == ScenarioKind.ANGLE }
    Canvas(modifier.fillMaxWidth().height(170.dp)) {
        if (pts.isEmpty()) return@Canvas
        val left = 40.dp.toPx(); val right = 12.dp.toPx(); val top = 10.dp.toPx(); val bottom = 26.dp.toPx()
        val all = profile.results
        val lo = floor((all.minOf { it.median - it.sd } - 3) / 10.0).toFloat() * 10f
        val hi = ceil((all.maxOf { it.median + it.sd } + 3) / 10.0).toFloat() * 10f
        val maxD = max(1.0, pts.maxOf { it.distanceM }).toFloat()
        fun px(d: Double) = left + (d.toFloat() / maxD) * (size.width - left - right)
        fun py(v: Double) = top + (hi - v.toFloat()) / (hi - lo) * (size.height - top - bottom)

        var g = lo
        while (g <= hi + 0.1f) {
            drawLine(line, Offset(left, py(g.toDouble())), Offset(size.width - right, py(g.toDouble())), 1.dp.toPx())
            text("${g.toInt()}", left - 6.dp.toPx(), py(g.toDouble()) + 4.dp.toPx(), ink, 10f, align = Paint.Align.RIGHT)
            g += 10f
        }
        var d = 0
        while (d <= maxD) {
            text(if (d == 0) "0" else "$d m", px(d.toDouble()), size.height - 8.dp.toPx(), ink, 10f)
            d += if (maxD > 4) 2 else 1
        }
        // curva ajustada
        val a = profile.a1m; val n = profile.pathLossN
        if (a != null && n != null) {
            val path = Path()
            var first = true
            var dd = 0.3
            while (dd <= maxD + 1e-6) {
                val v = a - 10.0 * n * log10(dd)
                val p = Offset(px(dd), py(v.coerceIn(lo.toDouble(), hi.toDouble())))
                if (first) { path.moveTo(p.x, p.y); first = false } else path.lineTo(p.x, p.y)
                dd += 0.1
            }
            drawPath(path, primary.copy(alpha = .55f), style = Stroke(2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))))
        }
        // ángulos a 1 m (cuadrados)
        ring.forEach { r -> drawRect(warn, Offset(px(1.0) - 4.dp.toPx(), py(r.median) - 4.dp.toPx()), Size(8.dp.toPx(), 8.dp.toPx())) }
        // medias con su dispersión
        pts.forEach { r ->
            val x = px(r.distanceM)
            drawLine(primary, Offset(x, py(r.median + r.sd)), Offset(x, py(r.median - r.sd)), 2.dp.toPx())
            drawCircle(primary, 5.dp.toPx(), Offset(x, py(r.median)))
        }
    }
}
