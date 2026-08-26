package com.nokta.pos.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nokta.pos.common.Money
import com.nokta.pos.ui.theme.AlertRed
import com.nokta.pos.ui.theme.AlertRedLight
import com.nokta.pos.ui.theme.MoneyGreen
import com.nokta.pos.ui.theme.MoneyGreenLight
import com.nokta.pos.ui.theme.WarningAmber
import com.nokta.pos.ui.theme.WarningAmberLight

/**
 * Peças visuais compartilhadas do POS. Existem para que toda tela tenha o
 * mesmo alvo de toque, o mesmo peso de texto e o mesmo jeito de mostrar
 * dinheiro — um operador não deveria precisar reaprender a ler a tela a cada
 * fluxo.
 *
 * Altura mínima de 56dp em tudo que é tocável (acima dos 48dp do Material):
 * o operador toca em movimento, muitas vezes com a maquininha numa mão só.
 */

/** Ação principal de uma tela. Só deve existir UMA por tela. */
@Composable
fun PosPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.medium,
        // Sem elevação: o botão primário já se distingue pela cor de marca;
        // sombra colorida por baixo é o que dá aparência de template.
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(12.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun PosSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Barra de topo padrão. `onBack` nulo esconde a seta (telas raiz). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", modifier = Modifier.size(26.dp))
                }
            }
        },
        actions = actions,
    )
}

/**
 * Linha de dinheiro. `emphasized` marca o número que decide a ação atual
 * (total a pagar, saldo restante) — sempre o maior da tela.
 */
@Composable
fun MoneyRow(
    label: String,
    amount: Money,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    positive: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            amount.formatBRL(),
            style = if (emphasized) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
            color = when {
                positive -> MoneyGreen
                emphasized -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

enum class PosBadgeTone { NEUTRAL, SUCCESS, WARNING, DANGER }

@Composable
fun PosBadge(text: String, tone: PosBadgeTone = PosBadgeTone.NEUTRAL, modifier: Modifier = Modifier) {
    val (bg, fg) = when (tone) {
        PosBadgeTone.SUCCESS -> MoneyGreenLight to MoneyGreen
        PosBadgeTone.WARNING -> WarningAmberLight to WarningAmber
        PosBadgeTone.DANGER -> MaterialTheme.colorScheme.errorContainer to AlertRed
        PosBadgeTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

/** Estado vazio/erro com ação opcional — nunca uma tela em branco sem saída. */
@Composable
fun PosEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            PosPrimaryButton(text = actionText, onClick = onAction)
        }
    }
}

@Composable
fun PosLoading(modifier: Modifier = Modifier, label: String? = null) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Traço fino e na cor de marca — carregar é momento de espera, não de
        // alarde; um indicador grosso e cinza parece do sistema, não do produto.
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        if (label != null) {
            Spacer(Modifier.height(14.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Aviso persistente no topo do conteúdo (ex.: caixa fechado, sem conexão).
 * Fundo bem lavado + borda da própria cor: informa sem virar um bloco chapado
 * disputando atenção com a ação principal.
 */
@Composable
fun PosInlineWarning(text: String, modifier: Modifier = Modifier, tone: PosBadgeTone = PosBadgeTone.WARNING) {
    val (bg, fg) = when (tone) {
        PosBadgeTone.DANGER -> AlertRedLight to AlertRed
        else -> WarningAmberLight to WarningAmber
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.18f), MaterialTheme.shapes.small)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = fg)
    }
}

/** Teclado numérico grande — digitar número de mesa/comanda/valor sem o teclado do sistema. */
@Composable
fun PosNumpad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    extraKey: String? = null,
    onExtraKey: (() -> Unit)? = null,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(extraKey ?: "", "0", "⌫"),
    )
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 62.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(
                                if (key.isBlank()) Color.Transparent
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .then(
                                if (key.isBlank()) Modifier
                                else Modifier.clickable {
                                    when (key) {
                                        "⌫" -> onBackspace()
                                        extraKey -> onExtraKey?.invoke()
                                        else -> onDigit(key.first())
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key.isNotBlank()) {
                            Text(key, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Diálogo de tela cheia para digitar manualmente o valor recebido em dinheiro.
 *
 * Existe porque um POS que só oferece "Exato" + 3 sugestões fixas de troco
 * (R$20/R$50/R$100) não cobre o caso comum de o cliente entregar qualquer
 * outra nota/quantia — sem isto o operador não tinha como registrar o valor
 * real recebido. Os dígitos entram da direita para a esquerda, como numa
 * calculadora/maquininha (ex.: "1","5","0" vira R$ 1,50, não "150,00" seguido
 * de correção) — evita o erro comum de esquecer a vírgula em um teclado de
 * texto livre.
 */
@Composable
fun ReceivedAmountDialog(
    initialCents: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var cents by remember { mutableStateOf(initialCents) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Valor recebido", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Text(
                    Money(cents).formatBRL(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(20.dp))
                PosNumpad(
                    onDigit = { d ->
                        // Máximo R$ 999.999,99 — teto generoso só para não estourar Long/UI.
                        if (cents < 99_999_999L) cents = cents * 10 + (d - '0')
                    },
                    onBackspace = { cents /= 10 },
                    modifier = Modifier.width(260.dp),
                )
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancelar") }
                    Button(
                        onClick = { onConfirm(cents) },
                        enabled = cents > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("Usar valor") }
                }
            }
        }
    }
}
