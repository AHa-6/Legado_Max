package io.legado.app.ui.video

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.model.VideoPlay
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.video.player.VideoEpisodeGroup
import io.legado.app.ui.video.player.VideoEpisodeSheet
import io.legado.app.ui.video.player.VideoSpeedSheet
import io.legado.app.ui.video.player.generateVideoEpisodeGroups

/**
 * 视频页 Compose 面板宿主：承载「选集」与「倍速」两个面板。
 *
 * 为什么不用 [io.legado.app.ui.widget.components.dialog.BaseComposeDialogFragment]：
 * 它的 `onStart()` 会无条件调 `EInkRender.applyRootLayer(...)`，而
 * [VideoPlayerActivity] 本身在 `EInkRender.rootLayerExcludedActivities` 里——视频页
 * 应当保持彩色，走 Fragment 会被套上灰度根层。所以沿用项目内 [BookInfoActivity] /
 * [SearchActivity] 的既有范式：`ComposeView` 直接贴在 Activity 的内容容器上。
 *
 * 与那两个页面的差别只有一点：这里两个面板自己就带 `Dialog` / `ModalBottomSheet`
 * （各自是独立窗口），所以宿主 ComposeView 用 `WRAP_CONTENT` 即可——它画出来的
 * 布局节点是空的，实测 0×0，不会在视频画面上拦触摸。
 *
 * 生命周期：ComposeView 的 `viewCompositionStrategy` 用
 * [ViewCompositionStrategy.DisposeOnDetachedFromWindow]；[release] 里先清状态再
 * `removeView`，兜住 `AccessibilityInteractionController.mTempArrayList` 持有
 * 已销毁 View 造成的泄漏（[BookInfoActivity.onDestroy] 同款处理，注释见那里）。
 */
class VideoPanelHost(private val activity: AppCompatActivity) {

    private var composeView: ComposeView? = null

    private var episode by mutableStateOf<EpisodeRequest?>(null)
    private var speed by mutableStateOf<SpeedRequest?>(null)

    /** 选集面板请求快照。groups 在请求时刻算好，面板内部只管展示与回调。 */
    private class EpisodeRequest(
        val groups: List<VideoEpisodeGroup>,
        val currentVolume: Int,
        val currentEpisode: Int,
        val currentDuration: Long,
        val onSelect: (Int, Int) -> Unit,
        val onDismiss: () -> Unit,
    )

    private class SpeedRequest(
        val currentSpeed: Float,
        val onSelect: (Float) -> Unit,
        val onDismiss: () -> Unit,
    )

    /**
     * 弹出选集面板。
     *
     * [currentVolume] / [currentEpisode] 就是 `VideoPlay.durVolumeIndex` /
     * `VideoPlay.chapterInVolumeIndex`；[onSelect] 的两级下标与之一一对应，
     * 因此面板可以跨卷跳转（旧 `ChoiceEpisodeDialog` 只拿到已切片的
     * `VideoPlay.episodes`，只能在本卷内选）。
     *
     * 目录必须用 `VideoPlay.toc` 全量数据：`VideoPlay.episodes` 已经切成当前卷，
     * 拿它分组会丢掉卷锚点，有分卷的源会退化成单组。
     */
    fun showEpisode(
        currentVolume: Int,
        currentEpisode: Int,
        currentDuration: Long,
        onSelect: (Int, Int) -> Unit,
        onDismiss: () -> Unit,
    ) {
        val toc = VideoPlay.toc
        if (toc.isNullOrEmpty()) {
            //没有目录就没有可选的内容，直接归还播放器的 isChanging
            onDismiss()
            return
        }
        val groups = generateVideoEpisodeGroups(toc, VideoPlay.volumes)
        if (groups.isEmpty()) {
            onDismiss()
            return
        }
        ensureView()
        episode = EpisodeRequest(
            groups = groups,
            currentVolume = currentVolume,
            currentEpisode = currentEpisode,
            currentDuration = currentDuration,
            onSelect = onSelect,
            onDismiss = onDismiss,
        )
    }

    fun showSpeed(
        currentSpeed: Float,
        onSelect: (Float) -> Unit,
        onDismiss: () -> Unit,
    ) {
        ensureView()
        speed = SpeedRequest(currentSpeed, onSelect, onDismiss)
    }

    fun release() {
        episode = null
        speed = null
        composeView?.let { view ->
            try {
                (view.parent as? ViewGroup)?.removeView(view)
            } catch (_: Exception) {
            }
        }
        composeView = null
    }

    private fun dismissEpisode() {
        val request = episode ?: return
        episode = null
        request.onDismiss()
    }

    private fun dismissSpeed() {
        val request = speed ?: return
        speed = null
        request.onDismiss()
    }

    private fun ensureView() {
        val existing = composeView
        if (existing != null && existing.parent != null) {
            return
        }
        //被从窗口树上摘掉过（例如全屏切换重建了内容容器）：旧的 composition 已随
        //DisposeOnDetachedFromWindow 销毁，直接丢掉引用重建一个
        existing?.let { (it.parent as? ViewGroup)?.removeView(it) }
        composeView = null

        val parent = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val view = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                LegadoTheme {
                    episode?.let { request ->
                        VideoEpisodeSheet(
                            groups = request.groups,
                            initialVolume = request.currentVolume,
                            currentVolume = request.currentVolume,
                            currentEpisode = request.currentEpisode,
                            currentDuration = request.currentDuration,
                            onSelect = { volumeIndex, episodeIndex ->
                                request.onSelect(volumeIndex, episodeIndex)
                                dismissEpisode()
                            },
                            onDismiss = { dismissEpisode() },
                        )
                    }
                    speed?.let { request ->
                        VideoSpeedSheet(
                            currentSpeed = request.currentSpeed,
                            //旧 ChoiceSpeedDialog 传的就是降序（3.0×→0.5×），保持一致
                            speeds = SPEED_OPTIONS.reversed(),
                            onSelect = { value ->
                                request.onSelect(value)
                                dismissSpeed()
                            },
                            onDismiss = { dismissSpeed() },
                        )
                    }
                }
            }
        }
        parent.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        composeView = view
    }

    private companion object {
        val SPEED_OPTIONS =
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)
    }
}