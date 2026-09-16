package com.example.smartroaddamage.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.smartroaddamage.ui.theme.NeoBrutalTokens
import com.example.smartroaddamage.ui.theme.neoBrutalSurface

/**
 * PlasticBentoCard — Now backed by [neoBrutalSurface].
 *
 * Flat Slate face with 2px black border and hard offset shadow.
 * No glass, no gradient borders — pure "dead metal" aesthetic.
 *
 * @param backgroundColor  Card face color (default CardSlate)
 * @param borderColor      2px border color — use semantic tokens for state
 * @param cornerRadius     12dp standard, 8dp compact
 * @param contentPadding   Internal padding for child content
 */
@Composable
fun PlasticBentoCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = NeoBrutalTokens.CardSlate,
    borderColor: Color = NeoBrutalTokens.HardBorder,
    cornerRadius: Dp = 12.dp,
    shadowOffset: Dp = 4.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .neoBrutalSurface(
                backgroundColor = backgroundColor,
                borderColor     = borderColor,
                cornerRadius    = cornerRadius,
                shadowOffset    = shadowOffset
            )
            .padding(contentPadding),
        content = content
    )
}
