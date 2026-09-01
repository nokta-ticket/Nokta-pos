package com.nokta.pos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Dispara [onResume] toda vez que esta tela volta a ficar visível (ON_RESUME
 * do lifecycle) — inclusive ao voltar de uma tela filha (ex.: abrir uma
 * comanda e apertar voltar), não só na primeira composição.
 *
 * Necessário porque telas de lista "Em atendimento" (Comandas, Abertas,
 * Mesas) ficam na pilha de navegação — nunca são recriadas quando o garçom
 * abre uma comanda e volta — mas seus ViewModels só carregavam os dados uma
 * vez, no `init`. Sem isto, o total/itens mostrados ficavam desatualizados
 * até sair pra Home e reentrar (bug real reportado 2x: Comandas e Abertas).
 */
@Composable
fun OnResumeEffect(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
