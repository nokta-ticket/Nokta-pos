package com.nokta.pos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nokta.pos.ui.theme.NoktaInk
import com.nokta.pos.ui.theme.NoktaMutedSoft

private val CompanyText = Color(0xFF1A2130)

// Gradiente horizontal Deep Blue → Electric Blue → Cyan Blue (paleta
// oficial 2026-09-03) como divisória de seção — nunca magenta/rosa, regra
// explícita de marca.
private val TopDividerGradient = Brush.horizontalGradient(
    colors = listOf(Color(0xFF0038B8), Color(0xFF0066FF), Color(0xFF00B7FF))
)

/**
 * Rodapé institucional do app.
 *
 * Sem fundo/contorno próprio de propósito — herda o fundo da tela, para não
 * ler como um card separado do resto do conteúdo.
 *
 * @param companyName razão social exibida abaixo da marca
 * @param cnpj CNPJ já formatado (00.000.000/0000-00)
 * @param appVersion versão vinda do BuildConfig.VERSION_NAME
 */
@Composable
fun NoktaFooter(
    modifier: Modifier = Modifier,
    companyName: String = "Nokta Tecnologia LTDA",
    cnpj: String = "59.386.582/0001-39",
    appVersion: String,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Gradiente de ponta a ponta da identidade Nokta, separando o
        // conteúdo principal do rodapé.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(TopDividerGradient)
        )

        Spacer(Modifier.height(12.dp))

        // Trocar por Image(painterResource(R.drawable.logo_nokta)) quando o asset entrar.
        Text(
            text = "NOKTA",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp,
            color = NoktaInk
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = companyName,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = CompanyText,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(3.dp))

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CNPJ $cnpj",
                fontSize = 9.sp,
                color = NoktaMutedSoft
            )
            Box(
                Modifier
                    .padding(horizontal = 6.dp)
                    .size(3.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(NoktaMutedSoft)
            )
            Text(
                text = "Versão do app $appVersion",
                fontSize = 9.sp,
                color = NoktaMutedSoft
            )
        }

        Spacer(Modifier.height(10.dp))
    }
}

/*
 * Uso:
 *
 * NoktaFooter(appVersion = BuildConfig.VERSION_NAME)
 */
