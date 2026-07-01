package com.example.openview

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.openview.input.VolumeUpDispatcher
import com.example.openview.ui.OpenViewApp
import com.example.openview.ui.theme.OpenviewTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenviewTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    OpenViewApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP &&
            event.action == KeyEvent.ACTION_DOWN &&
            VolumeUpDispatcher.dispatch()
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
