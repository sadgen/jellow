package dev.jdtech.jellyfin

import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Rational
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.databinding.ActivityPlayerBinding
import dev.jdtech.jellyfin.player.local.presentation.PlayerEvents
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.presentation.player.SpeedSelectionDialogFragment
import dev.jdtech.jellyfin.presentation.player.TrackSelectionDialogFragment
import dev.jdtech.jellyfin.presentation.player.VrModeSelectionDialogFragment
import androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.PlayerGestureHelper
import dev.jdtech.jellyfin.utils.PreviewScrubListener
import java.util.UUID
import javax.inject.Inject
import android.view.GestureDetector
import android.view.MotionEvent
import android.os.Vibrator
import android.os.VibrationEffect
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

var isControlsLocked: Boolean = false

@AndroidEntryPoint
class PlayerActivity : BasePlayerActivity() {

    @Inject lateinit var appPreferences: AppPreferences

    lateinit var binding: ActivityPlayerBinding
    private var playerGestureHelper: PlayerGestureHelper? = null
    override val viewModel: PlayerViewModel by viewModels()
    private var previewScrubListener: PreviewScrubListener? = null
    private var wasZoom: Boolean = false
    private var skipButtonTimeoutExpired: Boolean = true
    private var forcePortrait: Boolean = false

    private lateinit var skipSegmentButton: Button
    private lateinit var btnVr: ImageButton
    private var isVrSteering: Boolean = false
    private var vrSteeringRunnable: Runnable? = null
    private val VR_STEERING_DELAY = 450L
    private var vrSteeringStartX = 0f
    private var vrSteeringStartY = 0f
    private var vrSteeringCurrentX = 0f
    private var vrSteeringCurrentY = 0f
    private val VR_STEERING_SENSITIVITY = 2.0f
    private var originalTouchListener: View.OnTouchListener? = null
    
    private val gyroController by lazy { StandardVideoGyroController() }

    private var lastBufferedPosition = 0L
    private var lastCurrentPosition = 0L
    private var lastNetworkCheckTime = 0L
    private var totalBytesDownloaded = 0L
    private var currentDownloadSpeedMbps = 0.0
    private var lastMediaId: String? = null

    private val isPipSupported by lazy {
        // Check if device has PiP feature
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return@lazy false
        }

        // Check if PiP is enabled for the app
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager?
        appOps?.checkOpNoThrow(
            AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
            Process.myUid(),
            packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    private val handler = Handler(Looper.getMainLooper())
    private val skipButtonTimeout = Runnable {
        if (!binding.playerView.isControllerFullyVisible) {
            skipSegmentButton.isVisible = false
            skipButtonTimeoutExpired = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && binding.sphericalView.isVisible) {
            binding.sphericalView.onResume()
        }
        gyroController.start()

        // Surface Fix & Hybrid Mode Logic
        if (::binding.isInitialized && !viewModel.playerVrMode.value) {
            val player = viewModel.player
            
            // Check if we need to switch to TextureView (for ExoPlayer to support panning/clipping)
            // or if we should stay on SurfaceView (for MPV to avoid crash).
            // Note: We can only "switch" effectively by what we call on the player, 
            // but the View itself is determined by XML (SurfaceView by default now).
            
            val surfaceView = binding.playerView.videoSurfaceView
            
            // If the XML provided a SurfaceView (default), use it.
            if (surfaceView is SurfaceView) {
                 // For MPV, this is safe and correct.
                 // For ExoPlayer, it means clipping fix is disabled, but at least it works.
                 // Ideally we'd swap the view, but PlayerView is rigid.
                 player.setVideoSurfaceView(surfaceView)
            } else if (surfaceView is TextureView) {
                 // If for some reason we have a TextureView (e.g. modified XML), use it.
                 // MPV might crash here, but ExoPlayer loves it.
                 player.setVideoTextureView(surfaceView)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::binding.isInitialized && binding.sphericalView.isVisible) {
            binding.sphericalView.onPause()
        }
        gyroController.stop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extras = intent.extras ?: Bundle()
        val itemId = UUID.fromString(extras.getString("itemId"))
        val itemKind = extras.getString("itemKind")
        val startFromBeginning = extras.getBoolean("startFromBeginning")
        val mediaSourceIndex =
            if (intent.hasExtra("mediaSourceIndex")) {
                intent.getIntExtra("mediaSourceIndex", 0)
            } else {
                null
            }
        val startItemIndex =
            if (intent.hasExtra("startItemIndex")) {
                intent.getIntExtra("startItemIndex", 0)
            } else {
                null
            }
        val forceTranscode =
            if (intent.hasExtra("forceTranscode")) {
                intent.getBooleanExtra("forceTranscode", false)
            } else {
                false
            }
        forcePortrait = extras.getBoolean("forcePortrait", false)
        val startInVr = extras.getBoolean("startInVr", false)

        if (startInVr) {
            viewModel.setVrMode(true, false)
        }

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (forcePortrait) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.playerView.player = viewModel.player
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.GONE) {
                    hideSystemUI()
                }
                // 同步进度条容器的显示/隐藏状态
                val progressBarContainer = binding.playerView.findViewById<View>(R.id.progress_bar_container)
                progressBarContainer.visibility = visibility
                // Update progress bar position based on orientation
                updateProgressBarPosition()
            },
        )

