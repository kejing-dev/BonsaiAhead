package io.embodit.bonsai

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.bonsai.ui.Containers
import io.embodit.bonsai.ui.control.ControlWindow
import io.embodit.bonsai.ui.scene.VolumeScreen
import io.embodit.bonsai.ui.theme.BonsaiTheme

/**
 * Two surfaces in Shared Space (design windowModel volume_plus_system_panel): the pot volume (default container,
 * 1.0 x 0.9 x 0.78 m from the manifest) and the control window (Planar 520 x 600 dp) placed to its right by the system.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            BonsaiTheme { VolumeScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 520.dp, height = 600.dp),
            // First open and every reopen: beside the pot volume (profile D44: Shared Space has no "bring it in front").
            placement = { containers.firstOrNull { it.id == Containers.POT }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            BonsaiTheme {
                Box(Modifier.windowConstraints(minWidth = 520.dp, minHeight = 600.dp, maxWidth = 520.dp, maxHeight = 600.dp)) { ControlWindow() }
            }
        }
    }
