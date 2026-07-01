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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.openview.ui.NavigationUiState
import com.example.openview.ui.theme.SuccessGreen
import com.example.openview.ui.theme.SuccessGreenLight
import com.example.openview.ui.theme.SuccessInk

@Composable
fun ArrivalScreen(
    state: NavigationUiState,
    tts: TtsController,
    onFinish: () -> Unit,
    onNewRoute: () -> Unit
) {
    val destinationName = state.activeRoute?.destination?.name
        ?: state.selectedDestination?.name
        ?: "tu destino"
    val message = "Has llegado a $destinationName."

    LaunchedEffect(destinationName) { tts.speak(message) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SuccessGreenLight)
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = SuccessGreen,
            modifier = Modifier.size(96.dp)
        )
        Text(
            "¡Has llegado!",
            fontWeight = FontWeight.Bold,
            fontSize = 36.sp,
            color = SuccessInk,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            message,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = SuccessInk,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Assertive }
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onFinish,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SuccessGreen,
                contentColor = SuccessInk
            )
        ) {
            Text("Finalizar", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onNewRoute,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
        ) {
            Text("Nueva ruta", fontSize = 20.sp, color = SuccessInk)
        }
        OutlinedButton(
            onClick = { tts.speak(message) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
        ) {
            Text("Repetir confirmación", fontSize = 20.sp, color = SuccessInk)
        }
    }
}
