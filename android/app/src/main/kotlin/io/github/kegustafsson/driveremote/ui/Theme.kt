package io.github.kegustafsson.driveremote.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The same palette as the browser UI (`sk-plugin/src/index.css`), so all the
 * command stations read as one system.
 *
 * Deliberately a single high-contrast dark theme rather than following the
 * system light/dark setting: this is a helm display used in bright sun and at
 * night, and the colours carry meaning (forward/reverse, good/warn/bad) that a
 * light variant would have to re-derive. [isSystemInDarkTheme] is therefore not
 * consulted at all -- that is a decision, not an omission.
 */
object DriveColors {
  val surface = Color(0xFF0B0B0B)
  val surfaceRaised = Color(0xFF1A1A19)
  val ink = Color(0xFFFFFFFF)
  val inkMuted = Color(0xFFA9A89F)
  val border = Color(0x24FFFFFF)

  val forward = Color(0xFF2A78D6)
  val reverse = Color(0xFFEB6834)
  val neutral = Color(0xFF6B6A63)

  val armed = Color(0xFFD03B3B)
  val disarmed = Color(0xFF383835)

  val good = Color(0xFF0CA30C)
  val warn = Color(0xFFFAB219)
  val bad = Color(0xFFD03B3B)
}

private val scheme =
  darkColorScheme(
    primary = DriveColors.forward,
    onPrimary = DriveColors.ink,
    background = DriveColors.surface,
    onBackground = DriveColors.ink,
    surface = DriveColors.surfaceRaised,
    onSurface = DriveColors.ink,
    error = DriveColors.bad,
  )

@Composable
fun DriveRemoteTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = scheme, content = content)
}
