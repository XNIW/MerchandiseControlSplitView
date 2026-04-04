package com.example.merchandisecontrolsplitview.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Immutable
data class AppColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val filledContainer: Color,
    val onFilledContainer: Color,
    val gridAliasBackground: Color,
    val gridPatternBackground: Color
)

@Immutable
data class AppSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 6.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp
)

internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

internal val AppSpacingDefaults = AppSpacing()

private val LightAppColors = AppColors(
    success = Success,
    successContainer = SuccessContainerLight,
    onSuccessContainer = OnCustomContainerLight,
    warning = Warning,
    filledContainer = FilledContainerLight,
    onFilledContainer = OnCustomContainerLight,
    gridAliasBackground = GridAliasBackgroundLight,
    gridPatternBackground = GridPatternBackgroundLight
)

private val DarkAppColors = AppColors(
    success = Success,
    successContainer = SuccessContainerDark,
    onSuccessContainer = OnCustomContainerDark,
    warning = Warning,
    filledContainer = FilledContainerDark,
    onFilledContainer = OnCustomContainerDark,
    gridAliasBackground = GridAliasBackgroundDark,
    gridPatternBackground = GridPatternBackgroundDark
)

internal fun appColors(darkTheme: Boolean): AppColors = if (darkTheme) DarkAppColors else LightAppColors

internal val LocalAppColors = staticCompositionLocalOf { LightAppColors }
internal val LocalAppSpacing = staticCompositionLocalOf { AppSpacingDefaults }

val MaterialTheme.appColors: AppColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppColors.current

val MaterialTheme.appSpacing: AppSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalAppSpacing.current

@Composable
fun MerchandiseControlSplitViewTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(
        LocalAppColors provides appColors(darkTheme),
        LocalAppSpacing provides AppSpacingDefaults
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content
        )
    }
}
