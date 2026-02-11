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

        val itemId = UUID.fromString(intent.extras!!.getString("itemId"))
        val itemKind = intent.extras!!.getString("itemKind")
        val startFromBeginning = intent.extras!!.getBoolean("startFromBeginning")
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
        forcePortrait = intent.extras!!.getBoolean("forcePortrait", false)
        val startInVr = intent.extras!!.getBoolean("startInVr", false)

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
            }
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
                                }
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
        val progressContainer = binding.playerView.findViewById<LinearLayout>(R.id.progress_container) ?: return
        val playbackControls = binding.playerView.findViewById<LinearLayout>(R.id.playback_controls_container) ?: return
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
            } else {
                 // Fallback if null or not surface view, clear it to let PlayerView handle generic surface logic
                 viewModel.player.clearVideoSurface()
                 viewModel.player.setVideoSurfaceView(surfaceView as? SurfaceView)
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

        val itemId = UUID.fromString(intent.extras!!.getString("itemId"))
        val itemKind = intent.extras!!.getString("itemKind")
        val startFromBeginning = intent.extras!!.getBoolean("startFromBeginning")
        val startInVr = intent.extras!!.getBoolean("startInVr", false)

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

    private fun finishPlayback() {
        try {
            viewModel.player.clearVideoSurfaceView(
                binding.playerView.videoSurfaceView as SurfaceView
            )
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
        
        // Limits for translation
        private var maxTransX = 0f
        private var maxTransY = 0f
        
        fun setEnabled(enabled: Boolean) {
            if (isEnabled != enabled) {
                isEnabled = enabled
                // Reset translation when disabling
                if (!enabled) {
                    resetTranslation()
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
        
        private fun resetTranslation() {
            binding.playerView.videoSurfaceView?.animate()
                ?.translationX(0f)
                ?.translationY(0f)
                ?.setDuration(0)
                ?.start()
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null || !isEnabled) return
            
            val surfaceView = binding.playerView.videoSurfaceView ?: return
            
            // Calculate limits based on current dimensions
            calculateLimits()
            
            // If limits are 0, it means we fit on screen, no need to pan
            if (maxTransX <= 0 && maxTransY <= 0) return
            
            // Adjust for orientation
            // Standard landscape: Y rotation (roll) -> moves X
            // X rotation (pitch) -> moves Y
            
            val sensitivity = 50f
            val config = resources.configuration
            
            // In landscape, tilting phone left/right (positive/negative Y) should move content right/left
            // If I tilt Right (screen right side go down), Y rot is positive?
            // If surface moves LEFT (negative X), I see right content.
            // So: +Y rot -> -X trans.
            
            var deltaX = 0f
            var deltaY = 0f
            
            if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                deltaX = -event.values[1] * sensitivity 
                deltaY = -event.values[0] * sensitivity
            } else {
                // Portrait
                deltaX = -event.values[1] * sensitivity 
                deltaY = -event.values[0] * sensitivity
            }
            
            var newTransX = surfaceView.translationX + deltaX
            var newTransY = surfaceView.translationY + deltaY
            
            // Clamp
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
            
            // When RESIZE_MODE_ZOOM is active, the content fills the view.
            val scaleX = viewWidth / videoSize.width
            val scaleY = viewHeight / videoSize.height
            val scale = max(scaleX, scaleY)
            
            val renderedWidth = videoSize.width * scale
            val renderedHeight = videoSize.height * scale
            
            maxTransX = (renderedWidth - viewWidth) / 2f
            maxTransY = (renderedHeight - viewHeight) / 2f
            
            if (maxTransX < 1f) maxTransX = 0f
            if (maxTransY < 1f) maxTransY = 0f
        }
    }
}
