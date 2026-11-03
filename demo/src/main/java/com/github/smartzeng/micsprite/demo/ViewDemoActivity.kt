package com.github.smartzeng.micsprite.demo

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel
import com.github.smartzeng.micsprite.core.MicSpriteSeat
import com.github.smartzeng.micsprite.view.MicSpriteSurfaceView

class ViewDemoActivity : AppCompatActivity() {

    private lateinit var spriteView: MicSpriteSurfaceView
    private lateinit var svgaActions: View
    private val seats = mutableListOf<View>()
    private var format = DemoFormat.SVGA
    private val svgaHeadwears = mutableMapOf<Int, String>()

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { publishSeats() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val seatSize = (48 * density).toInt()
        val gap = (28 * density).toInt()
        val pad = (16 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(pad, pad, pad, pad)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(pad, pad + status.top, pad, pad)
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = "View 麦位头饰"
                textSize = 18f
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        header.addView(
            Button(this).apply {
                text = "Compose 示例"
                setOnClickListener { finish() }
            },
        )
        root.addView(header)

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        DemoFormat.entries.forEach { item ->
            chips.addView(
                Button(this).apply {
                    text = item.label
                    setOnClickListener { selectFormat(item) }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = (8 * density).toInt() }
                },
            )
        }
        root.addView(chips)

        svgaActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * density).toInt(), 0, (12 * density).toInt())
            addView(
                Button(this@ViewDemoActivity).apply {
                    text = "挂满头饰"
                    setOnClickListener { fillAllHeadwears() }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = (8 * density).toInt() }
                },
            )
            addView(
                Button(this@ViewDemoActivity).apply {
                    text = "随机加头饰"
                    setOnClickListener { addRandomHeadwear() }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = (8 * density).toInt() }
                },
            )
            addView(
                Button(this@ViewDemoActivity).apply {
                    text = "随机摘头饰"
                    setOnClickListener { removeRandomHeadwear() }
                },
            )
        }
        root.addView(svgaActions)

        val stage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val halfGap = gap / 2
        val seatGrid = GridLayout(this).apply {
            columnCount = DemoAnchors.SEAT_COLUMNS
            rowCount = (DemoAnchors.AVATARS.size + DemoAnchors.SEAT_COLUMNS - 1) / DemoAnchors.SEAT_COLUMNS
        }
        spriteView = MicSpriteSurfaceView(this)
        DemoAnchors.AVATARS.forEachIndexed { index, avatar ->
            val seat = ShapeableImageView(this).apply {
                tag = index
                scaleType = ImageView.ScaleType.CENTER_CROP
                shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCornerSizes(RelativeCornerSize(0.5f))
                    .build()
                clipToOutline = true
                setBackgroundColor(0xFFE8DEF8.toInt())
                load(avatar)
            }
            val params = GridLayout.LayoutParams().apply {
                width = seatSize
                height = seatSize
                setMargins(halfGap, halfGap, halfGap, halfGap)
            }
            seats += seat
            seatGrid.addView(seat, params)
        }
        stage.addView(
            seatGrid,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        stage.addView(
            spriteView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(stage)
        setContentView(root)

        publishHeadwears()
        spriteView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    override fun onDestroy() {
        spriteView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        spriteView.release()
        super.onDestroy()
    }

    private fun selectFormat(next: DemoFormat) {
        format = next
        svgaActions.visibility = if (next == DemoFormat.SVGA) View.VISIBLE else View.GONE
        publishHeadwears()
    }

    private fun fillAllHeadwears() {
        if (format != DemoFormat.SVGA) return
        svgaHeadwears.clear()
        svgaHeadwears.putAll(DemoAnchors.randomFilledHeadwears(DemoAnchors.AVATARS.size))
        spriteView.setHeadwears(svgaHeadwears)
    }

    private fun addRandomHeadwear() {
        if (format != DemoFormat.SVGA) return
        val empty = DemoAnchors.AVATARS.indices.filter { it !in svgaHeadwears }
        if (empty.isEmpty()) return
        val micId = empty.random()
        val url = DemoAnchors.randomHeadwearUrl()
        svgaHeadwears[micId] = url
        spriteView.setHeadwear(micId, url)
    }

    private fun removeRandomHeadwear() {
        if (format != DemoFormat.SVGA) return
        val micId = svgaHeadwears.keys.randomOrNull() ?: return
        svgaHeadwears.remove(micId)
        spriteView.removeHeadwear(micId)
    }

    private fun publishHeadwears() {
        if (format == DemoFormat.SVGA) {
            spriteView.setHeadwears(svgaHeadwears)
            return
        }
        spriteView.setHeadwears(
            DemoAnchors.AVATARS.indices.associateWith { format.url },
        )
    }

    private fun publishSeats() {
        if (spriteView.width <= 0 || spriteView.height <= 0) return
        val parentLoc = IntArray(2)
        spriteView.getLocationOnScreen(parentLoc)
        spriteView.setSeats(
            seats.mapIndexed { index, view ->
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                val left = (loc[0] - parentLoc[0]).toFloat()
                val top = (loc[1] - parentLoc[1]).toFloat()
                MicSpriteSeat(
                    micId = index,
                    left = left,
                    top = top,
                    right = left + view.width,
                    bottom = top + view.height,
                )
            },
        )
    }
}
