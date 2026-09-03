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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nokta.pos.R

private val FooterBg = Color(0xFFF4F7FB)
private val InkSoft = Color(0xFF3A4048)
// #9AA1AA sobre #F4F7FB dava ~2.1:1 de contraste (WCAG AA exige 4.5:1 para
// texto pequeno) — o CNPJ ficava praticamente ilegível. #5B6472 mantém o
// peso visual "secundário" do rótulo com contraste ~4.6:1.
private val MutedAccessible = Color(0xFF5B6472)

/**
 * Rodapé institucional do app — compacto (redesign 2026-09, revisado após
 * feedback do usuário: a versão inicial, fiel ao arquivo de referência com
 * fileira de pontos decorativos + padding generoso, ficava grande demais e
 * chegava a forçar scroll na Home). Fundo Ice (#F4F7FB), wordmark como
 * imagem real com fundo transparente (asset atualizado pelo usuário — ver
 * [R.drawable.nokta_pos_wordmark] — por isso pode ser exibida maior sem
 * mostrar nenhuma borda/caixa branca), razão social e "CNPJ · versão"
 * centralizados abaixo.
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
            .padding(vertical = 14.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(id = R.drawable.nokta_pos_wordmark),
            contentDescription = "Nokta POS",
            modifier = Modifier.width(136.dp),
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = companyName,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = InkSoft,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(3.dp))

        Text(
            text = "CNPJ $cnpj · V$appVersion",
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.3.sp,
            color = MutedAccessible,
            textAlign = TextAlign.Center,
        )
    }
}
