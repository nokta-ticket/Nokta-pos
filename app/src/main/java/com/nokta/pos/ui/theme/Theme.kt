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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tema do POS.
 *
 * A paleta vem da identidade real da Nokta (os mesmos hex usados no
 * nokta-frontend), não de tons genéricos do Material:
 *
 *  - `#9944CC` é o roxo de marca — o mais usado no produto, presente nos
 *    gradientes de destaque da Nokta Tickets.
 *  - `#1A1626` é o "preto" da marca: um quase-preto arroxeado. Texto em
 *    preto puro (#000) ao lado de um roxo de marca parece descolado do
 *    produto; este tom amarra a tipografia à identidade sem chamar atenção.
 *  - `#ECE6F8` é a lavanda clara usada como superfície de apoio.
 *
 * Roxo aqui é ACENTO, não preenchimento: aparece na ação principal, em
 * estados ativos e em detalhes de marca. O corpo da interface é branco/
 * off-white com texto de alto contraste — é o que faz um POS parecer
 * instrumento de trabalho, e não uma landing page.
 */

// ── Marca ──
// Escala de roxo do escuro ao claro: o gradiente da ação principal percorre
// Darker → Deep → Bright, e o Purple sólido marca ícones e estados ativos.
val NoktaPurpleDarker = Color(0xFF3A1195)
val NoktaPurpleDeep = Color(0xFF4318A8)
val NoktaPurple = Color(0xFF6D28D9)
val NoktaPurpleBright = Color(0xFF7C3AED)
val NoktaPurpleSoft = Color(0xFFF3EAFB)   // fundo de realce muito suave
val NoktaLavender = Color(0xFFECE6F8)     // superfície de apoio
val NoktaAccentBlue = Color(0xFF3399FF)   // segunda cor da marca, uso pontual

// ── Neutros ──
val InkBlack = Color(0xFF1B1533)          // títulos ("preto" arroxeado da marca)
val InkSoft = Color(0xFF2A2440)           // rótulos secundários fortes
val InkMuted = Color(0xFF7C7A8A)          // subtítulos
val InkFaint = Color(0xFF9B99A8)          // textos auxiliares
val SurfaceOffWhite = Color(0xFFF6F5FA)   // fundo da tela
val BorderSubtle = Color(0xFFEFEDF5)      // borda dos cards
val BorderStrong = Color(0xFFE7E4EF)      // borda de pill / botão

// ── Nomes de uso (o que cada cor É na tela) ──
// Aliases dos neutros acima. Existem para que a UI diga "fundo da tela" /
// "borda do card" em vez de repetir o papel do tom em cada arquivo.
val NoktaBackground = SurfaceOffWhite     // fundo da tela
val NoktaSurface = Color(0xFFFFFFFF)      // cards
val NoktaBorder = BorderSubtle            // borda dos cards
val NoktaBorderStrong = BorderStrong      // borda de pill / botão
val NoktaInk = InkBlack                   // títulos
val NoktaInkSoft = InkSoft                // rótulos secundários fortes
val NoktaMuted = InkMuted                 // subtítulos
val NoktaMutedSoft = InkFaint             // textos auxiliares

// ── Semânticas ──
val NoktaOnline = Color(0xFF22C55E)       // indicador de conexão
val MoneyGreen = Color(0xFF0E9F6E)
val MoneyGreenLight = Color(0xFFE3F5EE)
val WarningAmber = Color(0xFFB45309)
val WarningAmberLight = Color(0xFFFDF3E3)
val AlertRed = Color(0xFFD92D20)
val AlertRedLight = Color(0xFFFDECEA)

private val LightColors = lightColorScheme(
    primary = NoktaPurple,
    onPrimary = Color.White,
    primaryContainer = NoktaPurpleSoft,
    onPrimaryContainer = NoktaPurpleDeep,
    secondary = MoneyGreen,
    onSecondary = Color.White,
    error = AlertRed,
    onError = Color.White,
    errorContainer = AlertRedLight,
    onErrorContainer = AlertRed,
    background = SurfaceOffWhite,
    onBackground = InkBlack,
    surface = Color.White,
    onSurface = InkBlack,
    surfaceVariant = NoktaLavender,
    onSurfaceVariant = InkMuted,
    outline = BorderSubtle,
    outlineVariant = BorderSubtle,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC98BE8),
    onPrimary = Color(0xFF2A0F3D),
    primaryContainer = Color(0xFF3D1F52),
    onPrimaryContainer = Color(0xFFEBD6F7),
    secondary = Color(0xFF34D399),
    error = Color(0xFFF87171),
    background = Color(0xFF121016),
    onBackground = Color(0xFFF4F2F7),
    surface = Color(0xFF1B1822),
    onSurface = Color(0xFFF4F2F7),
    surfaceVariant = Color(0xFF272332),
    onSurfaceVariant = Color(0xFFA9A4B8),
    outline = Color(0xFF3A3546),
)

/**
 * Tipografia como ferramenta de hierarquia, não de tamanho.
 *
 * O salto grande fica entre o TÍTULO DA AÇÃO e o resto: é isso que faz o
 * olho pousar em "Nova venda" antes de qualquer outra coisa. Rótulos
 * secundários ficam menores e com `letterSpacing` levemente aberto — o que
 * dá o acabamento de produto comercial sem precisar de mais peso ou cor.
 */
private val base = Typography()
private val PosTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = (-0.8).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.6).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.4).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.1.sp),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium, fontSize = 12.5.sp, letterSpacing = 0.2.sp),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.4.sp),
)

/**
 * Cantos contidos. O padrão do Material (28dp em cards grandes) faz tudo
 * virar pílula e é boa parte da "cara de template" — aqui o raio acompanha
 * o tamanho do elemento em vez de ser sempre generoso.
 */
private val PosShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp),
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