        // Disable clipping on the internal content frame to allow panning zoomed content
        // Note: We use the resource ID from the library if available, or just assume it exists
        // androidx.media3.ui.R.id.exo_content_frame might be resolved if we import it or use full name
        binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)?.let {
             if (it is android.view.ViewGroup) {
                 it.clipChildren = false
             }
        }

        val playerControls = binding.playerView.findViewById<View>(R.id.player_controls)
        val lockedControls = binding.playerView.findViewById<View>(R.id.locked_player_view)

        isControlsLocked = false

        configureInsets(playerControls)
        configureInsets(lockedControls)

        if (appPreferences.getValue(appPreferences.playerGestures)) {
            playerGestureHelper =
                PlayerGestureHelper(
                    appPreferences,
                    this,
                    binding.playerView,
                    getSystemService(AUDIO_SERVICE) as AudioManager,
                )
            playerGestureHelper?.onZoomStateChanged = { enabled ->
                gyroController.setEnabled(enabled)
            }
        }

        binding.playerView.findViewById<View>(R.id.back_button).setOnClickListener {
            finishPlayback()
        }

        val videoNameTextView = binding.playerView.findViewById<TextView>(R.id.video_name)
        val playMethodTextView = binding.playerView.findViewById<TextView>(R.id.play_method)

        val audioButton = binding.playerView.findViewById<ImageButton>(R.id.btn_audio_track)
        val subtitleButton = binding.playerView.findViewById<ImageButton>(R.id.btn_subtitle)
        val speedButton = binding.playerView.findViewById<ImageButton>(R.id.btn_speed)
        skipSegmentButton = binding.playerView.findViewById(R.id.btn_skip_segment)
        val pipButton = binding.playerView.findViewById<ImageButton>(R.id.btn_pip)
        val lockButton = binding.playerView.findViewById<ImageButton>(R.id.btn_lockview)
        val unlockButton = binding.playerView.findViewById<ImageButton>(R.id.btn_unlock)
        btnVr = binding.playerView.findViewById(R.id.btn_vr)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { uiState ->
                        Timber.d("$uiState")
                        uiState.apply {
                            // Title
                            videoNameTextView.text = currentItemTitle

                            // Play method indicator
                            val bitrateInfoTextView =
                                binding.playerView.findViewById<TextView>(R.id.bitrate_info)
                            val playbackInfoContainer =
                                binding.playerView.findViewById<View>(R.id.playback_info_container)
                            val codecInfoTextView =
                                binding.playerView.findViewById<TextView>(R.id.codec_info)
                            val bitrateInfoOverlay =
                                binding.playerView.findViewById<TextView>(R.id.bitrate_info_overlay)
                            if (playMethod == "Transcoding") {
                                playMethodTextView.text = "TR"
                                playMethodTextView.isVisible = true
                                val bitrateDisplay = bitrate?.toLongOrNull()?.let { bps ->
                                    when {
                                        bps >= 1_000_000 -> "${bps / 1_000_000} Mbps"
                                        bps >= 1_000 -> "${bps / 1_000} kbps"
                                        else -> "$bps bps"
                                    }
                                }
                                if (bitrateDisplay != null) {
                                    bitrateInfoTextView.text = bitrateDisplay
                                    bitrateInfoTextView.isVisible = true
                                } else {
                                    bitrateInfoTextView.isVisible = false
                                }
                                // Playback info overlay (bottom-left)
                                playbackInfoContainer.isVisible = true
                                val videoTrack = viewModel.player.currentTracks.groups
                                    .firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
                                val videoFormat = videoTrack?.mediaTrackGroup?.getFormat(0)
                                val codec = videoFormat?.codecs?.uppercase() ?: ""
                                val videoSize = viewModel.player.videoSize
                                codecInfoTextView.text = if (codec.isNotEmpty() && videoSize.height > 0) {
                                    "$codec ${videoSize.height}p"
                                } else {
                                    codec
                                }
                                codecInfoTextView.isVisible = codecInfoTextView.text.isNotEmpty()
                                if (bitrateDisplay != null) {
                                    bitrateInfoOverlay.text = bitrateDisplay
                                    bitrateInfoOverlay.isVisible = true
                                } else {
                                    bitrateInfoOverlay.isVisible = false
                                }
                            } else {
                                playMethodTextView.isVisible = false
                                bitrateInfoTextView.isVisible = false
                                playbackInfoContainer.isVisible = false
                            }

                            // Media segment
                            currentSegment?.let { segment ->
                                // Skip Button - text
                                skipSegmentButton.text = getString(currentSkipButtonStringRes)
                                // Skip Button - visibility
                                skipSegmentButton.isVisible = !isInPictureInPictureMode
                                if (skipSegmentButton.isVisible) {
                                    skipButtonTimeoutExpired = false
                                    handler.removeCallbacks(skipButtonTimeout)
                                    handler.postDelayed(
                                        skipButtonTimeout,
                                        viewModel.segmentsSkipButtonDuration * 1000,
                                    )
                                }
                                // Skip Button - onClick
                                skipSegmentButton.setOnClickListener {
                                    viewModel.skipSegment(segment)
                                    skipSegmentButton.isVisible = false
                                }
                            } ?: run { skipSegmentButton.isVisible = false }

                            binding.playerView.setControllerVisibilityListener(
                                PlayerView.ControllerVisibilityListener { visibility ->
                                    if (skipButtonTimeoutExpired && currentSegment != null) {
                                        skipSegmentButton.visibility = visibility
                                    }
                                    // 同步进度条容器的显示/隐藏状态
                                    val progressBarContainer = binding.playerView.findViewById<View>(R.id.progress_bar_container)
                                    progressBarContainer.visibility = visibility
                                    // Update progress bar position based on orientation
                                    updateProgressBarPosition()
                                },
                            )

                            // Trickplay
                            previewScrubListener?.let { it.currentTrickplay = currentTrickplay }

                            playerGestureHelper?.let { it.currentTrickplay = currentTrickplay }

                            // Chapters
                            val playerControlView =
                                findViewById<PlayerControlView>(R.id.exo_controller)
                            if (currentChapters.isNotEmpty()) {
                                val numOfChapters = currentChapters.size
                                playerControlView.setExtraAdGroupMarkers(
                                    LongArray(numOfChapters) { index ->
                                        currentChapters[index].startPosition
                                    },
                                    BooleanArray(numOfChapters) { false },
                                )
                            } else {
                                playerControlView.setExtraAdGroupMarkers(null, null)
                            }

                            // File Loaded
                            if (fileLoaded) {
                                audioButton.isEnabled = true
                                audioButton.imageAlpha = 255
                                lockButton.isEnabled = true
                                lockButton.imageAlpha = 255
                                subtitleButton.isEnabled = true
                                subtitleButton.imageAlpha = 255
                                speedButton.isEnabled = true
                                speedButton.imageAlpha = 255
                                pipButton.isEnabled = true
                                pipButton.imageAlpha = 255
                                btnVr.isEnabled = true
                                btnVr.imageAlpha = 255
                            }
                        }
                    }
                }

                launch {
                    viewModel.playerVrMode.collect { isVrEnabled ->
                        updateVrMode(isVrEnabled)
                    }
                }

                launch {
                    viewModel.playerVrProjection.collect { projection ->
                        updateVrProjection(projection)
                    }
                }

                launch {
                    viewModel.eventsChannelFlow.collect { event ->
                        when (event) {
                            is PlayerEvents.NavigateBack -> finishPlayback()
                            is PlayerEvents.IsPlayingChanged -> {
                                if (event.isPlaying) {
                                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                } else {
                                    window.clearFlags(
                                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                    )
                                }

                                if (appPreferences.getValue(appPreferences.playerPipGesture)) {
                                    try {
                                        setPictureInPictureParams(pipParams(event.isPlaying))
                                    } catch (_: IllegalArgumentException) {}
                                }
                            }
                        }
                    }
                }

                launch {
                    while (true) {
                        viewModel.updatePlaybackProgress()
                        delay(5000L)
                    }
                }

                launch {
                    while (true) {
                        updateNetworkStats()
                        delay(2000L)
                    }
                }

                if (
                    appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButton) ||
                        appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkip)
                ) {
                    launch {
                        while (true) {
                            viewModel.updateCurrentSegment()
                            delay(1000L)
                        }
                    }
                }
            }
        }

        audioButton.isEnabled = false
        audioButton.imageAlpha = 75

        lockButton.isEnabled = false
        lockButton.imageAlpha = 75

        subtitleButton.isEnabled = false
        subtitleButton.imageAlpha = 75

        speedButton.isEnabled = false
        speedButton.imageAlpha = 75

        if (isPipSupported) {
            pipButton.isEnabled = false
            pipButton.imageAlpha = 75
        } else {
            val pipSpace = binding.playerView.findViewById<Space>(R.id.space_pip)
            pipButton.isVisible = false
            pipSpace.isVisible = false
        }

        btnVr.isEnabled = false
        btnVr.imageAlpha = 75

        audioButton.setOnClickListener {
            TrackSelectionDialogFragment(C.TRACK_TYPE_AUDIO, viewModel)
                .show(supportFragmentManager, "trackselectiondialog")
        }

        val exoPlayerControlView = findViewById<FrameLayout>(R.id.player_controls)
        val lockedLayout = findViewById<FrameLayout>(R.id.locked_player_view)

        lockButton.setOnClickListener {
            exoPlayerControlView.visibility = View.GONE
            lockedLayout.visibility = View.VISIBLE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            isControlsLocked = true
        }

        unlockButton.setOnClickListener {
            exoPlayerControlView.visibility = View.VISIBLE
            lockedLayout.visibility = View.GONE
            requestedOrientation = if (forcePortrait) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            isControlsLocked = false
        }

        val orientationButton = binding.playerView.findViewById<ImageButton>(R.id.btn_orientation)
        orientationButton.setOnClickListener {
            forcePortrait = !forcePortrait
            requestedOrientation = if (forcePortrait) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            applyPortraitUIAdjustments()
        }

        subtitleButton.setOnClickListener {
            TrackSelectionDialogFragment(C.TRACK_TYPE_TEXT, viewModel)
                .show(supportFragmentManager, "trackselectiondialog")
        }

        speedButton.setOnClickListener {
            SpeedSelectionDialogFragment(viewModel)
                .show(supportFragmentManager, "speedselectiondialog")
        }

        btnVr.setOnClickListener {
            if (!viewModel.playerVrMode.value) {
                viewModel.toggleVrMode()
            } else {
                VrModeSelectionDialogFragment(viewModel)
                    .show(supportFragmentManager, "vrmodeselectiondialog")
            }
        }

        btnVr.setOnLongClickListener {
            if (viewModel.playerVrMode.value) {
                viewModel.toggleVrMode()
            }
            true
        }

        pipButton.setOnClickListener { pictureInPicture() }

        val playbackInfoContainer = binding.playerView.findViewById<View>(R.id.playback_info_container)
        playbackInfoContainer.setOnClickListener {
            val bitrateOptions = listOf(
                "300 Kbps" to "300000",
                "500 Kbps" to "500000",
                "1 Mbps" to "1000000",
                "2 Mbps" to "2000000",
                "4 Mbps" to "4000000",
                "8 Mbps" to "8000000",
                "10 Mbps" to "10000000",
                "15 Mbps" to "15000000",
                "20 Mbps" to "20000000",
                "40 Mbps" to "40000000",
                "80 Mbps" to "80000000",
                "120 Mbps" to "120000000",
            )
            val currentBitrate = appPreferences.getValue(appPreferences.playerTranscodingBitrate) ?: "10000000"
            val currentIndex = bitrateOptions.indexOfFirst { it.second == currentBitrate }

            MaterialAlertDialogBuilder(this)
                .setTitle("Select bitrate")
                .setSingleChoiceItems(
                    bitrateOptions.map { it.first }.toTypedArray(),
                    currentIndex.coerceAtLeast(0),
                ) { dialog, which ->
                    viewModel.reloadWithBitrate(bitrateOptions[which].second)
                    dialog.dismiss()
                }
                .show()
        }

        // Set marker color
        val timeBar = binding.playerView.findViewById<DefaultTimeBar>(R.id.exo_progress)
        timeBar.setAdMarkerColor(Color.WHITE)

        if (appPreferences.getValue(appPreferences.playerTrickplay)) {
            val imagePreview = binding.playerView.findViewById<ImageView>(R.id.image_preview)
            previewScrubListener = PreviewScrubListener(imagePreview, timeBar, viewModel.player)

            timeBar.addListener(previewScrubListener!!)
        }

        viewModel.initializePlayer(
            itemId = itemId,
            itemKind = itemKind ?: "",
            startFromBeginning = startFromBeginning,
            mediaSourceIndex = mediaSourceIndex,
            startItemIndex = startItemIndex,
            forceTranscode = forceTranscode,
        )
        hideSystemUI()
        applyPortraitUIAdjustments()
    }

    private fun handleVrSteeringIntercept(event: MotionEvent): Boolean {
        if (!binding.sphericalView.isVisible) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vrSteeringRunnable?.let { handler.removeCallbacks(it) }
                vrSteeringStartX = event.x
                vrSteeringStartY = event.y
                vrSteeringCurrentX = event.x
                vrSteeringCurrentY = event.y
                vrSteeringRunnable = Runnable {
                    if (binding.sphericalView.isVisible) {
                        Timber.d("VR: Long press triggered for steering (manual)")
                        vibrateForSteering()
                        isVrSteering = true
                        showVrSteeringIndicator()
                        
                        val now = android.os.SystemClock.uptimeMillis()
                        // Use the START coordinates to initialize the drag in spherical view
                        val downEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, vrSteeringStartX, vrSteeringStartY, 0)
                        binding.sphericalView.dispatchTouchEvent(downEvent)
                        downEvent.recycle()

                        // IMPORTANT: Immediately dispatch the CURRENT coordinates MOVE event 
                        // if the finger has already moved since the DOWN event.
                        if (vrSteeringCurrentX != vrSteeringStartX || vrSteeringCurrentY != vrSteeringStartY) {
                            val scaledX = vrSteeringStartX + (vrSteeringCurrentX - vrSteeringStartX) * VR_STEERING_SENSITIVITY
                            val scaledY = vrSteeringStartY + (vrSteeringCurrentY - vrSteeringStartY) * VR_STEERING_SENSITIVITY
                            val moveEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, scaledX, scaledY, 0)
                            binding.sphericalView.dispatchTouchEvent(moveEvent)
                            moveEvent.recycle()
                        }
                    }
                }
                handler.postDelayed(vrSteeringRunnable!!, VR_STEERING_DELAY)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                vrSteeringCurrentX = event.x
                vrSteeringCurrentY = event.y
                
                if (isVrSteering) {
                    val scaledX = vrSteeringStartX + (event.x - vrSteeringStartX) * VR_STEERING_SENSITIVITY
                    val scaledY = vrSteeringStartY + (event.y - vrSteeringStartY) * VR_STEERING_SENSITIVITY
                    
                    val now = android.os.SystemClock.uptimeMillis()
                    val moveEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, scaledX, scaledY, 0)
                    binding.sphericalView.dispatchTouchEvent(moveEvent)
                    moveEvent.recycle()
                    return true
                } else {
                    val dx = event.x - vrSteeringStartX
                    val dy = event.y - vrSteeringStartY
                    // Tighten threshold to 15dp to balance reliability vs accidental pop outs
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) > 15.dpToPx()) {
                        vrSteeringRunnable?.let { 
                            handler.removeCallbacks(it)
                            vrSteeringRunnable = null
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vrSteeringRunnable?.let { 
                    handler.removeCallbacks(it)
                    vrSteeringRunnable = null
                }
                if (isVrSteering) {
                    val scaledX = vrSteeringStartX + (event.x - vrSteeringStartX) * VR_STEERING_SENSITIVITY
                    val scaledY = vrSteeringStartY + (event.y - vrSteeringStartY) * VR_STEERING_SENSITIVITY
                    
                    val now = android.os.SystemClock.uptimeMillis()
                    val upEvent = MotionEvent.obtain(now, now, event.actionMasked, scaledX, scaledY, 0)
                    binding.sphericalView.dispatchTouchEvent(upEvent)
                    upEvent.recycle()
                    
                    isVrSteering = false
                    hideVrSteeringIndicator()
                    return true
                }
            }
        }
        return false
    }

    private fun vibrateForSteering() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(150)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to vibrate")
        }
    }

    private fun showVrSteeringIndicator() {
        binding.vrSteeringIndicator.apply {
            alpha = 0f
            scaleX = 0.5f
            scaleY = 0.5f
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .scaleX(1.4f)
                .scaleY(1.4f)
                .setDuration(250)
                .withEndAction {
                    animate()
                        .scaleX(1.1f)
                        .scaleY(1.1f)
                        .setDuration(100)
                        .start()
                }
                .start()
        }
    }

    private fun hideVrSteeringIndicator() {
        binding.vrSteeringIndicator.animate()
            .alpha(0f)
            .scaleX(0.5f)
            .scaleY(0.5f)
            .setDuration(200)
            .withEndAction {
                binding.vrSteeringIndicator.visibility = View.GONE
            }
            .start()
    }

    private fun applyPortraitUIAdjustments() {
        val progressContainer = binding.playerView.findViewById<View>(R.id.progress_bar_container) ?: return
        val playbackControls = binding.playerView.findViewById<View>(R.id.bottom_controls_container) ?: return
        val currentOrientation = resources.configuration.orientation
        
        val progressParams = progressContainer.layoutParams as FrameLayout.LayoutParams
        val playbackParams = playbackControls.layoutParams as FrameLayout.LayoutParams
        
        if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
            // Portrait mode: Single-hand optimization for progress bar
            progressParams.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            progressParams.setMargins(0, 0, 16.dpToPx(), 120.dpToPx()) 
            progressParams.width = (resources.displayMetrics.widthPixels * 0.65).toInt()
            
            // Move main playback controls lower to avoid overlaying video
            playbackParams.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            playbackParams.setMargins(0, 0, 0, 220.dpToPx()) // Positioned above progress bar but below typical video area
        } else {
            // Landscape mode: Normal layouts
            progressParams.gravity = android.view.Gravity.BOTTOM
            progressParams.setMargins(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            progressParams.width = FrameLayout.LayoutParams.MATCH_PARENT
            
            playbackParams.gravity = android.view.Gravity.CENTER
            playbackParams.setMargins(0, 0, 0, 0)
        }
        progressContainer.layoutParams = progressParams
        playbackControls.layoutParams = playbackParams
    }

    private fun updateVrMode(enabled: Boolean) {
        if (enabled) {
            binding.sphericalView.visibility = View.VISIBLE
            binding.sphericalView.onResume()
            binding.playerView.videoSurfaceView?.visibility = View.GONE
            binding.playerView.setBackgroundColor(Color.TRANSPARENT)
            
            // Hook into PlayerGestureHelper
            playerGestureHelper?.vrInterceptListener = { event ->
                handleVrSteeringIntercept(event)
            }
            
            viewModel.player.setVideoSurfaceView(binding.sphericalView)
            btnVr.setColorFilter(Color.parseColor("#00a0d6"))
            
            // Center the view after a short delay to ensure rendering started
            handler.postDelayed({ centerVrView() }, 500)
        } else {
            binding.sphericalView.onPause()
            binding.sphericalView.visibility = View.GONE
            binding.playerView.videoSurfaceView?.visibility = View.VISIBLE
            binding.playerView.setBackgroundColor(Color.BLACK)
            
            // Clear the hook
            playerGestureHelper?.vrInterceptListener = null
            
            // Important: Re-attach the surface view of the PlayerView
            val surfaceView = binding.playerView.videoSurfaceView
            if (surfaceView is SurfaceView) {
                 viewModel.player.setVideoSurfaceView(surfaceView)
            } else if (surfaceView is TextureView) {
                 viewModel.player.setVideoTextureView(surfaceView)
            } else {
                 viewModel.player.clearVideoSurface()
            }
            
            btnVr.clearColorFilter()
        }
    }

    private fun updateVrProjection(projection: String) {
        if (projection == "PLANE_2D") {
            if (viewModel.playerVrMode.value) {
                viewModel.toggleVrMode()
            }
            return
        }
        
        // Use default if nothing specific matches for now, ensuring we stay in VR
        viewModel.player.setVideoSurfaceView(binding.sphericalView)
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPortraitUIAdjustments()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val extras = intent.extras ?: Bundle()
        val itemId = UUID.fromString(extras.getString("itemId"))
        val itemKind = extras.getString("itemKind")
        val startFromBeginning = extras.getBoolean("startFromBeginning")
        val startInVr = extras.getBoolean("startInVr", false)

        if (startInVr) {
            viewModel.setVrMode(true, false)
        }

        val startItemIndex =
            if (intent.hasExtra("startItemIndex")) {
                intent.getIntExtra("startItemIndex", 0)
            } else {
                null
            }

        viewModel.initializePlayer(
            itemId = itemId,
            itemKind = itemKind ?: "",
            startFromBeginning = startFromBeginning,
            startItemIndex = startItemIndex,
        )
        applyPortraitUIAdjustments()
    }

    private fun centerVrView() {
        if (!binding.sphericalView.isVisible) return
        
        Timber.d("VR: Force centering view")
        // Simulate a substantial drag sequence to "wake up" the TouchTracker and ensure it resets
        // SphericalGLSurfaceView initializes its internal rotation based on sensor or first touch.
        // We'll dispatch a small back-and-forth movement at the center.
        handler.post {
            val now = android.os.SystemClock.uptimeMillis()
            val centerX = resources.displayMetrics.widthPixels / 2f
            val centerY = resources.displayMetrics.heightPixels / 2f
            
            // DOWN at center
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, centerX, centerY, 0)
            binding.sphericalView.dispatchTouchEvent(down)
            
            // Small MOVEs to register intent
            for (i in 1..5) {
                val move = MotionEvent.obtain(now, now + i * 10, MotionEvent.ACTION_MOVE, centerX + i, centerY, 0)
                binding.sphericalView.dispatchTouchEvent(move)
                move.recycle()
            }
            
            // UP
            val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, centerX + 5, centerY, 0)
            binding.sphericalView.dispatchTouchEvent(up)
            
            down.recycle()
            up.recycle()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                appPreferences.getValue(appPreferences.playerPipGesture) &&
                viewModel.player.isPlaying &&
                !isControlsLocked
        ) {
            pictureInPicture()
        }
    }

    private fun updateNetworkStats() {
        val player = viewModel.player
        val bufferedPos = player.bufferedPosition
        val now = System.currentTimeMillis()
        val currentMediaId = player.currentMediaItem?.mediaId

        if (currentMediaId != lastMediaId) {
            totalBytesDownloaded = 0L
            currentDownloadSpeedMbps = 0.0
            lastMediaId = currentMediaId
            lastBufferedPosition = bufferedPos
            lastCurrentPosition = player.currentPosition
            lastNetworkCheckTime = now
            return
        }

        val currentPosition = player.currentPosition
        val currentPosDelta = abs(currentPosition - lastCurrentPosition)

        if (currentPosDelta > 5000) {
            lastBufferedPosition = bufferedPos
            lastCurrentPosition = currentPosition
            lastNetworkCheckTime = now
            return
        }

        val bufferDelta = bufferedPos - lastBufferedPosition

        if (bufferDelta < -1000) {
            // Seek happened - buffer was reset
            lastBufferedPosition = bufferedPos
            lastNetworkCheckTime = now
            return
        }

        if (lastNetworkCheckTime > 0 && bufferDelta > 0) {
            val timeDelta = (now - lastNetworkCheckTime) / 1000.0

            if (timeDelta > 0) {
                val formatBitrate = player.currentTracks.groups
                    .firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
                    ?.mediaTrackGroup?.getFormat(0)?.bitrate?.toLong()
                val prefBitrate = appPreferences.getValue(appPreferences.playerTranscodingBitrate).toLong() * 1_000_000
                val bitrate = formatBitrate ?: prefBitrate
                val bytesDownloaded = ((bufferDelta / 1000.0) * (bitrate / 8.0)).toLong()
                currentDownloadSpeedMbps = (bytesDownloaded * 8 / timeDelta) / 1_000_000.0
                totalBytesDownloaded += bytesDownloaded
            }
        } else if (bufferedPos < lastBufferedPosition) {
            // Normal buffer fluctuation - don't reset total
        }

        lastBufferedPosition = bufferedPos
        lastCurrentPosition = currentPosition
        lastNetworkCheckTime = now

        val networkSpeedView = binding.playerView.findViewById<TextView>(R.id.network_speed)
        val dataUsageView = binding.playerView.findViewById<TextView>(R.id.data_usage)

        networkSpeedView.text = String.format("%.1f Mbps", currentDownloadSpeedMbps)
        dataUsageView.text = formatBytes(totalBytesDownloaded)
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun finishPlayback() {
        try {
            val surfaceView = binding.playerView.videoSurfaceView
            if (surfaceView is SurfaceView) {
                viewModel.player.clearVideoSurfaceView(surfaceView)
            } else if (surfaceView is TextureView) {
                viewModel.player.clearVideoTextureView(surfaceView)
            }
        } catch (e: Exception) {
            Timber.e(e)
        }
        handler.removeCallbacks(skipButtonTimeout)
        finish()
    }

    private fun pipParams(
        enableAutoEnter: Boolean = viewModel.player.isPlaying
    ): PictureInPictureParams {
        val displayAspectRatio = Rational(binding.playerView.width, binding.playerView.height)

        val aspectRatio =
            binding.playerView.player?.videoSize?.let {
                Rational(
                    it.width.coerceAtMost((it.height * 2.39f).toInt()),
                    it.height.coerceAtMost((it.width * 2.39f).toInt()),
                )
            }

        val sourceRectHint =
            if (displayAspectRatio < aspectRatio!!) {
                val space =
                    ((binding.playerView.height -
                            (binding.playerView.width.toFloat() / aspectRatio.toFloat())) / 2)
                        .toInt()
                Rect(
                    0,
                    space,
                    binding.playerView.width,
                    (binding.playerView.width.toFloat() / aspectRatio.toFloat()).toInt() + space,
                )
            } else {
                val space =
                    ((binding.playerView.width -
                            (binding.playerView.height.toFloat() * aspectRatio.toFloat())) / 2)
                        .toInt()
                Rect(
                    space,
                    0,
                    (binding.playerView.height.toFloat() * aspectRatio.toFloat()).toInt() + space,
                    binding.playerView.height,
                )
            }

        val builder =
            PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .setSourceRectHint(sourceRectHint)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(enableAutoEnter)
        }

        return builder.build()
    }

    private fun pictureInPicture() {
        if (!isPipSupported) {
            return
        }

        try {
            enterPictureInPictureMode(pipParams())
        } catch (_: IllegalArgumentException) {}
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.isInPictureInPictureMode = isInPictureInPictureMode
        when (isInPictureInPictureMode) {
            true -> {
                binding.playerView.useController = false
                skipSegmentButton.isVisible = false

                wasZoom = playerGestureHelper?.isZoomEnabled == true
                playerGestureHelper?.updateZoomMode(false)

                // Brightness mode Auto
                window.attributes =
                    window.attributes.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
            }

            false -> {
                binding.playerView.useController = true
                playerGestureHelper?.updateZoomMode(wasZoom)

                // Override auto brightness
                if (
                    appPreferences.getValue(appPreferences.playerGesturesVB) &&
                        appPreferences.getValue(appPreferences.playerGesturesBrightnessRemember)
                ) {
                    window.attributes =
                        window.attributes.apply {
                            screenBrightness =
                                appPreferences.getValue(appPreferences.playerBrightness)
                        }
                }
            }
        }
    }
