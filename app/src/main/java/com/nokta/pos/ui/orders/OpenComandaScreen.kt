package com.nokta.pos.ui.orders

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nokta.pos.R
import com.nokta.pos.ui.theme.NoktaInk
import com.nokta.pos.ui.theme.NoktaInkSoft
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
    val IllustrationSize = 200.dp
    val FieldHeight = 68.dp
    val ButtonHeight = 62.dp
}

private val PageBg = Color(0xFFFAFAFC)
private val SegmentBorder = Color(0xFFEAE8F0)
private val SelectedTint = Color(0xFFF8F4FE)

/* =========================================================================
 *  MODELO
 * ========================================================================= */

enum class ComandaType(val label: String) {
    WRISTBAND("Pulseira"),
    CARD("Cartão físico"),
}

/* =========================================================================
 *  TELA
 * ========================================================================= */

@Composable
fun OpenComandaScreen(
    selectedType: ComandaType,
    code: String,
    onSelectType: (ComandaType) -> Unit = {},
    onCodeChange: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
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
                    text = "Abrir comanda",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    color = NoktaInk
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Escolha o tipo de comanda e informe os dados.",
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
            ComandaType.entries.forEach { type ->
                TypeSegment(
                    type = type,
                    selected = type == selectedType,
                    onClick = { onSelectType(type) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // ---------- Ilustração ----------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dim.IllustrationSize),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(
                    id = when (selectedType) {
                        ComandaType.WRISTBAND -> R.drawable.il_pulseira
                        ComandaType.CARD -> R.drawable.il_cartao_fisico
                    }
                ),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(Dim.IllustrationSize)
            )
        }

        Spacer(Modifier.height(18.dp))

        // ---------- Instrução ----------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dim.ScreenPad),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, NoktaPurpleBright, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "i",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = NoktaPurpleBright
                )
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = when (selectedType) {
                    ComandaType.WRISTBAND -> "Informe o número da pulseira"
                    ComandaType.CARD -> "Informe o número do cartão"
                },
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                color = NoktaInk,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = when (selectedType) {
                    ComandaType.WRISTBAND ->
                        "Digite o número impresso na pulseira\npara abrir a comanda."
                    ComandaType.CARD ->
                        "Digite o número impresso no cartão\nda comanda para abri-la."
                },
                fontSize = 14.5.sp,
                lineHeight = 22.sp,
                color = NoktaMuted,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(22.dp))

            // ---------- Campo ----------
            CodeField(
                value = code,
                onValueChange = onCodeChange,
                label = when (selectedType) {
                    ComandaType.WRISTBAND -> "Número da pulseira"
                    ComandaType.CARD -> "Número do cartão"
                },
                leadingIcon = when (selectedType) {
                    ComandaType.WRISTBAND -> WristbandIcon
                    ComandaType.CARD -> Icons.Outlined.CreditCard
                }
            )

            Spacer(Modifier.height(22.dp))

            // ---------- Continuar ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dim.ButtonHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (code.isNotBlank()) NoktaPurple else NoktaPurple.copy(alpha = 0.45f))
                    .clickable(enabled = code.isNotBlank(), onClick = onContinue),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .border(1.8.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "Continuar",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.2).sp,
                    color = Color.White
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/* --------------------------- Seletor de tipo ------------------------ */

@Composable
private fun TypeSegment(
    type: ComandaType,
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
            imageVector = when (type) {
                ComandaType.WRISTBAND -> WristbandIcon
                ComandaType.CARD -> Icons.Outlined.CreditCard
            },
            contentDescription = null,
            tint = if (selected) NoktaPurpleBright else NoktaInk,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = type.label,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) NoktaPurpleBright else NoktaInkSoft,
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
    leadingIcon: ImageVector,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isLetterOrDigit() }) },
        modifier = Modifier
            .fillMaxWidth()
            .height(Dim.FieldHeight),
        label = {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        leadingIcon = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = NoktaPurpleBright,
                modifier = Modifier.size(22.dp)
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFDDDBE4))
                        .clickable { onValueChange("") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Limpar",
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        },
        singleLine = true,
        textStyle = TextStyle(fontSize = 18.sp, color = NoktaInk),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NoktaPurpleBright,
            unfocusedBorderColor = NoktaPurpleBright,
            focusedLabelColor = NoktaPurpleBright,
            unfocusedLabelColor = NoktaPurpleBright,
            cursorColor = NoktaPurple,
            focusedContainerColor = NoktaSurface,
            unfocusedContainerColor = NoktaSurface,
        )
    )
}

/** Ícone de pulseira (anel). Trocar pelo asset da marca quando existir. */
private val WristbandIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "WristbandIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            pathFillType = PathFillType.NonZero
        ) {
            // anel externo
            moveTo(3f, 12f)
            curveTo(3f, 9.2f, 7f, 7f, 12f, 7f)
            curveTo(17f, 7f, 21f, 9.2f, 21f, 12f)
            curveTo(21f, 14.8f, 17f, 17f, 12f, 17f)
            curveTo(7f, 17f, 3f, 14.8f, 3f, 12f)
            close()
        }
        path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.NonZero
        ) {
            // etiqueta
            moveTo(9.5f, 13.6f)
            lineTo(14.5f, 13.6f)
            lineTo(14.5f, 16.4f)
            lineTo(9.5f, 16.4f)
            close()
        }
    }.build()
}
