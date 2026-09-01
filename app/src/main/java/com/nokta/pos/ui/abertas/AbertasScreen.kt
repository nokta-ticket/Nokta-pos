package com.nokta.pos.ui.abertas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TableRestaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabStatus
import com.nokta.pos.comanda.domain.TabType
import com.nokta.pos.ui.components.OnResumeEffect
import com.nokta.pos.ui.components.PosLoading
import com.nokta.pos.ui.theme.NoktaInk
import com.nokta.pos.ui.theme.NoktaMuted
import com.nokta.pos.ui.theme.NoktaMutedSoft
import com.nokta.pos.ui.theme.NoktaPurple
import com.nokta.pos.ui.theme.NoktaPurpleBright
import com.nokta.pos.ui.theme.NoktaSurface
import java.text.SimpleDateFormat
import java.util.Locale

private object AbertasDim {
    val ScreenPad = 16.dp
    val TopBarHeight = 56.dp
    val TitleSize = 21.sp
    val FieldHeight = 38.dp
    val FieldRadius = 10.dp
    val CardRadius = 10.dp
    val IconBox = 42.dp
}

private val PageBg = Color(0xFFF7F6FA)
private val IconBoxBg = Color(0xFFF1ECFB)
private val BadgeBg = Color(0xFFF1EAFD)
private val LineColor = Color(0xFFEFEDF5)
private val FieldBorder = Color(0xFFE7E4EF)

private val openedAtFormat = SimpleDateFormat("HH:mm", Locale("pt", "BR"))

/**
 * Card "Abertas" da Home: tudo que está fisicamente em atendimento agora,
 * numa lista só — mesa, cartão físico e pulseira (ver AbertasViewModel).
 * Puramente consulta, sem ação de abrir nada aqui (isso é papel de
 * Mesas/Comandas) — clicar num item leva direto pro detalhamento.
 */
@Composable
fun AbertasScreen(
    onOpenTab: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: AbertasViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var query by remember { mutableStateOf("") }

    // Esta tela fica na pilha de navegação enquanto o garçom abre uma
    // comanda e volta — sem recarregar em ON_RESUME, a lista mostrava
    // total/itens desatualizados até sair pra Home e reentrar.
    OnResumeEffect(viewModel::load)

    val filtered = if (query.isBlank()) {
        state.tabs
    } else {
        state.tabs.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.customerName?.contains(query, ignoreCase = true) == true
        }
    }

    Column(Modifier.fillMaxSize().background(PageBg)) {
        TopBar(onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = AbertasDim.ScreenPad, end = AbertasDim.ScreenPad, top = 14.dp, bottom = 24.dp),
        ) {
            item {
                SearchField(query = query, onQueryChange = { query = it })

                Spacer(Modifier.height(20.dp))

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Em atendimento",
                        modifier = Modifier.weight(1f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp,
                        color = NoktaInk,
                    )
                    if (!state.isLoading) CountBadge(count = filtered.size)
                }

                Spacer(Modifier.height(14.dp))

                if (state.isLoading) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        PosLoading(label = "Carregando…")
                    }
                }
            }

            items(filtered, key = { it.localId }) { tab ->
                OpenTabCard(tab = tab, onClick = { onOpenTab(tab.localId) })
                Spacer(Modifier.height(10.dp))
            }

            if (!state.isLoading && filtered.isEmpty()) {
                item {
                    Text(
                        text = if (state.tabs.isEmpty()) "Nada em atendimento no momento." else "Nenhum resultado encontrado.",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        fontSize = 12.5.sp,
                        color = NoktaMutedSoft,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Column(Modifier.background(NoktaSurface)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(AbertasDim.TopBarHeight).padding(start = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = NoktaInk, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(text = "Abertas", fontSize = AbertasDim.TitleSize, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, color = NoktaInk)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(LineColor))
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AbertasDim.FieldHeight)
            .clip(RoundedCornerShape(AbertasDim.FieldRadius))
            .background(NoktaSurface)
            .border(1.dp, FieldBorder, RoundedCornerShape(AbertasDim.FieldRadius))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = Icons.Outlined.Search, contentDescription = null, tint = NoktaMutedSoft, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(9.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(text = "Buscar por mesa, código ou cliente", fontSize = 12.5.sp, color = NoktaMutedSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.5.sp, color = NoktaInk),
                cursorBrush = SolidColor(NoktaPurple),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CountBadge(count: Int) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(BadgeBg).padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = if (count == 1) "1 aberto" else "$count abertos",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = NoktaPurpleBright,
        )
    }
}

@Composable
private fun OpenTabCard(tab: Tab, onClick: () -> Unit) {
    val openedAtLabel = tab.openedAt?.let {
        runCatching { openedAtFormat.format(java.util.Date.from(java.time.Instant.parse(it))) }.getOrNull()
    }
    val icon = when (tab.type) {
        TabType.TABLE -> Icons.Outlined.TableRestaurant
        TabType.WRISTBAND -> Icons.Outlined.CreditCard
        TabType.INDIVIDUAL, TabType.COUNTER -> Icons.Outlined.Description
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AbertasDim.CardRadius))
            .background(NoktaSurface)
            .border(1.dp, LineColor, RoundedCornerShape(AbertasDim.CardRadius))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(AbertasDim.IconBox).clip(RoundedCornerShape(8.dp)).background(IconBoxBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = NoktaPurpleBright, modifier = Modifier.size(20.dp))
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(text = tab.displayName, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = NoktaInk, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    tab.customerName?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
                    val count = tab.activeItemCount
                    append(if (count == 1) "1 item" else "$count itens")
                    openedAtLabel?.let { append(" · "); append(it) }
                },
                fontSize = 11.5.sp,
                color = NoktaMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = tab.remaining.formatBRL(),
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                color = NoktaInk,
                maxLines = 1,
            )
            Spacer(Modifier.height(5.dp))
            when (tab.status) {
                TabStatus.CLOSING -> StatusPill("Fechando a conta")
                TabStatus.PAYMENT_IN_PROGRESS -> StatusPill("Recebendo pagamento")
                else -> if (tab.hasPartialPayment) StatusPill("Pagamento parcial")
            }
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(BadgeBg).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text = text, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = NoktaPurpleBright, maxLines = 1)
    }
}