private inner class StandardVideoGyroController : SensorEventListener {
        private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
        private val gyroscope by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
        private var isEnabled = false
        private var isListening = false
        
        // For view-based panning (ExoPlayer)
        private var maxTransX = 0f
        private var maxTransY = 0f
        
        // For MPV-native panning
        private val isMpv: Boolean get() = appPreferences.getValue(appPreferences.playerBackend) == "mpv"
        private var mpvPanX = 0.0
        private var mpvPanY = 0.0
        private var maxMpvPanX = 0.0
        private var maxMpvPanY = 0.0
        
        fun setEnabled(enabled: Boolean) {
            if (isEnabled != enabled) {
                isEnabled = enabled
                if (!enabled) {
                    resetPanning()
                }
                updateListeningState()
            }
        }
        
        fun start() {
            updateListeningState()
        }
        
        fun stop() {
            if (isListening) {
                sensorManager.unregisterListener(this)
                isListening = false
            }
        }
        
        private fun updateListeningState() {
            if (isEnabled && !isListening) {
                gyroscope?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                    isListening = true
                }
            } else if (!isEnabled && isListening) {
                stop()
            }
        }
        
        private fun resetPanning() {
            if (isMpv) {
                mpvPanX = 0.0
                mpvPanY = 0.0
                try {
                    (viewModel.player as? dev.jdtech.jellyfin.player.local.mpv.MPVPlayer)?.setProperty("video-pan-x", "0.0")
                    (viewModel.player as? dev.jdtech.jellyfin.player.local.mpv.MPVPlayer)?.setProperty("video-pan-y", "0.0")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to reset MPV pan")
                }
            } else {
                binding.playerView.videoSurfaceView?.animate()
                    ?.translationX(0f)
                    ?.translationY(0f)
                    ?.setDuration(0)
                    ?.start()
            }
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null || !isEnabled) return
            
            calculateLimits()
            
            if (isMpv) {
                handleMpvPanning(event)
            } else {
                handleViewPanning(event)
            }
        }
        
        private fun handleMpvPanning(event: SensorEvent) {
            if (maxMpvPanX <= 0 && maxMpvPanY <= 0) return
            
            // MPV video-pan-x/y are in fractions of the video size 
            // (0.0 = center, positive = shift right/down)
            val sensitivity = 0.005
            val config = resources.configuration
            
            var deltaX: Double
            var deltaY: Double
            
            if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                deltaX = -event.values[1].toDouble() * sensitivity
                deltaY = -event.values[0].toDouble() * sensitivity
            } else {
                deltaX = -event.values[1].toDouble() * sensitivity
                deltaY = -event.values[0].toDouble() * sensitivity
            }
            
            mpvPanX += deltaX
            mpvPanY += deltaY
            
            // Clamp
            mpvPanX = max(-maxMpvPanX, min(mpvPanX, maxMpvPanX))
            mpvPanY = max(-maxMpvPanY, min(mpvPanY, maxMpvPanY))
            
            try {
                (viewModel.player as? dev.jdtech.jellyfin.player.local.mpv.MPVPlayer)?.setProperty("video-pan-x", mpvPanX.toString())
                (viewModel.player as? dev.jdtech.jellyfin.player.local.mpv.MPVPlayer)?.setProperty("video-pan-y", mpvPanY.toString())
            } catch (e: Exception) {
                Timber.e(e, "Failed to set MPV pan")
            }
        }
        
        private fun handleViewPanning(event: SensorEvent) {
            val surfaceView = binding.playerView.videoSurfaceView ?: return
            if (maxTransX <= 0 && maxTransY <= 0) return
            
            val sensitivity = 50f
            val config = resources.configuration
            
            var deltaX = 0f
            var deltaY = 0f
            
            if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                deltaX = -event.values[1] * sensitivity 
                deltaY = -event.values[0] * sensitivity
            } else {
                deltaX = -event.values[1] * sensitivity 
                deltaY = -event.values[0] * sensitivity
            }
            
            var newTransX = surfaceView.translationX + deltaX
            var newTransY = surfaceView.translationY + deltaY
            
            newTransX = max(-maxTransX, min(newTransX, maxTransX))
            newTransY = max(-maxTransY, min(newTransY, maxTransY))
            
            surfaceView.translationX = newTransX
            surfaceView.translationY = newTransY
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // No-op
        }
        
        private fun calculateLimits() {
            val videoSize = binding.playerView.player?.videoSize ?: return
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            
            val viewWidth = binding.playerView.width.toFloat()
            val viewHeight = binding.playerView.height.toFloat()
            if (viewWidth <= 0 || viewHeight <= 0) return
            
            val scaleX = viewWidth / videoSize.width
            val scaleY = viewHeight / videoSize.height
            val scale = max(scaleX, scaleY)
            
            val renderedWidth = videoSize.width * scale
            val renderedHeight = videoSize.height * scale
            
            // View-based limits (pixels)
            maxTransX = (renderedWidth - viewWidth) / 2f
            maxTransY = (renderedHeight - viewHeight) / 2f
            if (maxTransX < 1f) maxTransX = 0f
            if (maxTransY < 1f) maxTransY = 0f
            
            // MPV-native limits (fraction of video size)
            // video-pan-x of 1.0 shifts the video by its full width
            // We want to shift by at most half the overflow, normalized to video size
            maxMpvPanX = if (renderedWidth > viewWidth) {
                ((renderedWidth - viewWidth) / 2.0) / renderedWidth.toDouble()
            } else 0.0
            maxMpvPanY = if (renderedHeight > viewHeight) {
                ((renderedHeight - viewHeight) / 2.0) / renderedHeight.toDouble()
            } else 0.0
        }
    }

    /**
     * Update the position of the progress bar based on screen orientation
     * Since controls are now at the bottom, we don't need to adjust the position
     */
    private fun updateProgressBarPosition() {
        // No longer needed as controls are now at the bottom
        // Keeping the method for compatibility
    }
}
