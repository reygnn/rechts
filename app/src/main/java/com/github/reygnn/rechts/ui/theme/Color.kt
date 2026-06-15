package com.github.reygnn.rechts.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────
// Static teal brand scheme, aligned with the launcher icon background
// (#006A6A). Fully populated per MATERIAL3RULES A4 — every role is set in
// both light and dark so no role falls back to an M3 purple default.
// ─────────────────────────────────────────────────────────────────────────

// Light
val md_light_primary = Color(0xFF006A6A)
val md_light_onPrimary = Color(0xFFFFFFFF)
val md_light_primaryContainer = Color(0xFF71F7F7)
val md_light_onPrimaryContainer = Color(0xFF002020)
val md_light_inversePrimary = Color(0xFF4CDADA)
val md_light_secondary = Color(0xFF4A6363)
val md_light_onSecondary = Color(0xFFFFFFFF)
val md_light_secondaryContainer = Color(0xFFCCE8E8)
val md_light_onSecondaryContainer = Color(0xFF051F1F)
val md_light_tertiary = Color(0xFF4B607C)
val md_light_onTertiary = Color(0xFFFFFFFF)
val md_light_tertiaryContainer = Color(0xFFD3E4FF)
val md_light_onTertiaryContainer = Color(0xFF041C35)
val md_light_error = Color(0xFFBA1A1A)
val md_light_onError = Color(0xFFFFFFFF)
val md_light_errorContainer = Color(0xFFFFDAD6)
val md_light_onErrorContainer = Color(0xFF410002)
val md_light_background = Color(0xFFFAFDFD)
val md_light_onBackground = Color(0xFF191C1C)
val md_light_surface = Color(0xFFFAFDFD)
val md_light_onSurface = Color(0xFF191C1C)
val md_light_surfaceVariant = Color(0xFFDAE5E5)
val md_light_onSurfaceVariant = Color(0xFF3F4949)
val md_light_surfaceTint = md_light_primary
val md_light_surfaceBright = Color(0xFFFAFDFD)
val md_light_surfaceDim = Color(0xFFD7DBDA)
val md_light_surfaceContainerLowest = Color(0xFFFFFFFF)
val md_light_surfaceContainerLow = Color(0xFFF1F4F4)
val md_light_surfaceContainer = Color(0xFFEBEEEE)
val md_light_surfaceContainerHigh = Color(0xFFE6E9E8)
val md_light_surfaceContainerHighest = Color(0xFFE0E3E3)
val md_light_inverseSurface = Color(0xFF2D3131)
val md_light_inverseOnSurface = Color(0xFFEFF1F0)
val md_light_outline = Color(0xFF6F7979)
val md_light_outlineVariant = Color(0xFFBEC9C8)
val md_light_scrim = Color(0xFF000000)

// Dark
val md_dark_primary = Color(0xFF4CDADA)
val md_dark_onPrimary = Color(0xFF003737)
val md_dark_primaryContainer = Color(0xFF005050)
val md_dark_onPrimaryContainer = Color(0xFF71F7F7)
val md_dark_inversePrimary = Color(0xFF006A6A)
val md_dark_secondary = Color(0xFFB1CCCC)
val md_dark_onSecondary = Color(0xFF1C3434)
val md_dark_secondaryContainer = Color(0xFF324B4B)
val md_dark_onSecondaryContainer = Color(0xFFCCE8E8)
val md_dark_tertiary = Color(0xFFA4C8FF)
val md_dark_onTertiary = Color(0xFF1A314B)
val md_dark_tertiaryContainer = Color(0xFF324863)
val md_dark_onTertiaryContainer = Color(0xFFD3E4FF)
val md_dark_error = Color(0xFFFFB4AB)
val md_dark_onError = Color(0xFF690005)
val md_dark_errorContainer = Color(0xFF93000A)
val md_dark_onErrorContainer = Color(0xFFFFDAD6)
val md_dark_background = Color(0xFF191C1C)
val md_dark_onBackground = Color(0xFFE0E3E3)
val md_dark_surface = Color(0xFF191C1C)
val md_dark_onSurface = Color(0xFFE0E3E3)
val md_dark_surfaceVariant = Color(0xFF3F4949)
val md_dark_onSurfaceVariant = Color(0xFFBFC9C9)
val md_dark_surfaceTint = md_dark_primary
val md_dark_surfaceBright = Color(0xFF353A39)
val md_dark_surfaceDim = Color(0xFF101414)
val md_dark_surfaceContainerLowest = Color(0xFF0B0F0F)
val md_dark_surfaceContainerLow = Color(0xFF191C1C)
val md_dark_surfaceContainer = Color(0xFF1D2120)
val md_dark_surfaceContainerHigh = Color(0xFF272B2A)
val md_dark_surfaceContainerHighest = Color(0xFF323535)
val md_dark_inverseSurface = Color(0xFFE0E3E3)
val md_dark_inverseOnSurface = Color(0xFF191C1C)
val md_dark_outline = Color(0xFF899393)
val md_dark_outlineVariant = Color(0xFF3F4949)
val md_dark_scrim = Color(0xFF000000)

// ─────────────────────────────────────────────────────────────────────────
// Private-mode chrome tint. Derived FROM the teal surfaceTint, not picked: take
// the teal, keep its HSL saturation and lightness, rotate the hue from ~180°
// (cyan) to ~305° (a warm violet-magenta on the opposite side of the wheel).
// So the private bar reads at the same intensity as the normal "dark green"
// bar — only the hue changes — and lands on a warm purple rather than a cool
// lavender. Tints the top chrome / status bar only; the lock icon and every
// other accent stay teal. One per mode, mirroring how surfaceTint flips
// light↔dark (each keeps its own teal's S/L).
// ─────────────────────────────────────────────────────────────────────────
val md_light_privateTint = Color(0xFF6A0061) // #006A6A hue 180°→305° (S/L kept)
val md_dark_privateTint = Color(0xFFDA4CCE)  // #4CDADA hue 180°→305° (S/L kept)
