package com.nokta.pos.devtools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.nokta.pos.ui.theme.NoktaPosTheme

/**
 * Ferramenta de desenvolvimento: renderiza a Home com dados fixos para
 * conferir o acabamento visual sem precisar parear terminal e logar.
 *
 * Vive em `src/debug` de propósito — não existe no APK de produção. Não faz
 * parte do app nem aparece no launcher; é aberta por `adb shell am start`.
 */
class HomePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val variant = intent?.getStringExtra("variant")
        setContent {
            NoktaPosTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomePreviewContent(variant)
                }
            }
        }
    }
}
