package com.nokta.pos.ui.comanda

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.TextStyle
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
import com.nokta.pos.comanda.domain.TabStatus
import com.nokta.pos.ui.components.PosInlineWarning
import com.nokta.pos.ui.components.PosPrimaryButton
import com.nokta.pos.ui.theme.AlertRed
import com.nokta.pos.ui.theme.MoneyGreen
import com.nokta.pos.ui.theme.NoktaInk
import com.nokta.pos.ui.theme.NoktaInkSoft
import com.nokta.pos.ui.theme.NoktaMuted
import com.nokta.pos.ui.theme.NoktaMutedSoft
import com.nokta.pos.ui.theme.NoktaPurple
import com.nokta.pos.ui.theme.NoktaPurpleBright
import com.nokta.pos.ui.theme.NoktaSurface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/* =========================================================================
 *  MEDIDAS
 * ========================================================================= */
private object ComandasDim {
    val ScreenPad = 16.dp
    val TopBarHeight = 60.dp
    val FieldHeight = 44.dp
    val FieldRadius = 12.dp
    val TabsHeight = 38.dp
    val CardRadius = 12.dp
    val CardGap = 8.dp
    val IconBox = 40.dp
}

private val FieldBorder = Color(0xFFE7E4EF)
private val CardBorder = Color(0xFFEFEDF5)
private val TabsBg = Color(0xFFF5F4F8)
private val IconBoxBg = Color(0xFFF3EDFC)
private val BadgeBg = Color(0xFFF1EAFD)

private val openedAtFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Tela "Comandas" — só TabType.INDIVIDUAL (comanda de cliente; mesa é outro
 * fluxo, ver MesasScreen; balcão nem passa por aqui). Duas abas
 * (Abertas/Encerradas) com busca por código/nome, e um botão "+" no topo
 * para abrir comanda nova — o garçom precisa conseguir fazer isso sozinho na
 * maquininha, sem depender da recepção (que também tem essa ação, só que no
 * dashboard web).
 *
 * Nunca existe "cliente não informado": abrir comanda exige nome completo e
 * telefone antes de confirmar (ver [NovaComandaDialog] e
 * [ComandasViewModel.confirmNovaComanda]).
 */
@Composable
fun ComandasScreen(
    onOpenTab: (String) -> Unit,
    onBack: () -> Unit,
    onAddComanda: () -> Unit,
    viewModel: ComandasViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Column(Modifier.fillMaxSize().background(NoktaSurface)) {

        TopBar(onBack = onBack, onAdd = onAddComanda)

        Column(Modifier.padding(horizontal = ComandasDim.ScreenPad)) {

            SearchField(query = state.query, onQueryChange = viewModel::setQuery)

            Spacer(Modifier.height(16.dp))

            Tabs(
                selected = state.selectedTab,
                openCount = state.openTabs.size,
                closedCount = state.closedTabs.size,
                onSelect = viewModel::selectTab,
            )

            Spacer(Modifier.height(22.dp))

            Text(
                text = if (state.selectedTab == ComandaTab.OPEN) "Abertas" else "Encerradas",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
                color = NoktaInk,
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = if (state.selectedTab == ComandaTab.OPEN) {
                    "Todas as comandas em aberto no estabelecimento."
                } else {
                    "Comandas encerradas recentemente no estabelecimento."
                },
                fontSize = 13.sp,
                color = NoktaMutedSoft,
            )

            if (state.error != null) {
                Spacer(Modifier.height(10.dp))
                PosInlineWarning(state.error!!)
            }

            Spacer(Modifier.height(12.dp))
        }

        val visible = state.visibleTabs
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ComandasDim.ScreenPad, end = ComandasDim.ScreenPad, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(ComandasDim.CardGap),
        ) {
            items(visible, key = { it.localId }) { tab ->
                ComandaCard(tab = tab, onClick = { onOpenTab(tab.localId) })
            }

            if (visible.isEmpty() && !state.isLoading) {
                item {
                    Text(
                        text = "Nenhuma comanda por aqui.",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        fontSize = 13.sp,
                        color = NoktaMutedSoft,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    if (state.form.visible) {
        NovaComandaDialog(
            form = state.form,
            onNameChange = viewModel::setFormName,
            onPhoneChange = viewModel::setFormPhone,
            onConfirm = { viewModel.confirmNovaComanda(onOpened = onOpenTab) },
            onDismiss = viewModel::dismissNovaComandaForm,
        )
    }
}

/* ------------------------------ Top bar ----------------------------- */

@Composable
private fun TopBar(onBack: () -> Unit, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(ComandasDim.TopBarHeight).padding(start = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = NoktaInk, modifier = Modifier.size(22.dp))
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = "Comandas",
            modifier = Modifier.weight(1f),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
            color = NoktaInk,
        )

        // Ação de abrir comanda nova — o garçom precisa conseguir fazer isso
        // sozinho na maquininha (a recepção tem o equivalente no dashboard).
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(NoktaPurpleBright).clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PlusThin, contentDescription = "Nova comanda", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/* ------------------------------- Busca ------------------------------ */

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ComandasDim.FieldHeight)
            .clip(RoundedCornerShape(ComandasDim.FieldRadius))
            .background(NoktaSurface)
            .border(1.dp, FieldBorder, RoundedCornerShape(ComandasDim.FieldRadius))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = NoktaMutedSoft, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(11.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "Número da comanda ou nome do cliente",
                    fontSize = 13.5.sp,
                    color = NoktaMutedSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.5.sp, color = NoktaInk),
                cursorBrush = SolidColor(NoktaPurple),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/* -------------------------------- Abas ------------------------------ */

@Composable
private fun Tabs(selected: ComandaTab, openCount: Int, closedCount: Int, onSelect: (ComandaTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(ComandasDim.TabsHeight).clip(RoundedCornerShape(10.dp)).background(TabsBg),
    ) {
        TabItem(
            label = "Abertas",
            count = openCount,
            selected = selected == ComandaTab.OPEN,
            onClick = { onSelect(ComandaTab.OPEN) },
            modifier = Modifier.weight(1f),
        )
        TabItem(
            label = "Encerradas",
            count = closedCount,
            selected = selected == ComandaTab.CLOSED,
            onClick = { onSelect(ComandaTab.CLOSED) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TabItem(label: String, count: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (selected) NoktaSurface else Color.Transparent)
            .clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) NoktaInk else NoktaInkSoft,
            )
            Spacer(Modifier.width(8.dp))
            if (selected) {
                Box(
                    modifier = Modifier.size(20.dp).clip(CircleShape).background(BadgeBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = count.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NoktaPurple)
                }
            } else {
                Text(text = count.toString(), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = NoktaMuted)
            }
        }

        if (selected) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.5.dp).background(NoktaPurpleBright))
        }
    }
}

