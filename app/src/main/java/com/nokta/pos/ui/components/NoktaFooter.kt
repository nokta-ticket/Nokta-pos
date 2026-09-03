package com.nokta.pos.ui.components

import androidx.compose.foundation.Image
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nokta.pos.R

private val FooterBg = Color(0xFFF4F7FB)
private val InkSoft = Color(0xFF3A4048)
private val GrayLight = Color(0xFF9AA1AA)
private val GhostText = Color(0xFFC3C9D1)

/**
 * Rodapé institucional do app — fiel ao arquivo de referência do redesign
 * 2026-09 (HomeScreen.kt enviado pelo usuário): fileira de pontos
 * decorativos, fundo Ice (#F4F7FB), wordmark como imagem real (não texto
 * "NOKTA POS" placeholder — já existe asset real, ver [R.drawable.nokta_pos_wordmark]),
 * razão social e "CNPJ · versão" centralizados abaixo.
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
            .background(FooterBg)
            .padding(vertical = 26.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(14) {
                Box(Modifier.size(3.dp).background(GhostText))
            }
        }

        Spacer(Modifier.height(20.dp))

        Image(
            painter = painterResource(id = R.drawable.nokta_pos_wordmark),
            contentDescription = "Nokta POS",
            modifier = Modifier.width(130.dp),
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = companyName,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = InkSoft,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "CNPJ $cnpj · V$appVersion",
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp,
            color = GrayLight,
            textAlign = TextAlign.Center,
        )
    }
}
