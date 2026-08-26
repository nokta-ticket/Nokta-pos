package com.nokta.pos.ui.venda

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.common.Money
import com.nokta.pos.ui.cardapio.CardapioScreen

/**
 * Venda de balcão de ponta a ponta, em duas telas: cardápio → pagamento.
 *
 * É o fluxo mais curto possível sem perder registro: nenhuma tela de comanda
 * aparece para o operador, mas por baixo a venda vira uma comanda COUNTER
 * completa no backend (item 6 e 18 do brief — interface simples, contabilidade
 * inteira).
 */
@Composable
fun NovaVendaScreen(
    onFinished: () -> Unit,
    onBack: () -> Unit,
    viewModel: BalcaoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // Voltar do pagamento devolve ao carrinho (nada foi cobrado ainda);
    // durante o processamento o gesto é ignorado para não abandonar uma
    // cobrança em andamento.
    BackHandler(enabled = state.stage != BalcaoStage.CART) {
        if (!state.isProcessing) viewModel.reset()
    }

    when (state.stage) {
        BalcaoStage.CART -> CardapioScreen(
            title = "Nova venda",
            subtitle = "Balcão",
            confirmLabel = "Ir para pagamento",
            onDone = onBack,
            onCartConfirmed = { cart ->
                viewModel.setCart(cart)
                viewModel.goToPayment()
            },
        )

        BalcaoStage.PAYING -> BalcaoPagamentoScreen(
            state = state,
            onSelectMethod = viewModel::selectMethod,
            onSetReceived = viewModel::setReceived,
            onSetSplitPeople = viewModel::setSplitPeople,
            onToggleEditCart = viewModel::toggleEditCart,
            onIncreaseLine = viewModel::increaseCartLine,
            onDecreaseLine = viewModel::decreaseCartLine,
            onRemoveLine = viewModel::requestRemoveCartLine,
            onConfirmRemoveLine = viewModel::confirmRemoveCartLine,
            onDismissRemoveLine = viewModel::dismissRemoveCartLine,
            onConfirm = viewModel::confirmPayment,
            onBack = { viewModel.backToCart() },
            onDismissError = viewModel::dismissError,
        )

        BalcaoStage.DONE -> BalcaoConcluidoScreen(
            total = state.tab?.total ?: state.total,
            change = state.changeDue,
            onNewSale = viewModel::reset,
            onHome = {
                viewModel.reset()
                onFinished()
            },
        )
    }
}
