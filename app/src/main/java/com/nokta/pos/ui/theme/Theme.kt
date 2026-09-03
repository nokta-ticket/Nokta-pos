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
 * Paleta oficial de marca Nokta POS (2026-09-03) — PRETO + AZUL ELÉTRICO
 * como identidade central. Azul é a cor de assinatura; cyan é só para
 * realce/glow/gradiente, nunca preenchimento sólido de área grande. Nunca
 * magenta/rosa (regra explícita de marca — havia um gradiente roxo→magenta
 * no rodapé institucional antes desta paleta, removido).
 *
 *  - Electric Blue #0066FF: primária, ação principal.
 *  - Deep Blue #0038B8: tom mais escuro da escala azul (gradientes, hover).
 *  - Cyan Blue #00B7FF: destaque/glow pontual, nunca área grande.
 *  - Navy Black #080B12: "preto" da marca — usado em título/texto de alto
 *    contraste em vez de #000 puro.
 *  - Ice #F4F7FB: fundo claro. Slate #667085: texto secundário.
 *
 * Os nomes de variável (NoktaPurple*, NoktaAccentBlue) foram MANTIDOS —
 * só o valor hex mudou — porque ~10 telas já referenciam esses símbolos
 * diretamente; renomear tudo teria sido troca de escopo maior que o pedido
 * (trocar cor, não redesenhar cada tela). Não introduzir cor de marca nova
 * sem aprovação explícita (regra do brief).
 */

// ── Marca ──
// Mantido o nome "Purple*" por compatibilidade com ~10 telas existentes —
// os VALORES agora são a escala azul oficial, não roxo.
val NoktaPurpleDarker = Color(0xFF002466)     // Deep Blue escurecido, ponta do gradiente
val NoktaPurpleDeep = Color(0xFF0038B8)       // Deep Blue
val NoktaPurple = Color(0xFF0066FF)           // Electric Blue — primária
val NoktaPurpleBright = Color(0xFF0066FF)     // Electric Blue — ação principal (mesmo tom, sem variante mais clara pedida)
val NoktaPurpleSoft = Color(0xFFE6F0FF)       // fundo de realce muito suave, tom de Electric Blue
val NoktaLavender = Color(0xFFE6F0FF)         // superfície de apoio, mesmo tom suave
val NoktaAccentBlue = Color(0xFF00B7FF)       // Cyan Blue — realce/glow, nunca preenchimento

// ── Neutros ──
val InkBlack = Color(0xFF080B12)          // Navy Black — títulos
val InkSoft = Color(0xFF1A2130)           // rótulos secundários fortes (entre Navy Black e Slate)
val InkMuted = Color(0xFF667085)          // Slate — subtítulos
val InkFaint = Color(0xFF98A2B3)          // textos auxiliares (Slate clareado)
val SurfaceOffWhite = Color(0xFFF4F7FB)   // Ice — fundo da tela
val BorderSubtle = Color(0xFFE6EAF0)      // borda dos cards
val BorderStrong = Color(0xFFD8DEE8)      // borda de pill / botão

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
    primary = Color(0xFF3399FF),          // Electric Blue clareado p/ contraste em fundo escuro
    onPrimary = Color(0xFF001433),
    primaryContainer = Color(0xFF002466), // Deep Blue escurecido
    onPrimaryContainer = Color(0xFFCCE4FF),
    secondary = Color(0xFF34D399),
    error = Color(0xFFF87171),
    background = Color(0xFF080B12),       // Navy Black
    onBackground = Color(0xFFF4F7FB),     // Ice
    surface = Color(0xFF121722),
    onSurface = Color(0xFFF4F7FB),
    surfaceVariant = Color(0xFF1C2433),
    onSurfaceVariant = Color(0xFF98A2B3), // Slate clareado
    outline = Color(0xFF2A3346),
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
