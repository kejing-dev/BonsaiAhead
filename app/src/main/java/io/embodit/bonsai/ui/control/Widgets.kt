package io.embodit.bonsai.ui.control

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Switch
import androidx.compose.foundation.selection.toggleable
import com.pico.spatial.ui.design.Text
import io.embodit.bonsai.platform.TAG
import io.embodit.bonsai.platform.BackKeys
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import io.embodit.bonsai.ui.theme.BonsaiColors

/**
 * D60: controller keys never press a window button. Buttons cannot take focus (so ENTER / DPAD_CENTER have nothing to
 * click) and the window eats A / X / Y / ENTER / DPAD_CENTER before they reach anything; BACK / B go to the one handler
 * (copied from circuit-lab v0.1.3).
 */
internal val PAD_KEYS = setOf(Key.ButtonA, Key.ButtonB, Key.ButtonX, Key.ButtonY, Key.Enter, Key.NumPadEnter, Key.DirectionCenter)

internal fun Modifier.eatPadKeys(where: String): Modifier = onPreviewKeyEvent { e ->
    if (BackKeys.route(e.nativeKeyEvent, "compose:$where")) true
    else if (e.key in PAD_KEYS) { Log.i(TAG, "KEY eaten $where ${e.key}"); true } else false
}

internal val NoFocus: Modifier = Modifier.focusProperties { canFocus = false }

/** Text that logs "OVERFLOW <panel>/<control>" whenever it is cut (profile D13; ui-audit runs read the log). */
@Composable
internal fun Txt(
    text: String,
    tag: String,
    style: TextStyle = PicoTheme.typography.bodyLarge,
    color: Color = PicoTheme.colorScheme.labelPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, softWrap = maxLines > 1, textAlign = align,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") },
    )
}

@Composable
internal fun PrimaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, modifier = modifier.then(NoFocus), enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 80.dp, minWidth = 160.dp),
        shape = RoundedCornerShape(40.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
    ) {
        // design-style: inherited-content-color Button
        Txt(text, "$tag/primary", style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimaryLight)
    }
}

@Composable
internal fun SecondaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, tall: Boolean = false) {
    Button(
        onClick = onClick, modifier = modifier.then(NoFocus), enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = if (tall) 64.dp else 56.dp, minWidth = 96.dp),
        shape = RoundedCornerShape(if (tall) 32.dp else 28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) BonsaiColors.Error else PicoTheme.colorScheme.fillSecondary,
            contentColor = if (danger) BonsaiColors.OnError else PicoTheme.colorScheme.labelPrimary,
        ),
    ) {
        // design-style: inherited-content-color Button
        Txt(text, "$tag/button", style = PicoTheme.typography.labelLarge, color = if (danger) BonsaiColors.OnError else PicoTheme.colorScheme.labelPrimary)
    }
}

/** A switch-like button (D19): its on / off state is the fill, and a small lamp on the left. */
@Composable
internal fun ToggleButton(text: String, tag: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val fg = if (on) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, modifier = modifier.then(NoFocus), enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 96.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) BonsaiColors.PrimaryContainer else Color.Transparent).border(1.5.dp, fg, CircleShape))
        Box(Modifier.width(8.dp))
        // design-style: inherited-content-color Button
        Txt(text, "$tag/toggle", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

/** A labelled row holding a same-page option switch (SegmentControl is never used to change pages). */
@Composable
internal fun OptionRow(label: String, tag: String, labelWidth: Dp = 112.dp, rowHeight: Dp = 56.dp, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(labelWidth))
        content()
    }
}

/** A whole >= 48 dp row that toggles (label + Switch); the bare SpatialUI Switch is only ~32 x 20 dp (QA q1-4, D44). */
@Composable
internal fun SwitchRow(label: String, tag: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))
            .then(NoFocus).toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Three-stop volume slider (35 / 65 / 100 %) that takes taps and drags (B20, factory VolumeDrag logic). */
@Composable
internal fun VolumeSlider(level: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val drag = androidx.compose.runtime.remember { VolumeDrag(level, stops = 3) }
    var pos by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(level.toFloat()) }
    val cb = androidx.compose.runtime.rememberUpdatedState(onChange)
    androidx.compose.runtime.LaunchedEffect(level) { drag.sync(level); pos = drag.pos }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = pos,
            onValueChange = { v -> val changed = drag.move(v); pos = drag.pos; if (changed != null) cb.value(changed) },
            onValueChangeFinished = { val changed = drag.finish(); pos = drag.pos; Log.i(TAG, "VOLUME snap ${drag.level}"); if (changed != null) cb.value(changed) },
            modifier = Modifier.weight(1f).height(48.dp),
            valueRange = 0f..2f,
        )
        Box(Modifier.width(12.dp))
        Txt("${io.embodit.bonsai.platform.SoundPlayer.VOLUME_PERCENT[drag.level.coerceIn(0, 2)]}%", "settings/volume-pct", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
    }
}
