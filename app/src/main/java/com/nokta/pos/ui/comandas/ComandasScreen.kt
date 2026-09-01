package com.nokta.pos.ui.comandas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.ui.components.PosInlineWarning
import com.nokta.pos.ui.components.PosPrimaryButton
import com.nokta.pos.ui.theme.NoktaInk
import com.nokta.pos.ui.theme.NoktaMuted
import com.nokta.pos.ui.theme.NoktaMutedSoft
import com.nokta.pos.ui.theme.NoktaPurple
import com.nokta.pos.ui.theme.NoktaPurpleBright
import com.nokta.pos.ui.theme.NoktaSurface

/* =========================================================================
 *  MEDIDAS
 * ========================================================================= */
private object Dim {
    val ScreenPad = 20.dp
    val SegmentHeight = 74.dp
    val SegmentRadius = 12.dp
    val FieldHeight = 68.dp
    val ButtonHeight = 62.dp
}

private val PageBg = Color(0xFFFAFAFC)
private val SegmentBorder = Color(0xFFEAE8F0)
private val SelectedTint = Color(0xFFF8F4FE)

/**
 * Tela "Comandas" — fluxo simplificado por pulseira/cartão físico. Único
 * lugar para o garçom informar o número: o backend decide se abre direto
 * (pulseira sempre; cartão já vinculado) ou pede vinculação de cliente
 * (cartão disponível) — ver ComandasViewModel.
 */
@Composable
fun ComandasScreen(
    onOpenTab: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ComandasViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // Esta tela fica na pilha de navegação (nunca é recriada) enquanto o
    // garçom abre uma comanda e volta — sem isso, "Em atendimento" mostrava
    // o total antigo até sair e reentrar (o ViewModel só carrega uma vez no
    // init/troca de aba). ON_RESUME recarrega toda vez que a tela volta a
    // ficar visível, inclusive ao voltar de dentro de uma comanda que acabou
    // de receber um item novo.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.loadOpenTabs()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(PageBg)
            .verticalScroll(rememberScrollState())
    ) {
        // ---------- Header ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = Dim.ScreenPad, top = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Voltar",
                    tint = NoktaInk,
                    modifier = Modifier.size(23.dp)
                )
            }

            Spacer(Modifier.width(6.dp))

            Column(Modifier.padding(top = 4.dp)) {
                Text(
                    text = "Comandas",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    color = NoktaInk
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Digite o número da pulseira ou do cartão.",
                    fontSize = 13.5.sp,
                    color = NoktaMuted
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---------- Seletor de tipo ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dim.ScreenPad),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            ComandaKind.entries.forEach { kind ->
                KindSegment(
                    kind = kind,
                    selected = kind == state.selectedKind,
                    onClick = { viewModel.selectKind(kind) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dim.ScreenPad),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when (state.selectedKind) {
                    ComandaKind.WRISTBAND -> "Informe o número da pulseira"
                    ComandaKind.CARD -> "Informe o número do cartão"
                },
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                color = NoktaInk,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = when (state.selectedKind) {
                    ComandaKind.WRISTBAND -> "Digite o número impresso na pulseira do cliente."
                    ComandaKind.CARD -> "Digite o número impresso no cartão da comanda."
                },
                fontSize = 14.5.sp,
                lineHeight = 22.sp,
                color = NoktaMuted,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(22.dp))

            CodeField(
                value = state.code,
                onValueChange = viewModel::setCode,
                label = when (state.selectedKind) {
                    ComandaKind.WRISTBAND -> "Número da pulseira"
                    ComandaKind.CARD -> "Número do cartão"
                },
                kind = state.selectedKind,
                onDone = { viewModel.resolve(onOpenTab) },
            )

            if (state.error != null) {
                Spacer(Modifier.height(14.dp))
                PosInlineWarning(state.error!!)
            }

            Spacer(Modifier.height(22.dp))

            PosPrimaryButton(
                text = "Consultar",
                onClick = { viewModel.resolve(onOpenTab) },
                enabled = state.code.isNotBlank(),
                loading = state.isResolving,
            )

            Spacer(Modifier.height(32.dp))
        }

        OpenTabsSection(
            kind = state.selectedKind,
            tabs = state.openTabs,
            isLoading = state.isLoadingOpenTabs,
            onOpenTab = onOpenTab,
        )

        Spacer(Modifier.height(28.dp))
    }

    if (state.bindForm != null) {
        BindCardDialog(
            form = state.bindForm!!,
            onNameChange = viewModel::setBindFormName,
            onPhoneChange = viewModel::setBindFormPhone,
            onConfirm = { viewModel.confirmBindForm(onOpenTab) },
            onDismiss = viewModel::dismissBindForm,
        )
    }
}

