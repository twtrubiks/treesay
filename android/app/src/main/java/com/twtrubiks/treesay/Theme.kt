package com.twtrubiks.treesay

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 網頁版 frontend/src/assets/main.css 的色票（oklch 換算成 sRGB）
val BgApp = Color(0xFFFBF4E8)
val BgCard = Color(0xFFFFFBF6)
val BgBubbleUser = Color(0xFFFCD4C3)
val BgBubbleTree = Color(0xFFFBF0E0)
val Border = Color(0xFFDFD6CB)
val Ink = Color(0xFF3C291E)
val InkSoft = Color(0xFF7B6B61)
val InkMute = Color(0xFFA2968C)
val Accent = Color(0xFFCE7951)
val AccentDeep = Color(0xFFAD5B33)
val AccentContrast = Color(0xFFFEF7F2)
val BgSide = Color(0xFFF3EADD)
val BorderSoft = Color(0xFFE4DDD3)
val InkStrong = Color(0xFF372419)
val InkFaint = Color(0xFFC4BDB3)
val AccentSerif = Color(0xFF744D3C)
val Trunk = Color(0xFF634632)
// 樹的頭像、種樹畫面的那棵大樹
val TreeAvatar = Color(0xFFF0A08C)
val ComposerFocus = Color(0xFFDDB499)

private val TreeSayColors = lightColorScheme(
    primary = Accent,
    onPrimary = AccentContrast,
    secondaryContainer = BgBubbleUser,
    onSecondaryContainer = Ink,
    background = BgApp,
    onBackground = Ink,
    surface = BgApp,
    onSurface = Ink,
    onSurfaceVariant = InkSoft,
    surfaceContainerHigh = BgCard,
    outline = Border,
    outlineVariant = Border,
    error = AccentDeep,
)

@Composable
fun TreeSayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TreeSayColors, content = content)
}
