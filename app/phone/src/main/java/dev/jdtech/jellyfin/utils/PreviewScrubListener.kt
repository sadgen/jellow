package dev.jdtech.jellyfin.utils

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.media3.common.Player
import androidx.media3.ui.TimeBar
import coil3.load
import coil3.request.crossfade
import coil3.request.transformations
import coil3.transform.RoundedCornersTransformation
import dev.jdtech.jellyfin.player.core.domain.models.Trickplay
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import android.content.res.Resources

// 扩展函数，将dp转换为px
private val Float.dp: Float
    get() = this * Resources.getSystem().displayMetrics.density

private val Int.dp: Int
    get() = (this * Resources.getSystem().displayMetrics.density).toInt()

class PreviewScrubListener(
    private val scrubbingPreview: ImageView,
    private val timeBarView: View,
    private val player: Player,
) : TimeBar.OnScrubListener {
    var currentTrickplay: Trickplay? = null
    private val roundedCorners = RoundedCornersTransformation(10f)
    private var currentBitMap: Bitmap? = null

    override fun onScrubStart(timeBar: TimeBar, position: Long) {
        Timber.d("Scrubbing started at $position")

        if (currentTrickplay == null) {
            return
        }

        scrubbingPreview.visibility = View.VISIBLE
        onScrubMove(timeBar, position)
    }

    override fun onScrubMove(timeBar: TimeBar, position: Long) {
        Timber.d("Scrubbing to $position")

        try {
            val trickplay = currentTrickplay ?: return
            val image = trickplay.images[position.div(trickplay.interval).toInt()]

            val parent = scrubbingPreview.parent as ViewGroup

            val offset = position.toFloat() / player.duration
            val minX = 0
            val maxX = parent.width

            // 根据图片原始比例调整ImageView尺寸，放大图片
            val bitmapWidth = image.width
            val bitmapHeight = image.height
            val aspectRatio = bitmapWidth.toFloat() / bitmapHeight.toFloat()
            
            // 设置更大的最大宽度和高度，放大trick play图片
            val maxWidth = 280.dp.toFloat()  // 从160dp增加到280dp
            val maxHeight = 160.dp.toFloat() // 从90dp增加到160dp
            
            // 根据比例计算实际尺寸
            val (targetWidth, targetHeight) = if (aspectRatio > maxWidth / maxHeight) {
                // 宽度受限
                maxWidth to (maxWidth / aspectRatio)
            } else {
                // 高度受限
                (maxHeight * aspectRatio) to maxHeight
            }
            
            // 更新ImageView尺寸
            scrubbingPreview.layoutParams = scrubbingPreview.layoutParams.apply {
                width = targetWidth.toInt()
                height = targetHeight.toInt()
            }

            // 计算trick play图片的水平位置，使其跟随进度条
            val timeBarWidth = timeBarView.right - timeBarView.left
            val timeBarLeft = timeBarView.left
            val startX = timeBarLeft + timeBarWidth * offset - targetWidth / 2
            val endX = startX + targetWidth

            val layoutX = when {
                startX >= minX && endX <= maxX -> startX
                startX < minX -> minX
                else -> maxX - targetWidth
            }.toFloat()

            // 设置trick play图片的位置，在进度条上方
            scrubbingPreview.x = layoutX
            scrubbingPreview.y = timeBarView.top - targetHeight - 20.dp  // 在进度条上方20dp处

            if (currentBitMap != image) {
                scrubbingPreview.load(image) {
                    coroutineContext(Dispatchers.Main.immediate)
                    crossfade(false)
                    transformations(roundedCorners)
                }
                currentBitMap = image
            }
        } catch (e: Exception) {
            scrubbingPreview.visibility = View.GONE
            Timber.e(e)
        }
    }

    override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
        Timber.d("Scrubbing stopped at $position")

        scrubbingPreview.visibility = View.GONE
    }
}