/* --------------------------- Seletor de tipo ------------------------ */

@Composable
private fun KindSegment(
    kind: ComandaKind,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(Dim.SegmentHeight)
            .clip(RoundedCornerShape(Dim.SegmentRadius))
            .background(if (selected) SelectedTint else NoktaSurface)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) NoktaPurpleBright else SegmentBorder,
                shape = RoundedCornerShape(Dim.SegmentRadius)
            )
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when (kind) {
                ComandaKind.WRISTBAND -> WristbandIcon
                ComandaKind.CARD -> Icons.Outlined.CreditCard
            },
            contentDescription = null,
            tint = if (selected) NoktaPurpleBright else NoktaInk,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = kind.label,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) NoktaPurpleBright else NoktaInk,
            maxLines = 1
        )
    }
}

/* ------------------------------- Campo ------------------------------ */

@Composable
private fun CodeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    kind: ComandaKind,
    onDone: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(Dim.FieldHeight),
        label = { Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
        leadingIcon = {
            Icon(
                imageVector = when (kind) {
                    ComandaKind.WRISTBAND -> WristbandIcon
                    ComandaKind.CARD -> Icons.Outlined.CreditCard
                },
                contentDescription = null,
                tint = NoktaPurpleBright,
                modifier = Modifier.size(22.dp)
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        shape = RoundedCornerShape(12.dp),
    )
}

/* ------------------------ Lista "Em atendimento" --------------------- */

@Composable
private fun OpenTabsSection(
    kind: ComandaKind,
    tabs: List<Tab>,
    isLoading: Boolean,
    onOpenTab: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Dim.ScreenPad)) {
        Text(
            text = "Em atendimento",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.2).sp,
            color = NoktaInk,
        )

        Spacer(Modifier.height(14.dp))

        when {
            isLoading && tabs.isEmpty() -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NoktaPurpleBright, modifier = Modifier.size(28.dp))
            }

            tabs.isEmpty() -> Text(
                text = when (kind) {
                    ComandaKind.WRISTBAND -> "Nenhuma pulseira em atendimento no momento."
                    ComandaKind.CARD -> "Nenhum cartão físico em atendimento no momento."
                },
                fontSize = 13.5.sp,
                color = NoktaMuted,
            )

            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tabs.forEach { tab ->
                    OpenTabRow(tab = tab, onClick = { onOpenTab(tab.localId) })
                }
            }
        }
    }
}

@Composable
private fun OpenTabRow(tab: Tab, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NoktaSurface)
            .border(1.dp, SegmentBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = tab.displayName,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = NoktaInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            tab.customerName?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(text = it, fontSize = 12.5.sp, color = NoktaMutedSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            text = tab.total.formatBRL(),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = NoktaInk,
        )
    }
}

/* --------------------------- Vincular cartão ------------------------- */

/**
 * Nome completo e telefone sempre exigidos antes de abrir a comanda de
 * cartão — mesma regra de produto da comanda comum (nunca "cliente não
 * informado"). Pulseira nunca passa por aqui (ver ComandasViewModel).
 */
@Composable
private fun BindCardDialog(
    form: BindCardFormState,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = { if (!form.isSaving) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.large, color = NoktaSurface) {
            Column(Modifier.padding(20.dp)) {
                Text("Cartão ${form.publicCode} disponível", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = NoktaInk)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Identifique o cliente para iniciar o atendimento.",
                    fontSize = 13.sp,
                    color = NoktaMuted,
                )

                Spacer(Modifier.height(18.dp))

                OutlinedTextField(
                    value = form.name,
                    onValueChange = onNameChange,
                    label = { Text("Nome completo") },
                    leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    singleLine = true,
                    enabled = !form.isSaving,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = form.phone,
                    onValueChange = onPhoneChange,
                    label = { Text("Telefone") },
                    leadingIcon = { Icon(Icons.Outlined.Phone, contentDescription = null) },
                    singleLine = true,
                    enabled = !form.isSaving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (form.error != null) {
                    Spacer(Modifier.height(10.dp))
                    PosInlineWarning(form.error)
                }

                Spacer(Modifier.height(20.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onDismiss, enabled = !form.isSaving, modifier = Modifier.weight(1f)) {
                        Text("Cancelar")
                    }
                }
                Spacer(Modifier.height(6.dp))
                PosPrimaryButton(
                    text = "Iniciar atendimento",
                    onClick = onConfirm,
                    enabled = form.isValid,
                    loading = form.isSaving,
                )
            }
        }
    }
}
