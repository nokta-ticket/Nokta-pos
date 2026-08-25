package com.nokta.pos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tema do POS. Não é o tema do dashboard — aqui a prioridade é legibilidade
 * em pé, sob luz ruim, com a tela a meio metro do olho e o operador com uma
 * mão só. Por isso: fontes maiores que o padrão Material, alvos de toque
 * grandes e contraste alto.
 *
 * O roxo é a cor de marca da Nokta (mesma família do dashboard), mas usado
 * com parcimônia: no POS ele marca a AÇÃO principal de cada tela, nunca
 * decora. Verde/vermelho ficam reservados a dinheiro (pago/em aberto) para
 * que o operador leia o estado da conta sem precisar ler texto.
 */

val NoktaPurple = Color(0xFF7C3AED)
val NoktaPurpleDark = Color(0xFF6D28D9)
val NoktaPurpleLight = Color(0xFFEDE9FE)

val MoneyGreen = Color(0xFF059669)
val MoneyGreenLight = Color(0xFFD1FAE5)
val AlertRed = Color(0xFFDC2626)
val AlertRedLight = Color(0xFFFEE2E2)
val WarningAmber = Color(0xFFD97706)
val WarningAmberLight = Color(0xFFFEF3C7)

val InkBlack = Color(0xFF111827)
val InkGray = Color(0xFF6B7280)
val SurfaceGray = Color(0xFFF9FAFB)
val BorderGray = Color(0xFFE5E7EB)

private val LightColors = lightColorScheme(
    primary = NoktaPurple,
    onPrimary = Color.White,
    primaryContainer = NoktaPurpleLight,
    onPrimaryContainer = NoktaPurpleDark,
    secondary = MoneyGreen,
    onSecondary = Color.White,
    error = AlertRed,
    onError = Color.White,
    errorContainer = AlertRedLight,
    onErrorContainer = AlertRed,
    background = Color.White,
    onBackground = InkBlack,
    surface = Color.White,
    onSurface = InkBlack,
    surfaceVariant = SurfaceGray,
    onSurfaceVariant = InkGray,
    outline = BorderGray,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF2E1065),
    primaryContainer = NoktaPurpleDark,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF34D399),
    error = Color(0xFFF87171),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF232833),
    onSurfaceVariant = Color(0xFF9CA3AF),
)

/** Tipografia com escala maior que o padrão — leitura a meio metro, em pé. */
private val PosTypography = Typography(
    displaySmall = Typography().displaySmall.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp),
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 27.sp),
    headlineSmall = Typography().headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 23.sp),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 17.sp),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 15.sp),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
)

private val PosShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun NoktaPosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PosTypography,
        shapes = PosShapes,
        content = content,
    )
}
