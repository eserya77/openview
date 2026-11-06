package com.example.openview.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Esquemas completos (claro y oscuro) con la regla 60-30-10: fondo dominante, contenedores de la misma familia y un
// solo acento turquesa. Se definen TODOS los contenedores: si faltan, Material usa sus morados por defecto.
// Esquema claro de alto contraste con turquesa fuerte como color principal.
// No usamos color dinámico para garantizar contraste consistente (WCAG).
private val LightColorScheme = lightColorScheme(
    primary = TurquoisePrimary,
    onPrimary = NeutralWhite,
    primaryContainer = Turquoise100,
    onPrimaryContainer = Turquoise900,
    secondary = DeepTeal,
    onSecondary = NeutralWhite,
    secondaryContainer = Turquoise50,
    onSecondaryContainer = Turquoise900,
    tertiary = Green700,
    onTertiary = NeutralWhite,
    tertiaryContainer = Turquoise100,
    onTertiaryContainer = Turquoise900,
    background = SurfaceLight,
    onBackground = NeutralInk,
    surface = NeutralWhite,
    onSurface = NeutralInk,
    surfaceVariant = Turquoise50,
    onSurfaceVariant = NeutralGrey,
    outline = Turquoise800,
    outlineVariant = Turquoise100,
    error = Red700,
    onError = NeutralWhite,
    errorContainer = Red100,
    onErrorContainer = Red700,
)

private val DarkColorScheme = darkColorScheme(
    primary = Turquoise200Dark,
    onPrimary = NeutralInk,
    primaryContainer = Turquoise800,
    onPrimaryContainer = NeutralWhite,
    secondary = Turquoise400,
    onSecondary = NeutralInk,
    secondaryContainer = DarkContainer,
    onSecondaryContainer = NeutralWhite,
    tertiary = SuccessGreenLight,
    onTertiary = NeutralInk,
    tertiaryContainer = DarkContainerHigh,
    onTertiaryContainer = NeutralWhite,
    background = DarkBg,
    onBackground = NeutralWhite,
    surface = DarkSurface,
    onSurface = NeutralWhite,
    surfaceVariant = DarkContainer,
    onSurfaceVariant = DarkOnVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = FailureRedLight,
    onError = NeutralInk,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
)

@Composable
fun OpenviewTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
