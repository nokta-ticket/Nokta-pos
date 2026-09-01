package com.nokta.pos.ui

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder

/**
 * Protege a navegação contra duplo disparo — o "miss click" que empilhava a
 * mesma tela duas vezes.
 *
 * Dois toques rápidos no mesmo card disparam dois `navigate()` antes de a
 * primeira recomposição acontecer: o Compose Navigation aceita os dois e
 * empilha duas instâncias do MESMO destino. O sintoma para o operador é
 * "apertei voltar e não voltou" (voltou para a cópia idêntica embaixo) e,
 * quando isso se acumula, uma tela em branco — a pilha esvazia por um
 * `popBackStack()` a mais e o NavHost fica sem destino para renderizar.
 *
 * A defesa padrão do Android para isto é checar o estado do lifecycle da
 * entrada atual: só a entrada REALMENTE visível está em RESUMED. Assim que o
 * primeiro `navigate()` acontece, a entrada de origem cai para STARTED, então
 * o segundo toque (que ainda vem da tela antiga) é descartado — sem timers,
 * sem debounce por tempo, sem estado extra para manter.
 */
fun NavController.navigateOnce(route: String, builder: NavOptionsBuilder.() -> Unit = {}) {
    if (currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
        navigate(route, builder)
    }
}

/**
 * Mesma proteção de [navigateOnce] para o caminho de volta: dois toques na
 * seta viravam dois `popBackStack()`, pulando uma tela a mais do que o
 * operador pediu (ou esvaziando a pilha).
 *
 * Devolve `false` quando não havia mais para onde voltar — o chamador decide
 * o destino de recuperação (ver ComandaScreen no NavHost).
 */
fun NavController.popBackStackOnce(): Boolean {
    if (currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) {
        // Uma navegação já está em curso: o toque extra não deve empilhar
        // outro pop, e também não deve ser tratado como "pilha vazia".
        return true
    }
    return popBackStack()
}
