package com.nokta.pos.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nokta.pos.R
import com.nokta.pos.ui.theme.NoktaBackground
import com.nokta.pos.ui.theme.NoktaInk

/**
 * Rodapé institucional do app — fiel ao arquivo de referência do redesign
 * 2026-09 (HomeScreen.kt enviado pelo usuário): fundo Ice (não transparente
 * herdando o fundo da tela como antes), wordmark como imagem real (não mais
 * texto "NOKTA"), CNPJ e versão em UMA linha mono, sem gradiente divisor.
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
        modifier = modifier
            .fillMaxWidth()
            .background(NoktaBackground)
            .padding(top = 12.dp, bottom = 16.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(id = R.drawable.nokta_pos_wordmark),
            contentDescription = "Nokta POS",
            modifier = Modifier.width(130.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(companyName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NoktaInk)
        Text(
            "CNPJ $cnpj · V$appVersion",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Color(0xFF98A2B3),
        )
    }
}
