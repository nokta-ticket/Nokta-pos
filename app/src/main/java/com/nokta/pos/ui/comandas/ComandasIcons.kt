package com.nokta.pos.ui.comandas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Ícone de pulseira (anel com etiqueta). Trocar pelo asset da marca quando existir. */
internal val WristbandIcon: ImageVector by lazy {
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
            moveTo(3f, 12f)
            curveTo(3f, 9.2f, 7f, 7f, 12f, 7f)
            curveTo(17f, 7f, 21f, 9.2f, 21f, 12f)
            curveTo(21f, 14.8f, 17f, 17f, 12f, 17f)
            curveTo(7f, 17f, 3f, 14.8f, 3f, 12f)
            close()
        }
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(9.5f, 13.4f)
            lineTo(14.5f, 13.4f)
            lineTo(14.5f, 16.2f)
            lineTo(9.5f, 16.2f)
            close()
        }
    }.build()
}

internal val ChevronRightThin: ImageVector by lazy {
    ImageVector.Builder(
        name = "ChevronRightThin",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            strokeLineMiter = 10f,
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(9f, 5.5f)
            lineTo(15.5f, 12f)
            lineTo(9f, 18.5f)
        }
    }.build()
}
