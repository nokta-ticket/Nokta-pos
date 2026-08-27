package com.nokta.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.nokta.pos.session.DeviceEvents
import com.nokta.pos.session.SessionEvents
import com.nokta.pos.ui.NoktaPosNavHost
import com.nokta.pos.ui.theme.NoktaPosTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Injetado na Activity (e não numa tela) porque a queda de sessão precisa
     * chegar à navegação de qualquer lugar do app, inclusive quando a tela
     * atual foi destruída.
     */
    @Inject lateinit var sessionEvents: SessionEvents

    /** Mesmo motivo de [sessionEvents]: revogação de terminal pode ser detectada com qualquer tela em foco. */
    @Inject lateinit var deviceEvents: DeviceEvents

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NoktaPosTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val navController = rememberNavController()
                    NoktaPosNavHost(navController = navController, sessionEvents = sessionEvents, deviceEvents = deviceEvents)
                }
            }
        }
    }
}
