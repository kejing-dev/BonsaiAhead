package io.embodit.bonsai.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.bonsai.domain.Language
import io.embodit.bonsai.platform.Graph
import java.util.Locale

/** design palette: moss green (brand / main button), terracotta (icon, done accent), sand white; signals red / yellow / teal. */
object BonsaiColors {
    val Primary = Color(0xFF5E7D4A)
    val PrimaryDeep = Color(0xFF45602F)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFD9E8C9)
    val OnPrimaryContainer = Color(0xFF1C2E10)
    val Terra = Color(0xFFA5573A)
    val TerraDeep = Color(0xFF7E3F27)
    val Sand = Color(0xFFEDE6D8)
    val CardFace = Color(0xFFF6F1E6)
    val Error = Color(0xFFB3261E)
    val OnError = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1B1F1A)
    /** Secondary / small text on the window glass (>= 4.5:1 on the darkest measured glass #B4B3B1). */
    val InkSecondary = Color(0xFF363C33)
    val ErrorText = Color(0xFF7A1A18)
    val CutRed = Color(0xFFE5534B)
    val RingYellow = Color(0xFFF2C14E)
    val OutlineTeal = Color(0xFF9FD3D6)
    val Weak = Color(0xFFC9C27A)
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). [language] localises resources below this point
 * through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun BonsaiTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = BonsaiColors.OnPrimary,
        labelPrimary = BonsaiColors.Ink,
        labelSecondary = BonsaiColors.InkSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = BonsaiColors.Error,
        alert = BonsaiColors.Terra,
        passable = BonsaiColors.Primary,
        interaction = BonsaiColors.PrimaryDeep,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
