package com.nokta.pos.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.ui.components.PosBadgeTone
import com.nokta.pos.ui.components.PosInlineWarning
import com.nokta.pos.ui.components.PosNumpad
import com.nokta.pos.ui.components.PosPrimaryButton

/**
 * Ativação do terminal — o gerente digita o código de 6 dígitos gerado no
 * dashboard (Operação › Terminais › Novo terminal). Acontece UMA vez por
 * maquininha: o pareamento sobrevive a troca de operador e a reinício do app
 * (ver docs/pos-mvp-architecture.md, "dois estágios de identidade").
 *
 * Teclado próprio em vez do teclado do sistema: são só dígitos, e o alvo
 * grande evita erro de digitação em pé.
 */
@Composable
fun PairingScreen(onPaired: () -> Unit, viewModel: PairingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.alreadyPaired) {
        if (state.alreadyPaired) onPaired()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Text("nokta", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text("Ative este terminal", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Peça ao gerente o código de 6 dígitos gerado no painel Nokta, em Operação › Terminais.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))
        CodeDisplay(code = state.code, hasError = state.error != null)

        state.error?.let {
            Spacer(Modifier.height(16.dp))
            PosInlineWarning(it, tone = PosBadgeTone.DANGER)
        }

        Spacer(Modifier.height(24.dp))
        PosNumpad(
            onDigit = { digit -> viewModel.onCodeChanged(state.code + digit) },
            onBackspace = { viewModel.onCodeChanged(state.code.dropLast(1)) },
        )

        Spacer(Modifier.height(20.dp))
        PosPrimaryButton(
            text = "Ativar terminal",
            onClick = { viewModel.submit(onPaired) },
            enabled = state.code.length == 6,
            loading = state.isSubmitting,
        )
    }
}

/** Seis caixas — o operador vê exatamente quantos dígitos faltam. */
@Composable
private fun CodeDisplay(code: String, hasError: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(6) { index ->
            val filled = index < code.length
            val borderColor = when {
                hasError -> MaterialTheme.colorScheme.error
                filled -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline
            }
            Box(
                modifier = Modifier
                    .size(width = 46.dp, height = 60.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(if (filled) 2.dp else 1.5.dp, borderColor, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (filled) code[index].toString() else "",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
    }
}
