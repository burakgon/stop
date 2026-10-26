package io.github.burakgon.stop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.burakgon.stop.graph

/** The launcher icon of [packageName], loaded off the main thread. */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val placeholder = @Composable {
        Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest))
    }
    if (LocalInspectionMode.current) {
        placeholder()
        return
    }
    val icons = LocalContext.current.graph.icons
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState(icons.cached(packageName, px), packageName, px) {
        value = icons.load(packageName, px)
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    if (image == null) placeholder() else Image(image, contentDescription = null, modifier = modifier.size(size))
}
