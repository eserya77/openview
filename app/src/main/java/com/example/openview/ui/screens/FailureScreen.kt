package com.example.openview.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.openview.tts.TtsController
import com.example.openview.ui.theme.FailureInk
import com.example.openview.ui.theme.FailureRed
import com.example.openview.ui.theme.FailureRedLight
import com.example.openview.ui.theme.NeutralWhite

@Composable
fun FailureScreen(
    tts: TtsController,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val message = "Te has desviado de la ruta. Detente y acércate a un punto de referencia conocido."

    androidx.compose.runtime.LaunchedEffect(Unit) { tts.speak(message) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FailureRedLight)
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = FailureRed,
            modifier = Modifier.size(84.dp)
        )
        Text(
            "Te has desviado",
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            color = FailureInk,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            message,
            fontSize = 20.sp,
            color = FailureInk,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Assertive }
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = FailureRed,
                contentColor = NeutralWhite
            )
        ) {
            Text("Reintentar ruta", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
        ) {
            Text("Cancelar navegación", fontSize = 20.sp, color = FailureInk)
        }
    }
}
