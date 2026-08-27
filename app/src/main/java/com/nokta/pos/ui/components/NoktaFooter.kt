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

private val DividerPurple = Color(0xFFB39AF0)
private val CompanyText = Color(0xFF2E2560)

// Gradiente horizontal roxo → magenta → azul da identidade Nokta (o mesmo
// usado em destaques da marca), de ponta a ponta como divisória de seção.
private val TopDividerGradient = Brush.horizontalGradient(
    colors = listOf(Color(0xFF6D28D9), Color(0xFFD946EF), Color(0xFF3399FF))
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

        Spacer(Modifier.height(20.dp))

        // Trocar por Image(painterResource(R.drawable.logo_nokta)) quando o asset entrar.
        Text(
            text = "NOKTA",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 5.sp,
            color = NoktaInk
        )

        Spacer(Modifier.height(10.dp))

        Box(
            Modifier
                .width(28.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(DividerPurple)
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = companyName,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = CompanyText,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(5.dp))

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CNPJ $cnpj",
                fontSize = 10.sp,
                color = NoktaMutedSoft
            )
            Box(
                Modifier
                    .padding(horizontal = 7.dp)
                    .size(3.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(NoktaMutedSoft)
            )
            Text(
                text = "Versão do app $appVersion",
                fontSize = 10.sp,
                color = NoktaMutedSoft
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

/*
 * Uso:
 *
 * NoktaFooter(appVersion = BuildConfig.VERSION_NAME)
 */
