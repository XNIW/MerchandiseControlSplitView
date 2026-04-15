package com.example.merchandisecontrolsplitview.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
