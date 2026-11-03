package io.github.smartzeng.micsprite.compose

import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.smartzeng.micsprite.core.MicSpriteController
import kotlinx.coroutines.awaitCancellation

@Composable
fun rememberMicSpriteController(): MicSpriteController {
    val context = LocalContext.current
    val controller = remember { MicSpriteController(context.applicationContext) }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}

@Composable
fun MicSpriteLayer(
    controller: MicSpriteController,
    modifier: Modifier = Modifier,
) {
    AndroidEmbeddedExternalSurface(
        modifier = modifier.fillMaxSize(),
        isOpaque = false,
    ) {
        onSurface { surface, width, height ->
            controller.attachSurface(surface, width, height)
            surface.onChanged { w, h -> controller.resize(w, h) }
            surface.onDestroyed { controller.detachSurface() }
            try {
                awaitCancellation()
            } finally {
                controller.detachSurface()
            }
        }
    }
}
