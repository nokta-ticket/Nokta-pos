package com.nokta.pos.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.ui.components.PosBadgeTone
import com.nokta.pos.ui.components.PosInlineWarning
import com.nokta.pos.ui.theme.NoktaInk
import com.nokta.pos.ui.theme.NoktaMuted
import com.nokta.pos.ui.theme.NoktaMutedSoft
import com.nokta.pos.ui.theme.NoktaPurple
import com.nokta.pos.ui.theme.NoktaPurpleBright
import com.nokta.pos.ui.theme.NoktaSurface
import kotlinx.coroutines.delay

private object Dim {
    val ScreenPad = 22.dp
    val CellHeight = 62.dp
    val CellRadius = 13.dp
    val CellGap = 9.dp
    val KeyGap = 10.dp
    val CtaHeight = 62.dp
}

private val PageBg = Color(0xFFF8F7FB)
private val CellBorder = Color(0xFFE9E7F0)
private val KeyBg = Color(0xFFFBFAFD)
private val CtaDisabledBg = Color(0xFFD9CFF2)
private val DividerColor = Color(0xFFEDEBF3)

private const val CODE_LENGTH = 6

/**
 * Ativação do terminal — o gerente digita o código de 6 dígitos gerado no
 * dashboard (Operação › Terminais › Novo terminal). Acontece UMA vez por
 * maquininha: o pareamento sobrevive a troca de operador e a reinício do app
 * (ver docs/pos-mvp-architecture.md, "dois estágios de identidade").
 *
 * Visual portado de um mockup de referência — teclado numérico próprio (não
 * o do sistema, o alvo grande evita erro de digitação em pé) com layout tipo
 * telefone (letras ABC/DEF sob os dígitos, só decorativo). Lógica intocada:
 * [PairingViewModel] continua a única fonte de verdade do código/erro/estado.
 */
@Composable
fun PairingScreen(onPaired: () -> Unit, viewModel: PairingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.alreadyPaired) {
        if (state.alreadyPaired) onPaired()
    }

    val complete = state.code.length == CODE_LENGTH

    Column(Modifier.fillMaxSize().background(PageBg)) {

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dim.ScreenPad),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(26.dp))

            Text("NOKTA", fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 7.sp, color = NoktaInk)

            Spacer(Modifier.height(30.dp))

            Text("Ativar terminal", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, color = NoktaInk)

            Spacer(Modifier.height(10.dp))

            Text(
                "Digite o código de 6 dígitos gerado\nno painel Nokta.",
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
                color = NoktaMuted,
            )

            Spacer(Modifier.height(26.dp))

            CodeCells(code = state.code, hasError = state.error != null)

            state.error?.let {
                Spacer(Modifier.height(16.dp))
                PosInlineWarning(it, tone = PosBadgeTone.DANGER)
            }

            Spacer(Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = NoktaPurple, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(9.dp))
                Text("Seu terminal ficará vinculado a esta unidade.", fontSize = 13.5.sp, color = NoktaMuted)
            }

            Spacer(Modifier.height(22.dp))
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))

        Keypad(
            modifier = Modifier.weight(1f).padding(horizontal = Dim.ScreenPad).padding(top = 16.dp, bottom = 12.dp),
            enabled = !state.isSubmitting,
            onDigit = { digit -> viewModel.onCodeChanged(state.code + digit) },
            onBackspace = { viewModel.onCodeChanged(state.code.dropLast(1)) },
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dim.ScreenPad).padding(bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val canSubmit = complete && !state.isSubmitting
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dim.CtaHeight)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (complete) NoktaPurpleBright else CtaDisabledBg)
                    .clickable(enabled = canSubmit, onClick = { viewModel.submit(onPaired) }),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = if (complete) Color.White else NoktaPurple, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(11.dp))
                    Text(
                        "Ativar terminal",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp,
                        color = if (complete) Color.White else NoktaPurple,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = NoktaMutedSoft, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(7.dp))
                Text("Peça ao gerente o código gerado em Operação › Terminais.", fontSize = 12.5.sp, color = NoktaMutedSoft)
            }
        }
    }
}

/** Seis caixas — o operador vê exatamente quantos dígitos faltam, com cursor piscando na próxima posição. */
@Composable
private fun CodeCells(code: String, hasError: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dim.CellGap)) {
        repeat(CODE_LENGTH) { index ->
            val filled = index < code.length
            val focused = index == code.length && !hasError

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(Dim.CellHeight)
                    .clip(RoundedCornerShape(Dim.CellRadius))
                    .background(if (focused) NoktaSurface else Color.Transparent)
                    .border(
                        width = if (focused || hasError) 1.5.dp else 1.dp,
                        color = if (hasError) MaterialThemeErrorColor() else if (focused) NoktaPurple else CellBorder,
                        shape = RoundedCornerShape(Dim.CellRadius),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    filled -> Text(code[index].toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = NoktaInk)
                    focused -> BlinkingCursor()
                }
            }
        }
    }
}

@Composable
private fun MaterialThemeErrorColor(): Color = androidx.compose.material3.MaterialTheme.colorScheme.error

@Composable
private fun BlinkingCursor() {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            visible = !visible
        }
    }
    Box(Modifier.alpha(if (visible) 1f else 0f).width(1.6.dp).height(26.dp).background(NoktaInk))
}

private data class Key(val digit: Char, val letters: String)

private val keyRows = listOf(
    listOf(Key('1', ""), Key('2', "ABC"), Key('3', "DEF")),
    listOf(Key('4', "GHI"), Key('5', "JKL"), Key('6', "MNO")),
    listOf(Key('7', "PQRS"), Key('8', "TUV"), Key('9', "WXYZ")),
)

@Composable
private fun Keypad(modifier: Modifier = Modifier, enabled: Boolean, onDigit: (Char) -> Unit, onBackspace: () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dim.KeyGap)) {
        keyRows.forEach { row ->
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Dim.KeyGap)) {
                row.forEach { key ->
                    DigitKey(key = key, enabled = enabled, onClick = { onDigit(key.digit) }, modifier = Modifier.weight(1f))
                }
            }
        }

        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Dim.KeyGap)) {
            Spacer(Modifier.weight(1f))

            DigitKey(key = Key('0', ""), enabled = enabled, onClick = { onDigit('0') }, modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Transparent)
                    .border(1.dp, CellBorder, RoundedCornerShape(12.dp))
                    .clickable(
                        enabled = enabled,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBackspace,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = "Apagar", tint = NoktaInk, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun DigitKey(key: Key, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(KeyBg)
            .border(1.dp, Color(0xFFF1EFF6), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(key.digit.toString(), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = NoktaInk)
        if (key.letters.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(key.letters, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp, color = NoktaMutedSoft)
        }
    }
}