/* --------------------------- Card da comanda ------------------------ */

@Composable
private fun ComandaCard(tab: Tab, onClick: () -> Unit) {
    val openedAtLabel = tab.openedAt?.let {
        runCatching {
            Instant.parse(it).atZone(ZoneId.systemDefault()).format(openedAtFormatter)
        }.getOrNull()
    }
    val itemCount = tab.activeItems.sumOf { it.quantity }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ComandasDim.CardRadius))
            .background(NoktaSurface)
            .border(1.dp, CardBorder, RoundedCornerShape(ComandasDim.CardRadius))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(ComandasDim.IconBox).clip(RoundedCornerShape(9.dp)).background(IconBoxBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = NoktaPurpleBright, modifier = Modifier.size(21.dp))
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = "Comanda #${tab.publicCode}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                color = NoktaInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(6.dp))

            // Sempre preenchido — não existe comanda sem cliente identificado.
            InfoLine(icon = Icons.Outlined.Person, text = tab.customerName.orEmpty())

            if (openedAtLabel != null) {
                Spacer(Modifier.height(4.dp))
                InfoLine(icon = Icons.Outlined.Schedule, text = "Aberta às $openedAtLabel")
            }
        }

        Spacer(Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = tab.total.formatBRL(),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                color = if (tab.status == TabStatus.CLOSED && tab.isFullyPaid) MoneyGreen else NoktaInk,
                maxLines = 1,
            )
            Spacer(Modifier.height(7.dp))
            Box(
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(BadgeBg).padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = if (itemCount == 1) "1 item" else "$itemCount itens",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = NoktaPurple,
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.width(6.dp))

        Icon(imageVector = ChevronRightThin, contentDescription = null, tint = NoktaMutedSoft, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = NoktaMutedSoft, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(7.dp))
        Text(text = text, fontSize = 13.sp, color = NoktaMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/* --------------------------- Nova comanda ---------------------------- */

/**
 * Nome completo e telefone são sempre exigidos antes de abrir a comanda —
 * decisão explícita de produto, nunca "cliente não informado". O botão
 * "Abrir comanda" só habilita com [NovaComandaFormState.isValid].
 */
@Composable
private fun NovaComandaDialog(
    form: NovaComandaFormState,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = { if (!form.isSaving) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.large, color = NoktaSurface) {
            Column(Modifier.padding(20.dp)) {
                Text("Nova comanda", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = NoktaInk)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Identifique o cliente antes de abrir a comanda.",
                    fontSize = 13.sp,
                    color = NoktaMutedSoft,
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
                    Text(form.error, fontSize = 12.5.sp, color = AlertRed)
                }

                Spacer(Modifier.height(20.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onDismiss, enabled = !form.isSaving, modifier = Modifier.weight(1f)) {
                        Text("Cancelar")
                    }
                }
                Spacer(Modifier.height(6.dp))
                PosPrimaryButton(
                    text = "Abrir comanda",
                    onClick = onConfirm,
                    enabled = form.isValid,
                    loading = form.isSaving,
                )
            }
        }
    }
}

/* ------------------------------ Helpers ----------------------------- */

private val ChevronRightThin: ImageVector by lazy {
    ImageVector.Builder(
        name = "ChevronRightThin",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            strokeLineMiter = 10f,
            pathFillType = PathFillType.NonZero,
        ) {
            moveTo(9f, 5.5f)
            lineTo(15.5f, 12f)
            lineTo(9f, 18.5f)
        }
    }.build()
}

private val PlusThin: ImageVector by lazy {
    ImageVector.Builder(
        name = "PlusThin",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            strokeLineMiter = 10f,
            pathFillType = PathFillType.NonZero,
        ) {
            moveTo(12f, 5f)
            lineTo(12f, 19f)
            moveTo(5f, 12f)
            lineTo(19f, 12f)
        }
    }.build()
}
