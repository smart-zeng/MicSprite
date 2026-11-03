package io.github.smartzeng.micsprite.demo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.smartzeng.micsprite.compose.MicSpriteLayer
import io.github.smartzeng.micsprite.compose.rememberMicSpriteController
import io.github.smartzeng.micsprite.core.MicSpriteSeat

class ComposeDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ComposeDemoScreen(
                        onOpenViewDemo = {
                            startActivity(Intent(this, ViewDemoActivity::class.java))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposeDemoScreen(onOpenViewDemo: () -> Unit) {
    val controller = rememberMicSpriteController()
    var format by remember { mutableStateOf(DemoFormat.SVGA) }
    var layerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val seatCoords = remember {
        mutableStateListOf<LayoutCoordinates?>().apply {
            repeat(DemoAnchors.AVATARS.size) { add(null) }
        }
    }
    val svgaHeadwears = remember { mutableStateMapOf<Int, String>() }

    fun reportSeats(layer: LayoutCoordinates? = layerCoords) {
        if (layer == null || !layer.isAttached) return
        val seats = seatCoords.mapIndexedNotNull { index, seat ->
            if (seat == null || !seat.isAttached) return@mapIndexedNotNull null
            val topLeft = layer.windowToLocal(seat.localToWindow(Offset.Zero))
            val bottomRight = layer.windowToLocal(
                seat.localToWindow(Offset(seat.size.width.toFloat(), seat.size.height.toFloat())),
            )
            MicSpriteSeat(
                micId = index,
                left = topLeft.x,
                top = topLeft.y,
                right = bottomRight.x,
                bottom = bottomRight.y,
            )
        }
        if (seats.isNotEmpty()) controller.setSeats(seats)
    }

    fun fillAllHeadwears() {
        if (format != DemoFormat.SVGA) return
        svgaHeadwears.clear()
        svgaHeadwears.putAll(DemoAnchors.randomFilledHeadwears(DemoAnchors.AVATARS.size))
        controller.setHeadwears(svgaHeadwears.toMap())
    }

    fun addRandomHeadwear() {
        if (format != DemoFormat.SVGA) return
        val empty = DemoAnchors.AVATARS.indices.filter { it !in svgaHeadwears }
        if (empty.isEmpty()) return
        val micId = empty.random()
        val url = DemoAnchors.randomHeadwearUrl()
        svgaHeadwears[micId] = url
        controller.setHeadwear(micId, url)
    }

    fun removeRandomHeadwear() {
        if (format != DemoFormat.SVGA) return
        val micId = svgaHeadwears.keys.randomOrNull() ?: return
        svgaHeadwears.remove(micId)
        controller.removeHeadwear(micId)
    }

    LaunchedEffect(format) {
        if (format == DemoFormat.SVGA) {
            controller.setHeadwears(svgaHeadwears.toMap())
        } else {
            controller.setHeadwears(
                DemoAnchors.AVATARS.indices.associateWith { format.url },
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Compose 麦位头饰", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onOpenViewDemo) {
                Text("View 示例")
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DemoFormat.entries.forEach { item ->
                FilterChip(
                    selected = format == item,
                    onClick = { format = item },
                    label = { Text(item.label) },
                )
            }
        }
        if (format == DemoFormat.SVGA) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(onClick = ::fillAllHeadwears) {
                    Text("挂满头饰")
                }
                Button(onClick = ::addRandomHeadwear) {
                    Text("加头饰")
                }
                Button(onClick = ::removeRandomHeadwear) {
                    Text("摘头饰")
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned {
                    layerCoords = it
                    reportSeats(it)
                },
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DemoAnchors.AVATARS.chunked(DemoAnchors.SEAT_COLUMNS).forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        row.forEachIndexed { colIndex, avatar ->
                            val index = rowIndex * DemoAnchors.SEAT_COLUMNS + colIndex
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .onGloballyPositioned {
                                        seatCoords[index] = it
                                        reportSeats()
                                    },
                            ) {
                                AsyncImage(
                                    model = avatar,
                                    contentDescription = "mic $index",
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
            MicSpriteLayer(
                controller = controller,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
