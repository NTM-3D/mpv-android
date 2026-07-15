package `is`.xyz.mpv.ntm3d

import `is`.xyz.mpv.ntm3d.databinding.PlayerBinding
import `is`.xyz.mpv.ntm3d.MPVLib.MpvEvent
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import androidx.appcompat.app.AlertDialog
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.util.Log
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager.getDefaultSharedPreferences
import android.provider.OpenableColumns
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.DisplayMetrics
import android.util.Rational
import androidx.core.content.ContextCompat
import android.view.*
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import com.leia.sdk.LeiaSDK
import java.io.File
import java.lang.IllegalArgumentException
import kotlin.math.roundToInt

typealias ActivityResultCallback = (Int, Intent?) -> Unit
typealias StateRestoreCallback = () -> Unit

enum class LeiaFormat { NONE, HALF_SBS, HALF_TAB, FULL_SBS, FULL_TAB }

fun detectLeiaFormat(filename: String): LeiaFormat {
    val name = filename.lowercase()
    val tokenBoundary = "[\\s._\\-\\[\\]\\(\\)]"
    fun hasToken(token: String): Boolean {
        val pattern = "(^|$tokenBoundary)${Regex.escape(token)}($tokenBoundary|$)"
        return Regex(pattern).containsMatchIn(name)
    }
    fun hasAnyToken(tokens: Array<String>): Boolean = tokens.any { hasToken(it) }

    val halfSbsExplicit = hasAnyToken(arrayOf("hsbs", "half-sbs", "half_sbs", "sbs-half", "sbs_half", "half_2x1"))
    val halfTabExplicit = hasAnyToken(arrayOf("htab", "half-tab", "half_tab", "tab-half", "tab_half", "half_1x2", "half-ou", "half_ou", "half-overunder"))
    val fullSbsExplicit = hasAnyToken(arrayOf("fsbs", "full-sbs", "full_sbs", "sbs-full", "sbs_full", "full_2x1"))
    val fullTabExplicit = hasAnyToken(arrayOf("ftab", "full-tab", "full_tab", "tab-full", "tab_full", "full_1x2", "full-ou", "full_ou", "full-overunder"))

    val genericSbs = hasAnyToken(arrayOf("3d", "sbs"))
    val genericTabOrOu = hasAnyToken(arrayOf("tab", "ou", "overunder", "over-under", "over_under", "topbottom", "top-bottom", "top_bottom", "tb"))

    val has2x1Fallback = name.contains("_2x1") && !name.contains("half_2x1")
    val has1x2Fallback = name.contains("_1x2") && !name.contains("half_1x2")

    // Camera-generated filenames like SV_20260709_175214.mp4 are always Full SBS.
    val svTimestamped = Regex("^sv_\\d{8}(_|\\.|$)").containsMatchIn(name)

    return when {
        halfSbsExplicit -> LeiaFormat.HALF_SBS
        halfTabExplicit -> LeiaFormat.HALF_TAB
        fullSbsExplicit -> LeiaFormat.FULL_SBS
        fullTabExplicit -> LeiaFormat.FULL_TAB
        genericSbs -> LeiaFormat.HALF_SBS
        genericTabOrOu -> LeiaFormat.HALF_TAB
        has2x1Fallback -> LeiaFormat.FULL_SBS
        has1x2Fallback -> LeiaFormat.FULL_TAB
        svTimestamped -> LeiaFormat.FULL_SBS
        else -> LeiaFormat.NONE
    }
}

class MPVActivity : AppCompatActivity(), MPVLib.EventObserver, TouchGesturesObserver {
    // Leia
    private var mPrevDesiredBacklightModeState = false
    private lateinit var sdk: LeiaSDK
    private var leiaEnabled = false
    private var loggedFirstImageSubtitleFrame = false
    private var currentLeiaFormat = LeiaFormat.NONE
    private var swapEyes = false
    private var subtitleDepth = 0
    private var subtitlePosition = 0
    private var subtitleSize = 0
    private var imageSubtitle3D = true
    private var imageSubtitleScale = 0
    private var imageSubtitlePosition = 100
    private var imageSubsScaleX = 10
    private var imageSubsScaleY = 10
    private var currentFilePath = ""
    private var currentSubText = ""
    private var stereoSubtitleModeEnabled = false
    private var hasGuessedNetworkSubtitles = false
    private var subtitleBitmap: Bitmap? = null
    private var imageSubtitleDecoderReady = false
    private var imageSubtitleDecoderPath: String? = null
    private var imageSubtitleDecoderFfIndex: Int? = null
    private var imageSubtitleDecoderRequestKey: String? = null
    private var imageSubtitleDecoderFailedKey: String? = null
    private var userForced3DOffForCurrentFile = false
    private var imageSubtitleDecoderGeneration = 0
    private var imageSubtitleInitThread: HandlerThread? = null
    private var imageSubtitleInitHandler: Handler? = null
    private enum class ImageSubtitleDecoderState { IDLE, INITIALIZING, READY, FAILED }
    private var imageSubtitleDecoderState = ImageSubtitleDecoderState.IDLE

    // for calls to eventUi() and eventPropertyUi()
    private val eventUiHandler = Handler(Looper.getMainLooper())
    // for use with fadeRunnable1..3
    private val fadeHandler = Handler(Looper.getMainLooper())
    // for use with stopServiceRunnable
    private val stopServiceHandler = Handler(Looper.getMainLooper())

    /**
     * DO NOT USE THIS
     */
    private var activityIsStopped = false

    private var activityIsForeground = true
    private var didResumeBackgroundPlayback = false
    private var userIsOperatingSeekbar = false

    private var toast: Toast? = null

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequestCompat? = null
    private var audioFocusRestore: () -> Unit = {}

    private val psc = Utils.PlaybackStateCache()
    private var mediaSession: MediaSessionCompat? = null

    private lateinit var binding: PlayerBinding
    private lateinit var gestures: TouchGestures

    // convenience alias
    private val player get() = binding.player

    private val seekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser)
                return
            player.timePos = progress.toDouble() / SEEK_BAR_PRECISION
            // Note: don't call updatePlaybackPos() here either
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            userIsOperatingSeekbar = true
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            userIsOperatingSeekbar = false
            showControls() // re-trigger display timeout
        }
    }

    private var becomingNoisyReceiverRegistered = false
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS, "noisy")
            }
        }
    }

    // Fade out controls
    private val fadeRunnable = object : Runnable {
        var hasStarted = false
        private val listener = object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) { hasStarted = true }

            override fun onAnimationCancel(animation: Animator) { hasStarted = false }

            override fun onAnimationEnd(animation: Animator) {
                if (hasStarted)
                    hideControls()
                hasStarted = false
            }
        }

        override fun run() {
            binding.topControls.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION)
            binding.controls.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION).setListener(listener)
        }
    }

    // Fade out unlock button
    private val fadeRunnable2 = object : Runnable {
        private val listener = object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                binding.unlockBtn.visibility = View.GONE
            }
        }

        override fun run() {
            binding.unlockBtn.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION).setListener(listener)
        }
    }

    // Fade out gesture text
    private val fadeRunnable3 = object : Runnable {
        // okay this doesn't actually fade...
        override fun run() {
            binding.gestureTextView.visibility = View.GONE
        }
    }

    private val stopServiceRunnable = Runnable {
        val intent = Intent(this, BackgroundPlaybackService::class.java)
        applicationContext.stopService(intent)
    }

    /* Settings */
    private var statsFPS = false
    private var statsLuaMode = 0 // ==0 disabled, >0 page number

    private var backgroundPlayMode = ""
    private var noUIPauseMode = ""

    private var shouldSavePosition = false

    private var autoRotationMode = ""

    private var controlsAtBottom = true
    private var showMediaTitle = false
    private var useTimeRemaining = false

    private var ignoreAudioFocus = false
    private var playlistExitWarning = true
    private var newIntentReplace = false

    private var smoothSeekGesture = false
    /* * */

    @SuppressLint("ClickableViewAccessibility")
    private fun initListeners() {
        with (binding) {
            prevBtn.setOnClickListener { playlistPrev() }
            nextBtn.setOnClickListener { playlistNext() }
            cycleAudioBtn.setOnClickListener { cycleAudio() }
            cycleSubsBtn.setOnClickListener { cycleSub() }
            playBtn.setOnClickListener { player.cyclePause() }
            cycleDecoderBtn.setOnClickListener { player.cycleHwdec() }
            cycleSpeedBtn.setOnClickListener { cycleSpeed() }
            topLockBtn.setOnClickListener { lockUI() }
            topPiPBtn.setOnClickListener { goIntoPiP() }
            topMenuBtn.setOnClickListener { openTopMenu() }
            unlockBtn.setOnClickListener { unlockUI() }
            playbackDurationTxt.setOnClickListener {
                useTimeRemaining = !useTimeRemaining
                updatePlaybackPos(psc.positionSec)
                updatePlaybackDuration(psc.durationSec)
            }

            cycleAudioBtn.setOnLongClickListener { pickAudio(); true }
            cycleSpeedBtn.setOnLongClickListener { pickSpeed(); true }
            cycleSubsBtn.setOnLongClickListener { pickSub(); true }
            prevBtn.setOnLongClickListener { openPlaylistMenu(pauseForDialog()); true }
            nextBtn.setOnLongClickListener { openPlaylistMenu(pauseForDialog()); true }
            cycleDecoderBtn.setOnLongClickListener { pickDecoder(); true }
            cycle3DBtn.setOnClickListener { toggle3D() }
            cycle3DBtn.setOnLongClickListener { pick3D(); true }

            playbackSeekbar.setOnSeekBarChangeListener(seekBarChangeListener)
        }

        player.setOnTouchListener { _, e ->
            if (lockedUI) false else gestures.onTouchEvent(e)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.outside) { _, windowInsets ->
            // guidance: https://medium.com/androiddevelopers/gesture-navigation-handling-visual-overlaps-4aed565c134c
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val insets2 = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            binding.outside.updateLayoutParams<MarginLayoutParams> {
                // avoid system bars and cutout
                leftMargin = Math.max(insets.left, insets2.left)
                topMargin = Math.max(insets.top, insets2.top)
                bottomMargin = Math.max(insets.bottom, insets2.bottom)
                rightMargin = Math.max(insets.right, insets2.right)
            }
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this) {
            onBackPressedImpl()
        }

        addOnPictureInPictureModeChangedListener { info ->
            onPiPModeChangedImpl(info.isInPictureInPictureMode)
        }
    }

    private var playbackHasStarted = false
    private var onloadCommands = mutableListOf<Array<String>>()

    // Activity lifetime

    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)

        // Do these here and not in MainActivity because mpv can be launched from a file browser
        Utils.copyAssets(this)
        BackgroundPlaybackService.createNotificationChannel(this)

        // Leia
        InitLeia(this)

        binding = PlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Init controls to be hidden and view fullscreen
        hideControls()

        // Initialize listeners for the player view
        initListeners()
        player.setSwapImages(false)

        gestures = TouchGestures(this)

        // set up initial UI state
        readSettings()
        applySubtitleDepth(subtitleDepth)
        applySubtitlePosition(subtitlePosition)
        applySubtitleSize(subtitleSize)
        onConfigurationChanged(resources.configuration)
        run {
            // edge-to-edge & immersive mode
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
            binding.topPiPBtn.visibility = View.GONE
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN))
            binding.topLockBtn.visibility = View.GONE

        if (showMediaTitle)
            binding.controlsTitleGroup.visibility = View.VISIBLE

        updateOrientation(true)

        // Parse the intent
        val filepath = parsePathFromIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            parseIntentExtras(intent.extras)
        }

        if (filepath == null) {
            Log.e(TAG, "No file given, exiting")
            showToast(getString(R.string.error_no_file))
            finishWithResult(RESULT_CANCELED)
            return
        }

        player.addObserver(this)
        player.initialize(filesDir.path, cacheDir.path)
        player.playFile(filepath)

        // Leia: 3D will be enabled once the file is loaded (see event handler)

        mediaSession = initMediaSession()
        updateMediaSession()
        BackgroundPlaybackService.mediaToken = mediaSession?.sessionToken

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioSessionId = audioManager!!.generateAudioSessionId()
        if (audioSessionId != AudioManager.ERROR)
            player.setAudioSessionId(audioSessionId)
        else
            Log.w(TAG, "AudioManager.generateAudioSessionId() returned error")

        volumeControlStream = STREAM_TYPE
    }

    private fun finishWithResult(code: Int, includeTimePos: Boolean = false) {
        // Refer to http://mpv-android.github.io/mpv-android/intent.html
        // FIXME: should track end-file events to accurately report OK vs CANCELED
        if (isFinishing) // only count first call
            return
        val result = Intent(RESULT_INTENT)
        result.data = if (intent.data?.scheme == "file") null else intent.data
        if (includeTimePos) {
            result.putExtra("position", psc.position.toInt())
            result.putExtra("duration", psc.duration.toInt())
        }
        setResult(code, result)
        finish()
    }

    override fun onDestroy() {
        Log.v(TAG, "Exiting.")

        stopImageSubtitleDecoder(resetNative = true, shutdownThread = true)
        Disable3D()
        MPVLib.setPropertyBoolean("sub-visibility", true)
        player.setStereoSubtitleEnabled(false)
        player.setStereoSubtitleBitmap(null)
        subtitleBitmap?.recycle()
        subtitleBitmap = null

        // Suppress any further callbacks
        activityIsForeground = false

        if (becomingNoisyReceiverRegistered) {
            unregisterReceiver(becomingNoisyReceiver)
            becomingNoisyReceiverRegistered = false
        }

        BackgroundPlaybackService.mediaToken = null
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null

        audioFocusRequest?.let {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager!!, it)
        }
        audioFocusRequest = null

        // take the background service with us
        stopServiceRunnable.run()

        player.removeObserver(this)
        player.destroy()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        Log.v(TAG, "onNewIntent($intent)")
        super.onNewIntent(intent)

        // Happens when mpv is still running (not necessarily playing) and the user selects a new
        // file to be played from another app
        val filepath = intent?.let { parsePathFromIntent(it) }
        if (filepath == null) {
            return
        }

        if (!activityIsForeground && didResumeBackgroundPlayback) {
            if (this.newIntentReplace) {
                MPVLib.command(arrayOf("loadfile", filepath, "replace"))
                showToast(getString(R.string.notice_file_play))
            } else {
                MPVLib.command(arrayOf("loadfile", filepath, "append"))
                showToast(getString(R.string.notice_file_appended))
            }
            moveTaskToBack(true)
        } else {
            MPVLib.command(arrayOf("loadfile", filepath))
        }
    }

    private fun updateAudioPresence() {
        val haveAudio = MPVLib.getPropertyBoolean("current-tracks/audio/selected")
        if (haveAudio == null) {
            // If we *don't know* if there's an active audio track then don't update to avoid
            // spurious UI changes. The property will become available again later.
            return
        }
        isPlayingAudio = (haveAudio && MPVLib.getPropertyBoolean("mute") != true)
    }

    private fun isPlayingAudioOnly(): Boolean {
        if (!isPlayingAudio)
            return false
        val image = MPVLib.getPropertyString("current-tracks/video/image")
        return image.isNullOrEmpty() || image == "yes"
    }

    private fun shouldBackground(): Boolean {
        if (isFinishing) // about to exit?
            return false
        return when (backgroundPlayMode) {
            "always" -> true
            "audio-only" -> isPlayingAudioOnly()
            else -> false // "never"
        }
    }

    override fun onPause() {
        player.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (isInMultiWindowMode || isInPictureInPictureMode) {
                Log.v(TAG, "Going into multi-window mode")
                super.onPause()
                return
            }
        }

        onPauseImpl()
    }

    private fun tryStartForegroundService(intent: Intent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, e)
                return false
            }
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
        return true
    }

    private fun onPauseImpl() {
        val fmt = MPVLib.getPropertyString("video-format")
        val shouldBackground = shouldBackground()
        if (shouldBackground && !fmt.isNullOrEmpty())
            BackgroundPlaybackService.thumbnail = MPVLib.grabThumbnail(THUMB_SIZE)
        else
            BackgroundPlaybackService.thumbnail = null
        // media session uses the same thumbnail
        updateMediaSession()

        activityIsForeground = false
        eventUiHandler.removeCallbacksAndMessages(null)
        if (isFinishing) {
            savePosition()
            // tell mpv to shut down so that any other property changes or such are ignored,
            // preventing useless busywork
            MPVLib.command(arrayOf("stop"))
        } else if (!shouldBackground) {
            player.paused = true
        }
        writeSettings()
        super.onPause()

        didResumeBackgroundPlayback = shouldBackground
        if (shouldBackground) {
            Log.v(TAG, "Resuming playback in background")
            stopServiceHandler.removeCallbacks(stopServiceRunnable)
            val serviceIntent = Intent(this, BackgroundPlaybackService::class.java)
            if (!tryStartForegroundService(serviceIntent)) {
                didResumeBackgroundPlayback = false
                player.paused = true
            }
        }
    }

    private fun readSettings() {
        // FIXME: settings should be in their own class completely
        val prefs = getDefaultSharedPreferences(applicationContext)
        val getString: (String, Int) -> String = { key, defaultRes ->
            prefs.getString(key, resources.getString(defaultRes))!!
        }

        gestures.syncSettings(prefs, resources)

        val statsMode = prefs.getString("stats_mode", "") ?: ""
        this.statsFPS = statsMode == "native_fps"
        this.statsLuaMode = if (statsMode.startsWith("lua"))
            statsMode.removePrefix("lua").toInt()
        else
            0
        this.backgroundPlayMode = getString("background_play", R.string.pref_background_play_default)
        this.noUIPauseMode = getString("no_ui_pause", R.string.pref_no_ui_pause_default)
        this.shouldSavePosition = prefs.getBoolean("save_position", false)
        if (this.autoRotationMode != "manual") // don't reset
            this.autoRotationMode = getString("auto_rotation", R.string.pref_auto_rotation_default)
        this.controlsAtBottom = prefs.getBoolean("bottom_controls", true)
        this.showMediaTitle = prefs.getBoolean("display_media_title", false)
        this.useTimeRemaining = prefs.getBoolean("use_time_remaining", false)
        this.subtitleDepth = prefs.getInt("subtitle_depth_3d", 0).coerceIn(-15, 15)
        this.subtitlePosition = prefs.getInt("subtitle_position_3d", 0).coerceIn(-15, 15)
        this.subtitleSize = prefs.getInt("subtitle_size_3d", 0).coerceIn(-15, 15)
        this.ignoreAudioFocus = prefs.getBoolean("ignore_audio_focus", false)
        this.playlistExitWarning = prefs.getBoolean("playlist_exit_warning", true)
        this.newIntentReplace = prefs.getBoolean("new_intent_replace", false)
        this.smoothSeekGesture = prefs.getBoolean("seek_gesture_smooth", false)
    }

    private fun writeSettings() {
        val prefs = getDefaultSharedPreferences(applicationContext)

        with (prefs.edit()) {
            putBoolean("use_time_remaining", useTimeRemaining)
            commit()
        }
    }

    override fun onStart() {
        super.onStart()
        activityIsStopped = false
    }

    override fun onStop() {
        super.onStop()
        activityIsStopped = true
    }

    override fun onResume() {
        player.onResume()
        // If we weren't actually in the background (e.g. multi window mode), don't reinitialize stuff
        if (activityIsForeground) {
            super.onResume()
            return
        }

        if (lockedUI) { // precaution
            Log.w(TAG, "resumed with locked UI, unlocking")
            unlockUI()
        }

        // Init controls to be hidden and view fullscreen
        hideControls()
        readSettings()

        activityIsForeground = true
        // stop background service with a delay
        stopServiceHandler.removeCallbacks(stopServiceRunnable)
        stopServiceHandler.postDelayed(stopServiceRunnable, 1000L)

        refreshUi()

        super.onResume()
    }

    private fun savePosition() {
        if (!shouldSavePosition)
            return
        if (MPVLib.getPropertyBoolean("eof-reached") ?: true) {
            Log.d(TAG, "player indicates EOF, not saving watch-later config")
            return
        }
        MPVLib.command(arrayOf("write-watch-later-config"))
    }

    /**
     * Requests or abandons audio focus and noisy receiver depending on the playback state.
     * @warning Call from event thread, not UI thread
     */
    private fun handleAudioFocus() {
        if ((psc.pause && !psc.cachePause) || !isPlayingAudio) {
            if (becomingNoisyReceiverRegistered)
                unregisterReceiver(becomingNoisyReceiver)
            becomingNoisyReceiverRegistered = false
            // TODO: could abandon audio focus after a timeout
        } else {
            if (!becomingNoisyReceiverRegistered)
                registerReceiver(
                    becomingNoisyReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                )
            becomingNoisyReceiverRegistered = true
            // (re-)request audio focus
            // Note that this will actually request focus every time the user unpauses, refer to discussion in #1066
            if (requestAudioFocus()) {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN, "request")
            } else {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS, "request")
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        val req = audioFocusRequest ?:
            with(AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)) {
            setAudioAttributes(with(AudioAttributesCompat.Builder()) {
                // N.B.: libmpv may use different values in ao_audiotrack, but here we always pretend to be music.
                setUsage(AudioAttributesCompat.USAGE_MEDIA)
                setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                build()
            })
            setOnAudioFocusChangeListener {
                onAudioFocusChange(it, "callback")
            }
            build()
        }
        val res = AudioManagerCompat.requestAudioFocus(manager, req)
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = req
            return true
        }
        return false
    }

    // This handles both "real" audio focus changes by the callbacks, which aren't
    // really used anymore after Android 12 (except for AUDIOFOCUS_LOSS),
    // as well as actions equivalent to a focus change that we make up ourselves.
    private fun onAudioFocusChange(type: Int, source: String) {
        Log.v(TAG, "Audio focus changed: $type ($source)")
        if (ignoreAudioFocus || isFinishing)
            return
        when (type) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // loss can occur in addition to ducking, so remember the old callback
                val oldRestore = audioFocusRestore
                val wasPlayerPaused = player.paused ?: false
                player.paused = true
                audioFocusRestore = {
                    oldRestore()
                    if (!wasPlayerPaused) player.paused = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                MPVLib.command(arrayOf("multiply", "volume", AUDIO_FOCUS_DUCKING.toString()))
                audioFocusRestore = {
                    val inv = 1f / AUDIO_FOCUS_DUCKING
                    MPVLib.command(arrayOf("multiply", "volume", inv.toString()))
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusRestore()
                audioFocusRestore = {}
            }
        }
    }

    // UI

    /** dpad navigation */
    private var btnSelected = -1

    private var mightWantToToggleControls = false

    /** true if we're actually outputting any audio (includes the mute state, but not pausing) */
    private var isPlayingAudio = false

    private var useAudioUI = false

    private var lockedUI = false

    private fun pauseForDialog(): StateRestoreCallback {
        val useKeepOpen = when (noUIPauseMode) {
            "always" -> true
            "audio-only" -> isPlayingAudioOnly()
            else -> false // "never"
        }
        if (useKeepOpen) {
            // don't pause but set keep-open so mpv doesn't exit while the user is doing stuff
            val oldValue = MPVLib.getPropertyString("keep-open")
            MPVLib.setPropertyBoolean("keep-open", true)
            return {
                oldValue?.also { MPVLib.setPropertyString("keep-open", it) }
            }
        }

        // Pause playback during UI dialogs
        val wasPlayerPaused = player.paused ?: true
        player.paused = true
        return {
            checkShouldToggle3D(mPrevDesiredBacklightModeState)
            if (!wasPlayerPaused)
                player.paused = false
        }
    }

    private fun updateStats() {
        if (!statsFPS)
            return
        binding.statsTextView.text = getString(R.string.ui_fps, player.estimatedVfFps)
    }

    private fun controlsShouldBeVisible(): Boolean {
        if (lockedUI)
            return false
        return useAudioUI || btnSelected != -1 || userIsOperatingSeekbar
    }

    /** Make controls visible, also controls the timeout until they fade. */
    private fun showControls() {
        if (lockedUI) {
            Log.w(TAG, "cannot show UI in locked mode")
            return
        }

        // remove all callbacks that were to be run for fading
        fadeHandler.removeCallbacks(fadeRunnable)
        binding.controls.animate().cancel()
        binding.topControls.animate().cancel()

        // reset controls alpha to be visible
        binding.controls.alpha = 1f
        binding.topControls.alpha = 1f

        if (binding.controls.visibility != View.VISIBLE) {
            binding.controls.visibility = View.VISIBLE
            binding.topControls.visibility = View.VISIBLE

            if (this.statsFPS) {
                updateStats()
                binding.statsTextView.visibility = View.VISIBLE
            }

            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.show(WindowInsetsCompat.Type.navigationBars())
        }

        // add a new callback to hide the controls once again
        if (!controlsShouldBeVisible())
            fadeHandler.postDelayed(fadeRunnable, CONTROLS_DISPLAY_TIMEOUT)
    }

    /** Hide controls instantly */
    fun hideControls() {
        if (controlsShouldBeVisible())
            return
        // use GONE here instead of INVISIBLE (which makes more sense) because of Android bug with surface views
        // see http://stackoverflow.com/a/12655713/2606891
        binding.controls.visibility = View.GONE
        binding.topControls.visibility = View.GONE
        binding.statsTextView.visibility = View.GONE

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Start fading out the controls */
    private fun hideControlsFade() {
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.post(fadeRunnable)
    }

    /**
     * Toggle visibility of controls (if allowed)
     * @return future visibility state
     */
    private fun toggleControls(): Boolean {
        if (lockedUI)
            return false
        if (controlsShouldBeVisible())
            return true
        return if (binding.controls.visibility == View.VISIBLE && !fadeRunnable.hasStarted) {
            hideControlsFade()
            false
        } else {
            showControls()
            true
        }
    }

    private fun showUnlockControls() {
        fadeHandler.removeCallbacks(fadeRunnable2)
        binding.unlockBtn.animate().setListener(null).cancel()

        binding.unlockBtn.alpha = 1f
        binding.unlockBtn.visibility = View.VISIBLE

        fadeHandler.postDelayed(fadeRunnable2, CONTROLS_DISPLAY_TIMEOUT)
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (lockedUI) {
            showUnlockControls()
            return super.dispatchKeyEvent(ev)
        }

        // try built-in event handler first, forward all other events to libmpv
        val handled = interceptDpad(ev) ||
                (ev.action == KeyEvent.ACTION_DOWN && interceptKeyDown(ev)) ||
                player.onKey(ev)
        if (handled) {
            return true
        }
        return super.dispatchKeyEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        if (lockedUI)
            return super.dispatchGenericMotionEvent(ev)

        if (ev != null && ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (player.onPointerEvent(ev))
                return true
            // keep controls visible when mouse moves
            if (ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE)
                showControls()
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (lockedUI) {
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_DOWN)
                showUnlockControls()
            return super.dispatchTouchEvent(ev)
        }

        if (super.dispatchTouchEvent(ev)) {
            // reset delay if the event has been handled
            // ideally we'd want to know if the event was delivered to controls, but we can't
            if (binding.controls.visibility == View.VISIBLE && !fadeRunnable.hasStarted)
                showControls()
            if (ev.action == MotionEvent.ACTION_UP)
                return true
        }
        if (ev.action == MotionEvent.ACTION_DOWN)
            mightWantToToggleControls = true
        if (ev.action == MotionEvent.ACTION_UP && mightWantToToggleControls) {
            toggleControls()
        }
        return true
    }

    /**
     * Returns views eligible for dpad button navigation
     */
    private fun dpadButtons(): Sequence<View> {
        val groups = arrayOf(binding.controlsButtonGroup, binding.topControls)
        return sequence {
            for (g in groups) {
                for (i in 0 until g.childCount) {
                    val view = g.getChildAt(i)
                    if (view.isEnabled && view.isVisible && view.isFocusable)
                        yield(view)
                }
            }
        }
    }

    private fun interceptDpad(ev: KeyEvent): Boolean {
        if (btnSelected == -1) { // UP and DOWN are always grabbed and overridden
            when (ev.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (ev.action == KeyEvent.ACTION_DOWN) { // activate dpad navigation
                        btnSelected = 0
                        updateSelectedDpadButton()
                        showControls()
                    }
                    return true
                }
            }
            return false
        }

        // this runs when dpad navigation is active:
        when (ev.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (ev.action == KeyEvent.ACTION_DOWN) { // deactivate dpad navigation
                    btnSelected = -1
                    updateSelectedDpadButton()
                    hideControlsFade()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    btnSelected = (btnSelected + 1) % dpadButtons().count()
                    updateSelectedDpadButton()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    val count = dpadButtons().count()
                    btnSelected = (count + btnSelected - 1) % count
                    updateSelectedDpadButton()
                }
                return true
            }
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (ev.action == KeyEvent.ACTION_UP) {
                    val view = dpadButtons().elementAtOrNull(btnSelected)
                    // 500ms appears to be the standard
                    if (ev.eventTime - ev.downTime > 500L)
                        view?.performLongClick()
                    else
                        view?.performClick()
                }
                return true
            }
        }
        return false
    }

    private fun updateSelectedDpadButton() {
        val colorFocused = ContextCompat.getColor(this, R.color.tint_btn_bg_focused)
        val colorNoFocus = ContextCompat.getColor(this, R.color.tint_btn_bg_nofocus)

        dpadButtons().forEachIndexed { i, child ->
            if (i == btnSelected)
                child.setBackgroundColor(colorFocused)
            else
                child.setBackgroundColor(colorNoFocus)
        }
    }

    private fun interceptKeyDown(event: KeyEvent): Boolean {
        // intercept some keys to provide functionality "native" to
        // mpv-android even if libmpv already implements these
        var unhandled = 0

        when (event.unicodeChar.toChar()) {
            // (overrides a default binding)
            'j' -> cycleSub()
            '#' -> cycleAudio()

            else -> unhandled++
        }
        // Note: dpad center is bound according to how Android TV apps should generally behave,
        // see <https://developer.android.com/docs/quality-guidelines/tv-app-quality>.
        // Due to implementation inconsistencies enter and numpad enter need to perform the same
        // function (issue #963).
        when (event.keyCode) {
            // (no default binding)
            KeyEvent.KEYCODE_CAPTIONS -> cycleSub()
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> cycleAudio()
            KeyEvent.KEYCODE_INFO -> toggleControls()
            KeyEvent.KEYCODE_MENU -> openTopMenu()
            KeyEvent.KEYCODE_GUIDE -> openTopMenu()
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> player.cyclePause()

            // (overrides a default binding)
            KeyEvent.KEYCODE_ENTER -> player.cyclePause()

            else -> unhandled++
        }

        return unhandled < 2
    }

    private fun onBackPressedImpl() {
        Disable3D()

        if (lockedUI)
            return showUnlockControls()

        val notYetPlayed = psc.playlistCount - psc.playlistPos - 1
        if (notYetPlayed <= 0 || !playlistExitWarning) {
            finishWithResult(RESULT_OK, true)
            return
        }

        val restore = pauseForDialog()
        with (AlertDialog.Builder(this)) {
            setMessage(getString(R.string.exit_warning_playlist, notYetPlayed))
            setPositiveButton(R.string.dialog_yes) { dialog, _ ->
                dialog.dismiss()
                finishWithResult(RESULT_OK, true)
            }
            setNegativeButton(R.string.dialog_no) { dialog, _ ->
                dialog.dismiss()
                restore()
            }
            create().show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = windowManager.currentWindowMetrics
            gestures.setMetrics(wm.bounds.width().toFloat(), wm.bounds.height().toFloat())
        } else @Suppress("DEPRECATION") {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            gestures.setMetrics(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
        }

        // Adjust control margins
        binding.controls.updateLayoutParams<MarginLayoutParams> {
            bottomMargin = if (!controlsAtBottom) {
                Utils.convertDp(this@MPVActivity, 60f)
            } else {
                0
            }
            leftMargin = if (!controlsAtBottom) {
                Utils.convertDp(this@MPVActivity, if (isLandscape) 60f else 24f)
            } else {
                0
            }
            rightMargin = leftMargin
        }
    }

    private fun onPiPModeChangedImpl(state: Boolean) {
        Log.v(TAG, "onPiPModeChanged($state)")
        if (state) {
            lockedUI = true
            hideControls()
            return
        }

        unlockUI()
        // For whatever stupid reason Android provides no good detection for when PiP is exited
        // so we have to do this shit <https://stackoverflow.com/questions/43174507/#answer-56127742>
        // If we don't exit the activity here it will stick around and not be retrievable from the
        // recents screen, or react to onNewIntent().
        if (activityIsStopped) {
            // Note: On Android 12 or older there's another bug with this: the result will not
            // be delivered to the calling activity and is instead instantly returned the next
            // time, which makes it looks like the file picker is broken.
            finishWithResult(RESULT_OK, true)
        }
    }

    private fun playlistPrev() = MPVLib.command(arrayOf("playlist-prev"))
    private fun playlistNext() = MPVLib.command(arrayOf("playlist-next"))

    private fun showToast(msg: String, cancel: Boolean = false) {
        if (cancel)
            toast?.cancel()
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).apply {
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 0)
            show()
        }
    }

    // Intent/Uri parsing

    private fun parsePathFromIntent(intent: Intent): String? {
        fun safeResolveUri(u: Uri?): String? {
            return if (u != null && u.isHierarchical && !u.isRelative)
                resolveUri(u)
            else null
        }

        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                // Normal file open or URL view
                intent.data?.let { resolveUri(it) }
            }

            Intent.ACTION_SEND -> {
                // Handle single shared file or text link
                var parsed = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (parsed == null) {
                    parsed = intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                        Uri.parse(it.trim())
                    }
                }

                safeResolveUri(parsed)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                // Multiple shared files
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (!uris.isNullOrEmpty()) {
                    val paths = uris.mapNotNull { uri ->
                        safeResolveUri(uri)
                    }
                    if (paths.size == 1) {
                        return paths[0]
                    } else if (!paths.isEmpty()) {
                        // Use a memory playlist
                        val memoryUri = "memory://#EXTM3U\n${paths.joinToString("\n")}\n"
                        Log.v(TAG, "Created memory playlist URI (${paths.size})")
                        return memoryUri
                    }
                }
                return null
            }

            else -> {
                // Custom intent from MainScreenFragment
                intent.getStringExtra("filepath")
            }
        }
    }

    private fun resolveUri(data: Uri): String? {
        val filepath = when (data.scheme) {
            "file" -> data.path
            "content" -> translateContentUri(data)
            // mpv supports data URIs but needs data:// to pass it through correctly
            "data" -> "data://${data.schemeSpecificPart}"
            "http", "https", "rtmp", "rtmps", "rtp", "rtsp", "mms", "mmst", "mmsh",
            "tcp", "udp", "lavf", "ftp"
            -> data.toString()
            else -> null
        }

        if (filepath == null)
            Log.e(TAG, "unknown scheme: ${data.scheme}")
        return filepath
    }

    private fun translateContentUri(uri: Uri): String {
        val resolver = applicationContext.contentResolver
        Log.v(TAG, "Resolving content URI: $uri")
        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                // See if we can skip the indirection and read the real file directly
                val path = Utils.findRealPath(pfd.fd)
                if (path != null) {
                    Log.v(TAG, "Found real file path: $path")
                    return path
                }
            }
        } catch(e: Exception) {
            Log.e(TAG, "Failed to open content fd: $e")
        }

        // Otherwise, just let mpv open the content URI directly via ffmpeg
        return uri.toString()
    }

    private fun parseIntentExtras(extras: Bundle?) {
        onloadCommands.clear()
        if (extras == null)
            return

        fun pushOption(key: String, value: String) {
            onloadCommands.add(arrayOf("set", "file-local-options/${key}", value))
        }

        // Refer to http://mpv-android.github.io/mpv-android/intent.html
        // Note: these only apply to the first file, it's not clear what the semantics for a
        // playlist should be.

        if (extras.getByte("decode_mode") == 2.toByte())
            pushOption("hwdec", "no")
        if (extras.containsKey("subs")) {
            val subList = Utils.getParcelableArray<Uri>(extras, "subs")
            val subsToEnable = Utils.getParcelableArray<Uri>(extras, "subs.enable")

            for (suburi in subList) {
                val subfile = resolveUri(suburi) ?: continue
                val flag = if (subsToEnable.any { it == suburi }) "select" else "auto"

                Log.v(TAG, "Adding subtitles from intent extras: $subfile")
                onloadCommands.add(arrayOf("sub-add", subfile, flag))
            }
        }
        extras.getInt("position", 0).let {
            if (it > 0)
                pushOption("start", "${it / 1000f}")
        }
        extras.getString("title", "").let {
            if (!it.isNullOrEmpty())
                pushOption("force-media-title", it)
        }
        // TODO: `headers` would be good, maybe `tls_verify`
    }

    // UI (Part 2)

    data class TrackData(val trackId: Int, val trackType: String)
    private fun trackSwitchNotification(f: () -> TrackData) {
        val (track_id, track_type) = f()
        val trackPrefix = when (track_type) {
            "audio" -> getString(R.string.track_audio)
            "sub"   -> getString(R.string.track_subs)
            "video" -> "Video"
            else    -> "???"
        }

        val msg = if (track_id == -1) {
            "$trackPrefix ${getString(R.string.track_off)}"
        } else {
            val trackName = player.tracks[track_type]?.firstOrNull{ it.mpvId == track_id }?.name ?: "???"
            "$trackPrefix $trackName"
        }
        showToast(msg, true)
    }

    private fun cycleAudio() = trackSwitchNotification {
        player.cycleAudio(); TrackData(player.aid, "audio")
    }
    private fun cycleSub() = trackSwitchNotification {
        player.cycleSub()
        updateStereoSubtitleMode()
        TrackData(player.sid, "sub")
    }

    private fun selectTrack(type: String, get: () -> Int, set: (Int) -> Unit) {
        val tracks = player.tracks.getValue(type)
        val selectedMpvId = get()
        val selectedIndex = tracks.indexOfFirst { it.mpvId == selectedMpvId }
        val restore = pauseForDialog()

        with (AlertDialog.Builder(this)) {
            setSingleChoiceItems(tracks.map { it.name }.toTypedArray(), selectedIndex) { dialog, item ->
                val trackId = tracks[item].mpvId

                set(trackId)
                dialog.dismiss()
                trackSwitchNotification { TrackData(trackId, type) }
            }
            setOnDismissListener { restore() }
            create().show()
        }
    }

    private fun pickAudio() = selectTrack("audio", { player.aid }, { player.aid = it })

    private fun pickSub() {
        val restore = pauseForDialog()
        val impl = SubTrackDialog(player)
        lateinit var dialog: AlertDialog
        impl.listener = { it, secondary ->
            if (secondary)
                player.secondarySid = it.mpvId
            else
                player.sid = it.mpvId
            updateStereoSubtitleMode()
            dialog.dismiss()
            trackSwitchNotification { TrackData(it.mpvId, SubTrackDialog.TRACK_TYPE) }
        }

        dialog = with (AlertDialog.Builder(this)) {
            val inflater = LayoutInflater.from(context)
            setView(impl.buildView(inflater))
            setOnDismissListener { restore() }
            create()
        }
        dialog.show()
    }

    private fun openPlaylistMenu(restore: StateRestoreCallback) {
        val impl = PlaylistDialog(player)
        lateinit var dialog: AlertDialog

        impl.listeners = object : PlaylistDialog.Listeners {
            private fun openFilePicker(skip: Int) {
                openFilePickerFor(RCODE_LOAD_FILE, "", skip) { result, data ->
                    if (result == RESULT_OK) {
                        val path = data!!.getStringExtra("path")!!
                        MPVLib.command(arrayOf("loadfile", path, "append"))
                        impl.refresh()
                    }
                }
            }
            override fun pickFile() = openFilePicker(FilePickerActivity.FILE_PICKER)

            override fun openUrl() {
                val helper = Utils.OpenUrlDialog(this@MPVActivity)
                with (helper) {
                    builder.setPositiveButton(R.string.dialog_ok) { _, _ ->
                        MPVLib.command(arrayOf("loadfile", helper.text, "append"))
                        impl.refresh()
                    }
                    builder.setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
                    create().show()
                }
            }

            override fun onItemPicked(item: MPVView.PlaylistItem) {
                MPVLib.setPropertyInt("playlist-pos", item.index)
                dialog.dismiss()
            }
        }

        dialog = with (AlertDialog.Builder(this)) {
            val inflater = LayoutInflater.from(context)
            setView(impl.buildView(inflater))
            setOnDismissListener { restore() }
            create()
        }
        dialog.show()
    }

    private fun pickDecoder() {
        val restore = pauseForDialog()

        val items = mutableListOf(
            Pair("HW (mediacodec-copy)", "mediacodec-copy"),
            Pair("SW", "no")
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            items.add(0, Pair("HW+ (mediacodec)", "mediacodec"))
        val hwdecActive = player.hwdecActive
        val selectedIndex = items.indexOfFirst { it.second == hwdecActive }
        with (AlertDialog.Builder(this)) {
            setSingleChoiceItems(items.map { it.first }.toTypedArray(), selectedIndex ) { dialog, idx ->
                MPVLib.setPropertyString("hwdec", items[idx].second)
                dialog.dismiss()
            }
            setOnDismissListener { restore() }
            create().show()
        }
    }

    private fun cycleSpeed() {
        player.cycleSpeed()
    }

    private fun pickSpeed() {
        // TODO: replace this with SliderPickerDialog
        val picker = SpeedPickerDialog()

        val restore = pauseForDialog()
        genericPickerDialog(picker, R.string.title_speed_dialog, "speed") {
            restore()
        }
    }

    private fun goIntoPiP() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
            return
        updatePiPParams(true)
        enterPictureInPictureMode()
    }

    private fun lockUI() {
        lockedUI = true
        hideControlsFade()
    }

    private fun unlockUI() {
        binding.unlockBtn.visibility = View.GONE
        lockedUI = false
        showControls()
    }

    data class MenuItem(@IdRes val idRes: Int, val handler: () -> Boolean)
    private fun genericMenu(
            @LayoutRes layoutRes: Int, buttons: List<MenuItem>, hiddenButtons: Set<Int>,
            restoreState: StateRestoreCallback) {
        lateinit var dialog: AlertDialog

        val builder = AlertDialog.Builder(this)
        val dialogView = LayoutInflater.from(builder.context).inflate(layoutRes, null)

        for (button in buttons) {
            val buttonView = dialogView.findViewById<Button>(button.idRes)
            buttonView.setOnClickListener {
                val ret = button.handler()
                if (ret) // restore state immediately
                    restoreState()
                dialog.dismiss()
            }
        }

        hiddenButtons.forEach { dialogView.findViewById<View>(it).isVisible = false }

        if (Utils.visibleChildren(dialogView) == 0) {
            Log.w(TAG, "Not showing menu because it would be empty")
            restoreState()
            return
        }

        Utils.handleInsetsAsPadding(dialogView)

        with (builder) {
            setView(dialogView)
            setOnCancelListener { restoreState() }
            dialog = create()
        }
        dialog.show()
    }

    private fun openTopMenu() {
        val restoreState = pauseForDialog()

        fun addExternalThing(cmd: String, result: Int, data: Intent?) {
            if (result != RESULT_OK)
                return
            // file picker may return a content URI or a bare file path
            val path = data!!.getStringExtra("path")!!
            val path2 = if (path.startsWith("content://"))
                translateContentUri(Uri.parse(path))
            else
                path
            MPVLib.command(arrayOf(cmd, path2, "cached"))
        }

        /******/
        val hiddenButtons = mutableSetOf<Int>()
        val buttons: MutableList<MenuItem> = mutableListOf(
                MenuItem(R.id.audioBtn) {
                    openFilePickerFor(RCODE_EXTERNAL_AUDIO, R.string.open_external_audio) { result, data ->
                        addExternalThing("audio-add", result, data)
                        restoreState()
                    }; false
                },
                MenuItem(R.id.subBtn) {
                    openFilePickerFor(RCODE_EXTERNAL_SUB, R.string.open_external_sub) { result, data ->
                        addExternalThing("sub-add", result, data)
                        restoreState()
                    }; false
                },
                MenuItem(R.id.playlistBtn) {
                    openPlaylistMenu(restoreState); false
                },
                MenuItem(R.id.backgroundBtn) {
                    // restoring state may (un)pause so do that first
                    restoreState()
                    backgroundPlayMode = "always"
                    player.paused = false
                    moveTaskToBack(true)
                    false
                },
                MenuItem(R.id.chapterBtn) {
                    val chapters = player.loadChapters()
                    if (chapters.isEmpty())
                        return@MenuItem true
                    val chapterArray = chapters.map {
                        val timecode = Utils.prettyTime(it.time.roundToInt())
                        if (!it.title.isNullOrEmpty())
                            getString(R.string.ui_chapter, it.title, timecode)
                        else
                            getString(R.string.ui_chapter_fallback, it.index+1, timecode)
                    }.toTypedArray()
                    val selectedIndex = MPVLib.getPropertyInt("chapter") ?: 0
                    with (AlertDialog.Builder(this)) {
                        setSingleChoiceItems(chapterArray, selectedIndex) { dialog, item ->
                            MPVLib.setPropertyInt("chapter", chapters[item].index)
                            dialog.dismiss()
                        }
                        setOnDismissListener { restoreState() }
                        create().show()
                    }; false
                },
                MenuItem(R.id.chapterPrev) {
                    MPVLib.command(arrayOf("add", "chapter", "-1")); true
                },
                MenuItem(R.id.chapterNext) {
                    MPVLib.command(arrayOf("add", "chapter", "1")); true
                },
                MenuItem(R.id.advancedBtn) { openAdvancedMenu(restoreState); false },
                MenuItem(R.id.orientationBtn) {
                    autoRotationMode = "manual"
                    cycleOrientation()
                    true
                }
        )

        if (!isPlayingAudio)
            hiddenButtons.add(R.id.backgroundBtn)
        if ((MPVLib.getPropertyInt("chapter-list/count") ?: 0) == 0)
            hiddenButtons.add(R.id.rowChapter)
        /******/

        genericMenu(R.layout.dialog_top_menu, buttons, hiddenButtons, restoreState)
    }

    private fun genericPickerDialog(
        picker: PickerDialog, @StringRes titleRes: Int, property: String,
        restoreState: StateRestoreCallback
    ) {
        val dialog = with(AlertDialog.Builder(this)) {
            setTitle(titleRes)
            val inflater = LayoutInflater.from(context)
            setView(picker.buildView(inflater))
            setPositiveButton(R.string.dialog_ok) { _, _ ->
                picker.number?.let {
                    if (picker.isInteger())
                        MPVLib.setPropertyInt(property, it.toInt())
                    else
                        MPVLib.setPropertyDouble(property, it)
                }
            }
            setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
            setOnDismissListener { restoreState() }
            create()
        }

        picker.number = MPVLib.getPropertyDouble(property)
        dialog.show()
    }

    private fun openAdvancedMenu(restoreState: StateRestoreCallback) {
        /******/
        val hiddenButtons = mutableSetOf<Int>()
        val buttons: MutableList<MenuItem> = mutableListOf(
                MenuItem(R.id.subSeekPrev) {
                    MPVLib.command(arrayOf("sub-seek", "-1")); true
                },
                MenuItem(R.id.subSeekNext) {
                    MPVLib.command(arrayOf("sub-seek", "1")); true
                },
                MenuItem(R.id.statsBtn) {
                    MPVLib.command(arrayOf("script-binding", "stats/display-stats-toggle")); true
                },
                MenuItem(R.id.aspectBtn) {
                    val ratios = resources.getStringArray(R.array.aspect_ratios)
                    with (AlertDialog.Builder(this)) {
                        setItems(R.array.aspect_ratio_names) { dialog, item ->
                            if (ratios[item] == "panscan") {
                                MPVLib.setPropertyString("video-aspect-override", "-1")
                                MPVLib.setPropertyDouble("panscan", 1.0)
                            } else {
                                MPVLib.setPropertyString("video-aspect-override", ratios[item])
                                MPVLib.setPropertyDouble("panscan", 0.0)
                            }
                            dialog.dismiss()
                        }
                        setOnDismissListener { restoreState() }
                        create().show()
                    }; false
                },
        )

        val statsButtons = arrayOf(R.id.statsBtn1, R.id.statsBtn2, R.id.statsBtn3)
        for (i in 1..3) {
            buttons.add(MenuItem(statsButtons[i-1]) {
                MPVLib.command(arrayOf("script-binding", "stats/display-page-$i")); true
            })
        }

        // contrast, brightness and others get a -100 to 100 slider
        val basicIds = arrayOf(R.id.contrastBtn, R.id.brightnessBtn, R.id.gammaBtn, R.id.saturationBtn)
        val basicProps = arrayOf("contrast", "brightness", "gamma", "saturation")
        val basicTitles = arrayOf(R.string.contrast, R.string.video_brightness, R.string.gamma, R.string.saturation)
        basicIds.forEachIndexed { index, id ->
            buttons.add(MenuItem(id) {
                val slider = SliderPickerDialog(-100.0, 100.0, 1, R.string.format_fixed_number)
                genericPickerDialog(slider, basicTitles[index], basicProps[index], restoreState)
                false
            })
        }

        // audio / sub delay get a decimal picker
        buttons.add(MenuItem(R.id.audioDelayBtn) {
            val picker = DecimalPickerDialog(-600.0, 600.0)
            genericPickerDialog(picker, R.string.audio_delay, "audio-delay", restoreState)
            false
        })
        buttons.add(MenuItem(R.id.subDelayBtn) {
            val picker = SubDelayDialog(-600.0, 600.0)
            val dialog = with(AlertDialog.Builder(this)) {
                setTitle(R.string.sub_delay)
                val inflater = LayoutInflater.from(context)
                setView(picker.buildView(inflater))
                setPositiveButton(R.string.dialog_ok) { _, _ ->
                    picker.delay1?.let { player.subDelay = it }
                    picker.delay2?.let { player.secondarySubDelay = it }
                }
                setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
                setOnDismissListener { restoreState() }
                create()
            }

            picker.delay1 = player.subDelay ?: 0.0
            picker.delay2 = if (player.secondarySid != -1) player.secondarySubDelay else null
            dialog.show()
            false
        })

        if (player.vid == -1)
            hiddenButtons.addAll(arrayOf(R.id.rowVideo1, R.id.rowVideo2, R.id.aspectBtn))
        if (player.aid == -1 || player.vid == -1)
            hiddenButtons.add(R.id.audioDelayBtn)
        if (player.sid == -1)
            hiddenButtons.addAll(arrayOf(R.id.subDelayBtn, R.id.rowSubSeek))
        /******/

        genericMenu(R.layout.dialog_advanced_menu, buttons, hiddenButtons, restoreState)
    }

    private fun cycleOrientation() {
        requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private var activityResultCallbacks: MutableMap<Int, ActivityResultCallback> = mutableMapOf()
    private fun openFilePickerFor(requestCode: Int, title: String, skip: Int?, callback: ActivityResultCallback) {
        val intent = Intent(this, FilePickerActivity::class.java)
        intent.putExtra("title", title)
        intent.putExtra("allow_document", true)
        skip?.let { intent.putExtra("skip", it) }
        // start file picker at directory of current file
        val path = MPVLib.getPropertyString("path") ?: ""
        if (path.startsWith('/'))
            intent.putExtra("default_path", File(path).parent)

        activityResultCallbacks[requestCode] = callback
        startActivityForResult(intent, requestCode)
    }
    private fun openFilePickerFor(requestCode: Int, @StringRes titleRes: Int, callback: ActivityResultCallback) {
        openFilePickerFor(requestCode, getString(titleRes), null, callback)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        activityResultCallbacks.remove(requestCode)?.invoke(resultCode, data)
    }

    private fun refreshUi() {
        // forces update of entire UI, used when resuming the activity
        updatePlaybackStatus(psc.pause)
        updatePlaybackPos(psc.positionSec)
        updatePlaybackDuration(psc.durationSec)
        updateAudioUI()
        updateOrientation()
        updateMetadataDisplay()
        updateDecoderButton()
        updateSpeedButton()
        updatePlaylistButtons()
        player.loadTracks()
    }

    private fun updateAudioUI() {
        val audioButtons = arrayOf(R.id.prevBtn, R.id.cycleAudioBtn, R.id.playBtn,
                R.id.cycleSpeedBtn, R.id.nextBtn)
        val videoButtons = arrayOf(R.id.cycleAudioBtn, R.id.cycleSubsBtn, R.id.cycle3DBtn, R.id.playBtn,
                R.id.cycleDecoderBtn, R.id.cycleSpeedBtn)

        val shouldUseAudioUI = isPlayingAudioOnly()
        if (shouldUseAudioUI == useAudioUI)
            return
        useAudioUI = shouldUseAudioUI
        Log.v(TAG, "Audio UI: $useAudioUI")

        val seekbarGroup = binding.controlsSeekbarGroup
        val buttonGroup = binding.controlsButtonGroup

        if (useAudioUI) {
            // Move prev/next file from seekbar group to buttons group
            Utils.viewGroupMove(seekbarGroup, R.id.prevBtn, buttonGroup, 0)
            Utils.viewGroupMove(seekbarGroup, R.id.nextBtn, buttonGroup, -1)

            // Change button layout of buttons group
            Utils.viewGroupReorder(buttonGroup, audioButtons)

            // Show song title and more metadata
            binding.controlsTitleGroup.visibility = View.VISIBLE
            Utils.viewGroupReorder(binding.controlsTitleGroup, arrayOf(R.id.titleTextView, R.id.minorTitleTextView))
            updateMetadataDisplay()

            showControls()
        } else {
            Utils.viewGroupMove(buttonGroup, R.id.prevBtn, seekbarGroup, 0)
            Utils.viewGroupMove(buttonGroup, R.id.nextBtn, seekbarGroup, -1)

            Utils.viewGroupReorder(buttonGroup, videoButtons)

            // Show title only depending on settings
            if (showMediaTitle) {
                binding.controlsTitleGroup.visibility = View.VISIBLE
                Utils.viewGroupReorder(binding.controlsTitleGroup, arrayOf(R.id.fullTitleTextView))
                updateMetadataDisplay()
            } else {
                binding.controlsTitleGroup.visibility = View.GONE
            }

            hideControls() // do NOT use fade runnable
        }

        // Visibility might have changed, so update
        updatePlaylistButtons()
    }

    private fun updateMetadataDisplay() {
        if (!useAudioUI) {
            if (showMediaTitle)
                binding.fullTitleTextView.text = psc.meta.formatTitle()
        } else {
            binding.titleTextView.text = psc.meta.formatTitle()
            binding.minorTitleTextView.text = psc.meta.formatArtistAlbum()
        }
    }

    private fun updatePlaybackPos(position: Int) {
        binding.playbackPositionTxt.text = Utils.prettyTime(position)
        if (useTimeRemaining) {
            val diff = psc.durationSec - position
            binding.playbackDurationTxt.text = if (diff <= 0)
                "-00:00"
            else
                Utils.prettyTime(-diff, true)
        }
        if (!userIsOperatingSeekbar)
            binding.playbackSeekbar.progress = position * SEEK_BAR_PRECISION

        // Note: do NOT add other update functions here just because this is called every second.
        // Use property observation instead.
        updateStats()
    }

    private fun updatePlaybackDuration(duration: Int) {
        if (!useTimeRemaining)
            binding.playbackDurationTxt.text = Utils.prettyTime(duration)
        if (!userIsOperatingSeekbar)
            binding.playbackSeekbar.max = duration * SEEK_BAR_PRECISION
    }

    private fun updatePlaybackStatus(paused: Boolean) {
        val r = if (paused) R.drawable.ic_play_arrow_black_24dp else R.drawable.ic_pause_black_24dp
        binding.playBtn.setImageResource(r)

        updatePiPParams()
        if (paused) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateDecoderButton() {
        binding.cycleDecoderBtn.text = when (player.hwdecActive) {
            "mediacodec" -> "HW+"
            "no" -> "SW"
            else -> "HW"
        }
    }

    private fun updateSpeedButton() {
        binding.cycleSpeedBtn.text = getString(R.string.ui_speed, psc.speed)
    }

    private fun updatePlaylistButtons() {
        val plCount = psc.playlistCount
        val plPos = psc.playlistPos

        if (!useAudioUI && plCount == 1) {
            // use View.GONE so the buttons won't take up any space
            binding.prevBtn.visibility = View.GONE
            binding.nextBtn.visibility = View.GONE
            return
        }
        binding.prevBtn.visibility = View.VISIBLE
        binding.nextBtn.visibility = View.VISIBLE

        val g = ContextCompat.getColor(this, R.color.tint_disabled)
        val w = ContextCompat.getColor(this, R.color.tint_normal)
        binding.prevBtn.imageTintList = ColorStateList.valueOf(if (plPos == 0) g else w)
        binding.nextBtn.imageTintList = ColorStateList.valueOf(if (plPos == plCount-1) g else w)
    }

    private fun updateOrientation(initial: Boolean = false) {
        // screen orientation is fixed (Android TV)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT))
            return

        // 3D (Leia) playback only works in landscape on this hardware. Lock to
        // landscape whenever it's active, overriding both the user's rotation
        // setting and the raw-frame-aspect logic below — a packed stereo format
        // like half-TAB can have a raw frame aspect under 1 (e.g. 1920x2400)
        // even though each eye's actual content is landscape.
        if (isSbs3DActive()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            return
        }

        if (autoRotationMode != "auto") {
            if (!initial)
                return // don't reset at runtime
            requestedOrientation = when (autoRotationMode) {
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        if (initial || player.vid == -1)
            return

        val ratio = player.getVideoAspect()?.toFloat() ?: 0f
        if (ratio == 0f || ratio in (1f / ASPECT_RATIO_MIN) .. ASPECT_RATIO_MIN) {
            // video is square, let Android do what it wants
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            return
        }
        requestedOrientation = if (ratio > 1f)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    @RequiresApi(26)
    private fun makeRemoteAction(@DrawableRes icon: Int, @StringRes title: Int, intentAction: String): RemoteAction {
        val intent = NotificationButtonReceiver.createIntent(this, intentAction)
        return RemoteAction(Icon.createWithResource(this, icon), getString(title), "", intent)
    }

    /**
     * Update Picture-in-picture parameters. Will only run if in PiP mode unless
     * `force` is set.
     */
    private fun updatePiPParams(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return
        if (!isInPictureInPictureMode && !force)
            return

        val playPauseAction = if (psc.pause)
            makeRemoteAction(R.drawable.ic_play_arrow_black_24dp, R.string.btn_play, "PLAY_PAUSE")
        else
            makeRemoteAction(R.drawable.ic_pause_black_24dp, R.string.btn_pause, "PLAY_PAUSE")
        val actions = mutableListOf<RemoteAction>()
        if (psc.playlistCount > 1) {
            actions.add(makeRemoteAction(
                R.drawable.ic_skip_previous_black_24dp, R.string.dialog_prev, "ACTION_PREV"
            ))
            actions.add(playPauseAction)
            actions.add(makeRemoteAction(
                R.drawable.ic_skip_next_black_24dp, R.string.dialog_next, "ACTION_NEXT"
            ))
        } else {
            actions.add(playPauseAction)
        }

        val params = with(PictureInPictureParams.Builder()) {
            val aspect = player.getVideoAspect() ?: 0.0
            setAspectRatio(Rational(aspect.times(10000).toInt(), 10000))
            setActions(actions)
        }
        try {
            setPictureInPictureParams(params.build())
        } catch (e: IllegalArgumentException) {
            // Android has some limits of what the aspect ratio can be
            params.setAspectRatio(Rational(1, 1))
            setPictureInPictureParams(params.build())
        }
    }

    // Media Session handling

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPause() {
            player.paused = true
        }
        override fun onPlay() {
            player.paused = false
        }
        override fun onSeekTo(pos: Long) {
            player.timePos = (pos / 1000.0)
        }
        override fun onSkipToNext() = playlistNext()
        override fun onSkipToPrevious() = playlistPrev()
        override fun onSetRepeatMode(repeatMode: Int) {
            MPVLib.setPropertyString("loop-playlist",
                if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ALL) "inf" else "no")
            MPVLib.setPropertyString("loop-file",
                if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE) "inf" else "no")
        }
        override fun onSetShuffleMode(shuffleMode: Int) {
            player.changeShuffle(false, shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL)
        }
    }

    private fun initMediaSession(): MediaSessionCompat {
        /*
            https://developer.android.com/guide/topics/media-apps/working-with-a-media-session
            https://developer.android.com/guide/topics/media-apps/audio-app/mediasession-callbacks
            https://developer.android.com/reference/android/support/v4/media/session/MediaSessionCompat
         */
        val session = MediaSessionCompat(this, TAG)
        session.setFlags(0)
        session.setCallback(mediaSessionCallback)
        return session
    }

    private fun updateMediaSession() {
        synchronized (psc) {
            mediaSession?.let { psc.write(it) }
        }
    }

    // mpv events

    private fun eventPropertyUi(property: String, dummy: Any?, metaUpdated: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "track-list" -> {
                player.loadTracks()
                updateStereoSubtitleMode()
            }
            "current-tracks/audio/selected", "current-tracks/video/image" -> {
                updateAudioUI()
                updateStereoSubtitleMode()
            }
            "hwdec-current" -> updateDecoderButton()
        }
        if (metaUpdated)
            updateMetadataDisplay()
    }

    private fun eventPropertyUi(property: String, value: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "pause" -> updatePlaybackStatus(value)
            "mute" -> { // indirectly from updateAudioPresence()
                updateAudioUI()
            }
        }
    }

    private fun eventPropertyUi(property: String, value: Long) {
        if (!activityIsForeground) return
        when (property) {
            "time-pos" -> updatePlaybackPos(psc.positionSec)
            "playlist-pos", "playlist-count" -> updatePlaylistButtons()
        }
    }

    private fun eventPropertyUi(property: String, value: Double) {
        if (!activityIsForeground) return
        when (property) {
            "time-pos/full" -> onTimePositionChanged(value)
            "duration/full" -> updatePlaybackDuration(psc.durationSec)
            "video-params/aspect", "video-params/rotate" -> {
                updateOrientation()
                updatePiPParams()
                updateLeiaContentAspect()
            }
        }
    }

    private fun eventPropertyUi(property: String, value: String, metaUpdated: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "speed" -> updateSpeedButton()
            "sub-text" -> updateStereoSubtitleText(value)
        }
        if (metaUpdated)
            updateMetadataDisplay()
    }

    private fun eventUi(eventId: Int) {
        if (!activityIsForeground) return
        // empty
    }

    override fun eventProperty(property: String) {
        val metaUpdated = psc.update(property)
        if (metaUpdated)
            updateMediaSession()
        if (property == "loop-file" || property == "loop-playlist") {
            mediaSession?.setRepeatMode(when (player.getRepeat()) {
                2 -> PlaybackStateCompat.REPEAT_MODE_ONE
                1 -> PlaybackStateCompat.REPEAT_MODE_ALL
                else -> PlaybackStateCompat.REPEAT_MODE_NONE
            })
        } else if (property == "current-tracks/audio/selected") {
            updateAudioPresence()
        }

        if (property == "pause" || property == "current-tracks/audio/selected")
            handleAudioFocus()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, null, metaUpdated) }
    }

    override fun eventProperty(property: String, value: Boolean) {
        val metaUpdated = psc.update(property, value)
        if (metaUpdated)
            updateMediaSession()
        if (property == "shuffle") {
            mediaSession?.setShuffleMode(if (value)
                PlaybackStateCompat.SHUFFLE_MODE_ALL
            else
                PlaybackStateCompat.SHUFFLE_MODE_NONE)
        } else if (property == "mute") {
            updateAudioPresence()
        }

        if (metaUpdated || property == "mute")
            handleAudioFocus()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: Long) {
        if (psc.update(property, value))
            updateMediaSession()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: Double) {
        if (psc.update(property, value))
            updateMediaSession()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: String) {
        val metaUpdated = psc.update(property, value)
        if (metaUpdated)
            updateMediaSession()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value, metaUpdated) }
    }

    override fun event(eventId: Int) {
        if (eventId == MpvEvent.MPV_EVENT_END_FILE) {
            psc.eof()
            updateMediaSession()
        }

        if (eventId == MpvEvent.MPV_EVENT_SHUTDOWN)
            finishWithResult(if (playbackHasStarted) RESULT_OK else RESULT_CANCELED)

        if (eventId == MpvEvent.MPV_EVENT_START_FILE) {
            hasGuessedNetworkSubtitles = false
            
            val cmds = onloadCommands.toTypedArray()
            onloadCommands.clear()
            for (c in cmds)
                MPVLib.command(c)
            if (this.statsLuaMode > 0 && !playbackHasStarted) {
                MPVLib.command(arrayOf("script-binding", "stats/display-page-${this.statsLuaMode}-toggle"))
            }

            playbackHasStarted = true

            // Detect 3D format for the new file; disable 3D if previous file had it
            val debugTag = "Leia3DFilenameDebug"
            var resolvedFilename = MPVLib.getPropertyString("filename") ?: ""
            val path = MPVLib.getPropertyString("path") ?: ""

            if (path.startsWith("content://")) {
                try {
                    val uri = Uri.parse(path)
                    val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
                    val cursor = applicationContext.contentResolver.query(uri, projection, null, null, null)
                    
                    if (cursor != null) {
                        if (cursor.moveToFirst()) {
                            val displayName = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                            //Log.d(debugTag, "Resolved DISPLAY_NAME: $displayName")
                            if (!displayName.isNullOrEmpty()) {
                                resolvedFilename = displayName
                            }
                        } else {
                            Log.d(debugTag, "Cursor is empty! moveToFirst() returned false.")
                        }
                        cursor.close()
                    } else {
                        Log.d(debugTag, "Cursor is NULL! Query failed.")
                    }
                } catch (e: Exception) {
                    Log.e(debugTag, "Exception during query: $e")
                }
            }

            //Log.d(debugTag, "Final filename passed to detectLeiaFormat: $resolvedFilename")
            val newFormat = detectLeiaFormat(resolvedFilename)
            loadImageSubtitleSettingsForFile(path, newFormat)
            userForced3DOffForCurrentFile = false
            imageSubtitleDecoderFailedKey = null
            if (leiaEnabled && newFormat == LeiaFormat.NONE) {
                eventUiHandler.post {
                    leiaEnabled = false
                    player.setMode(0)
                    Disable3D()
                    update3DButton()
                    updateStereoSubtitleMode()
                }
            } else if (leiaEnabled) {
                eventUiHandler.post {
                    leiaEnabled = false
                    update3DButton()
                    updateStereoSubtitleMode()
                }
            }
            currentLeiaFormat = newFormat
        }

        if (eventId == MpvEvent.MPV_EVENT_PLAYBACK_RESTART && !leiaEnabled) {
            // Enable 3D only once mpv is actively rendering frames, and only when
            // the filename indicates a known 3D format.
            if (currentLeiaFormat != LeiaFormat.NONE && !userForced3DOffForCurrentFile) {
                eventUiHandler.post {
                    apply3DMode(currentLeiaFormat)
                    update3DButton()
                    updateStereoSubtitleMode()
                }
            }
        }

        if (eventId == MpvEvent.MPV_EVENT_FILE_LOADED) {
            // Run in a background thread so HTTP timeouts don't block mpv's video decoding
            Thread {
                val currentPath = MPVLib.getPropertyString("path") ?: ""
                guessNetworkSubtitles(currentPath)
            }.start()
        }

        if (!activityIsForeground) return
        eventUiHandler.post { eventUi(eventId) }
    }

    // Gesture handler

    private var initialSeek = 0f
    private var initialBright = 0f
    private var initialVolume = 0
    private var maxVolume = 0
    /** 0 = initial, 1 = paused, 2 = was already paused */
    private var pausedForSeek = 0

    private fun fadeGestureText() {
        fadeHandler.removeCallbacks(fadeRunnable3)
        binding.gestureTextView.visibility = View.VISIBLE

        fadeHandler.postDelayed(fadeRunnable3, 500L)
    }

    override fun onPropertyChange(p: PropertyChange, diff: Float) {
        val gestureTextView = binding.gestureTextView
        when (p) {
            /* Drag gestures */
            PropertyChange.Init -> {
                mightWantToToggleControls = false

                initialSeek = (psc.position / 1000f)
                initialBright = Utils.getScreenBrightness(this) ?: 0.5f
                with (audioManager!!) {
                    initialVolume = getStreamVolume(STREAM_TYPE)
                    maxVolume = if (isVolumeFixed)
                        0
                    else
                        getStreamMaxVolume(STREAM_TYPE)
                }
                if (!isPlayingAudio)
                    maxVolume = 0 // disallow volume gesture if no audio
                pausedForSeek = 0

                fadeHandler.removeCallbacks(fadeRunnable3)
                gestureTextView.visibility = View.VISIBLE
                gestureTextView.text = ""
            }
            PropertyChange.Seek -> {
                // disable seeking when duration is unknown
                val duration = (psc.duration / 1000f)
                if (duration == 0f || initialSeek < 0)
                    return
                if (smoothSeekGesture && pausedForSeek == 0) {
                    pausedForSeek = if (psc.pause) 2 else 1
                    if (pausedForSeek == 1)
                        player.paused = true
                }

                val newPosExact = (initialSeek + diff).coerceIn(0f, duration)
                val newPos = newPosExact.roundToInt()
                val newDiff = (newPosExact - initialSeek).roundToInt()
                if (smoothSeekGesture) {
                    player.timePos = newPosExact.toDouble() // (exact seek)
                } else {
                    // seek faster than assigning to timePos but less precise
                    MPVLib.command(arrayOf("seek", "$newPosExact", "absolute+keyframes"))
                }
                // Note: don't call updatePlaybackPos() here because mpv will seek a timestamp
                // actually present in the file, and not the exact one we specified.

                val posText = Utils.prettyTime(newPos)
                val diffText = Utils.prettyTime(newDiff, true)
                gestureTextView.text = getString(R.string.ui_seek_distance, posText, diffText)
            }
            PropertyChange.Volume -> {
                if (maxVolume == 0)
                    return
                val newVolume = (initialVolume + (diff * maxVolume).toInt()).coerceIn(0, maxVolume)
                val newVolumePercent = 100 * newVolume / maxVolume
                audioManager!!.setStreamVolume(STREAM_TYPE, newVolume, 0)

                gestureTextView.text = getString(R.string.ui_volume, newVolumePercent)
            }
            PropertyChange.Bright -> {
                val lp = window.attributes
                val newBright = (initialBright + diff).coerceIn(0f, 1f)
                lp.screenBrightness = newBright
                window.attributes = lp

                gestureTextView.text = getString(R.string.ui_brightness, (newBright * 100).roundToInt())
            }
            PropertyChange.Finalize -> {
                if (pausedForSeek == 1)
                    player.paused = false
                gestureTextView.visibility = View.GONE
            }

            /* Tap gestures */
            PropertyChange.SeekFixed -> {
                val seekTime = diff * 10f
                val newPos = psc.positionSec + seekTime.toInt() // only for display
                MPVLib.command(arrayOf("seek", seekTime.toString(), "relative"))

                val diffText = Utils.prettyTime(seekTime.toInt(), true)
                gestureTextView.text = getString(R.string.ui_seek_distance, Utils.prettyTime(newPos), diffText)
                fadeGestureText()
            }
            PropertyChange.PlayPause -> player.cyclePause()
            PropertyChange.Custom -> {
                val keycode = 0x10002 + diff.toInt()
                MPVLib.command(arrayOf("keypress", "0x%x".format(keycode)))
            }
        }
    }

    companion object {
        private const val TAG = "mpv"
        // how long should controls be displayed on screen (ms)
        private const val CONTROLS_DISPLAY_TIMEOUT = 1500L
        // how long controls fade to disappear (ms)
        private const val CONTROLS_FADE_DURATION = 500L
        // resolution (px) of the thumbnail displayed with playback notification
        private const val THUMB_SIZE = 384
        // smallest aspect ratio that is considered non-square
        private const val ASPECT_RATIO_MIN = 1.2f // covers 5:4 and up
        // fraction to which audio volume is ducked on loss of audio focus
        private const val AUDIO_FOCUS_DUCKING = 0.5f
        // request codes for invoking other activities
        private const val RCODE_EXTERNAL_AUDIO = 1000
        private const val RCODE_EXTERNAL_SUB = 1001
        private const val RCODE_LOAD_FILE = 1002
        // action of result intent
        private const val RESULT_INTENT = "is.xyz.mpv.ntm3d.MPVActivity.result"
        // stream type used with AudioManager
        private const val STREAM_TYPE = AudioManager.STREAM_MUSIC
        // precision used by seekbar (1/s)
        private const val SEEK_BAR_PRECISION = 2
        // SharedPreferences file used to save image subtitle settings per opened file
        private const val IMAGE_SUBTITLE_PER_FILE_PREFS = "image_subtitle_per_file"
    }

    private fun InitLeia(context: Context) {
        val initArgs = LeiaSDK.InitArgs()
        initArgs.platform.context = context.applicationContext
        initArgs.enableFaceTracking = true
        initArgs.requiresFaceTrackingPermissionCheck = false
        sdk = LeiaSDK.createSDK(initArgs)
    }

    fun Enable3D() {
        sdk.enableBacklight(true)
        sdk.enableFaceTracking(true)
    }

    fun Disable3D() {
        sdk.enableBacklight(false)
        sdk.enableFaceTracking(false)
    }

    private fun update3DButton() {
        val color = if (leiaEnabled)
            android.graphics.Color.parseColor("#00FF00")
        else
            android.graphics.Color.WHITE
        binding.cycle3DBtn.setTextColor(color)
    }

    private fun applyLeiaDisplayProperties(format: LeiaFormat, is3DActive: Boolean) {
        // Stereo composite frames (SBS/TAB) must fill the mpv render buffer edge-to-edge,
        // with no internal aspect-driven letterboxing, so the eye split always lands at
        // the exact halfway point regardless of the buffer's own aspect ratio. The real
        // 16:9-in-16:10 letterboxing for the final image is applied once, after eye
        // splitting, in LeiaTextureRenderer.
        //
        // But mpv computes native subtitle/OSD positioning from the exact same
        // aspect-driven letterbox math as the video's own display rect, gated
        // by keepaspect — so keepaspect=no also zeroes out the margins mpv
        // uses to scale/position native image subtitles (PGS/VobSub/etc),
        // causing them to stretch to fill the whole edge-to-edge buffer
        // instead of being positioned correctly. osd-keepaspect (a small
        // vendored mpv patch, see buildscripts/patches/mpv-osd-keepaspect.patch)
        // decouples the two: it computes what those letterbox margins would
        // have been, for subtitle positioning only, without touching the
        // actual video destination rect.
        val stereoActive = is3DActive && format != LeiaFormat.NONE
        MPVLib.setPropertyString("keepaspect", if (stereoActive) "no" else "yes")

        // Only SBS needs this — TAB's subtitle alignment breaks if it's on.
        val osdKeepAspect = when (format) {
            LeiaFormat.HALF_SBS, LeiaFormat.FULL_SBS -> true
            else -> false
        }
        MPVLib.setPropertyBoolean("osd-keepaspect", if (stereoActive && imageSubtitle3D) osdKeepAspect else false)

        // The imageSubsScaleX/Y sliders directly hold the value applied to
        // image-subs-scale-x/y (stored in tenths, e.g. 5 = 0.5x). They're only
        // usable when imageSubtitle3D is off, same as this whole stretch step.
        // A file with no saved slider value defaults to the per-format
        // auto-correct value (see defaultImageSubsScaleXTenths/YTenths).
        val scaleX = imageSubsScaleX / 10.0
        val scaleY = imageSubsScaleY / 10.0
        MPVLib.setOptionString("image-subs-scale-x", if (stereoActive && !imageSubtitle3D) scaleX.toString() else "1.0")
        MPVLib.setOptionString("image-subs-scale-y", if (stereoActive && !imageSubtitle3D) scaleY.toString() else "1.0")

        MPVLib.setPropertyString("video-aspect-override", "no")
    }

    private fun isSbs3DActive(): Boolean {
        return leiaEnabled
    }

    /**
     * Recomputes the per-eye content aspect ratio from the raw decoded frame's
     * aspect ratio and the current stereo packing format, and pushes it to the
     * Leia renderer so non-16:9 sources are letterboxed correctly instead of
     * being stretched to an assumed 16:9.
     */
    private fun updateLeiaContentAspect() {
        val rawAspect = player.getVideoAspect()?.toFloat() ?: return
        if (rawAspect <= 0f) return
        val perEyeAspect = when (currentLeiaFormat) {
            // Half formats anamorphically squeeze each eye to fit inside the frame,
            // so the packed frame's own aspect ratio already equals the per-eye
            // aspect ratio once unsqueezed for display.
            LeiaFormat.HALF_SBS, LeiaFormat.HALF_TAB -> rawAspect
            // Full-SBS packs two unsquashed eyes side by side: each eye is half the frame's width.
            LeiaFormat.FULL_SBS -> rawAspect / 2f
            // Full-TAB packs two unsquashed eyes top/bottom: each eye is half the frame's height.
            LeiaFormat.FULL_TAB -> rawAspect * 2f
            // 2D passthrough: each eye shows the full, unpacked frame.
            LeiaFormat.NONE -> rawAspect
        }
        player.setContentAspect(perEyeAspect)
    }

    private fun sanitizeSubText(raw: String): String {
        return raw
            .replace("\\N", "\n")
            .replace(Regex("\\{[^}]*\\}"), "")
            .trim()
    }

    private fun applySubtitleDepth(depth: Int) {
        val clamped = depth.coerceIn(-15, 15)
        val maxStereoOffset = 0.012f
        val normalizedDepth = -(clamped / 15f) * maxStereoOffset
        // Positive depth = pop out; negative = behind screen.
        player.setStereoSubtitleDepth(normalizedDepth)
    }

    private fun applySubtitlePosition(position: Int) {
        val clamped = position.coerceIn(-15, 15)
        // Positive position = move up on screen.
        // Map range -15..15 to ±0.4 (40% of screen height) so subtitles can reach into letterbox.
        val normalizedPosition = (clamped / 15f) * 0.4f
        player.setStereoSubtitlePosition(normalizedPosition)
    }

    private fun applySubtitleSize(size: Int) {
        val clamped = size.coerceIn(-15, 15)
        // 0 = 1x scale, +15 = 2x, -15 = 0.5x (exponential feel via linear mapping)
        val normalizedScale = 1f + clamped * (1f / 15f)
        player.setStereoSubtitleScale(normalizedScale)
    }

    private fun createSubtitleBitmap(text: String): Bitmap {
        val width = if (binding.player.width > 0) binding.player.width else resources.displayMetrics.widthPixels
        val height = if (binding.player.height > 0) binding.player.height else resources.displayMetrics.heightPixels
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val textWidth = (width * 0.9f).toInt().coerceAtLeast(1)
        // Render at 2x the final pixel width for clean downsampling — more than enough
        // since the output canvas is already the full hardware buffer resolution (2560px).
        val ss = 2
        // Size text at ~4.5% of the render width so it scales naturally with resolution
        // rather than being tied to display density (which reflects the small physical screen).
        val textSizePx = width * 0.045f * ss
        val strokeWidth = textSizePx * 0.12f  // 12% of text size for the outline

        // Outline paint — drawn first, slightly larger, pure black
        val outlinePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.BLACK
            textSize = textSizePx
            textAlign = Paint.Align.LEFT
            isLinearText = true
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        // Fill paint — drawn on top, white
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.DITHER_FLAG).apply {
            color = Color.WHITE
            textSize = textSizePx
            textAlign = Paint.Align.LEFT
            isLinearText = true
            style = Paint.Style.FILL
        }

        val layerWidth = textWidth * ss
        val outlineLayout = StaticLayout.Builder.obtain(text, 0, text.length, outlinePaint, layerWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
        val fillLayout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, layerWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

        val textLayer = Bitmap.createBitmap(layerWidth, outlineLayout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val textCanvas = Canvas(textLayer)
        outlineLayout.draw(textCanvas)  // black outline first
        fillLayout.draw(textCanvas)     // white fill on top

        // Calculate single-line baseline position
        val bottomMargin = (72f * 0.65f * resources.displayMetrics.density).roundToInt()
        val singleLineHeight = (textSizePx / ss).roundToInt()
        val singleLineBaseline = height - bottomMargin - singleLineHeight

        // Center multi-line text around single-line baseline, but never let the
        // block run past the top or bottom of the canvas — for 3+ line subtitles
        // the block is taller than a single line's margin allows, and letting
        // Canvas silently crop whatever falls outside [0, height] was cutting
        // off (and visually smearing, once sampled by the shader) the outermost
        // line. There's plenty of vertical screen space; just use it.
        // Keep a small margin off the true edges too: content sitting flush
        // against row 0/height gets sampled by the shader's zoom at the exact
        // texture boundary, which GL_CLAMP_TO_EDGE + bilinear filtering can
        // stretch into a thin vertical streak.
        val left = ((width - textWidth) / 2f)
        val dstHeight = (textLayer.height / ss.toFloat()).roundToInt().coerceAtLeast(1)
        val idealTop = singleLineBaseline - dstHeight / 2 + singleLineHeight / 2
        val edgeMargin = (singleLineHeight * 0.25f).roundToInt().coerceAtLeast(1)
        val maxTop = (height - dstHeight - edgeMargin).coerceAtLeast(edgeMargin)
        val top = idealTop.coerceIn(edgeMargin, maxTop)
        val dst = android.graphics.RectF(left, top.toFloat(), left + textWidth, (top + dstHeight).toFloat())
        canvas.drawBitmap(textLayer, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        textLayer.recycle()
        return bitmap
    }

    private fun updateStereoSubtitleText(rawText: String) {
        currentSubText = rawText
        if (!stereoSubtitleModeEnabled || isImageSubtitleTrackSelected())
            return
        val text = sanitizeSubText(rawText)
        if (text.isBlank()) {
            subtitleBitmap?.recycle()
            subtitleBitmap = null
            player.setStereoSubtitleBitmap(null)
            return
        }
        subtitleBitmap?.recycle()
        subtitleBitmap = createSubtitleBitmap(text)
        player.setStereoSubtitleBitmap(subtitleBitmap)
    }

    private fun isImageSubtitleTrackSelected(): Boolean {
        if (player.sid == -1)
            return false
        val codec = player.tracks["sub"]?.firstOrNull { it.mpvId == player.sid }?.codec?.lowercase() ?: return false
        return codec.contains("pgs") || codec.contains("dvd_subtitle") ||
               codec.contains("dvb_subtitle") || codec.contains("vobsub") ||
               codec.contains("xsub")
    }

    private fun getSelectedSubtitleTrack(): MPVView.Track? {
        if (player.sid == -1)
            return null
        return player.tracks["sub"]?.firstOrNull { it.mpvId == player.sid }
    }

    private fun ensureImageSubtitleInitThread() {
        if (imageSubtitleInitThread?.isAlive == true && imageSubtitleInitHandler != null)
            return
        imageSubtitleInitThread = HandlerThread("image_subtitle_init").apply { start() }
        imageSubtitleInitHandler = Handler(imageSubtitleInitThread!!.looper)
    }

    private fun decoderPathCandidates(): List<String> {
        val candidates = linkedSetOf<String>()
        val path = MPVLib.getPropertyString("path")
        val filename = MPVLib.getPropertyString("filename")
        if (!path.isNullOrBlank()) {
            candidates.add(path)
            if (path.startsWith("file://")) {
                candidates.add(path.removePrefix("file://"))
            }
        }
        if (!filename.isNullOrBlank()) {
            candidates.add(filename)
            if (filename.startsWith("file://")) {
                candidates.add(filename.removePrefix("file://"))
            }
        }
        return candidates.toList()
    }

    private fun startImageSubtitleDecoderInit(
        pathCandidates: List<String>,
        ffIndex: Int,
        subtitleOrder: Int,
        codecHint: String?
    ) {
        ensureImageSubtitleInitThread()
        imageSubtitleDecoderGeneration += 1
        val generation = imageSubtitleDecoderGeneration
        val requestKey = "${pathCandidates.joinToString("|")}#$ffIndex#$subtitleOrder#${codecHint ?: ""}"
        imageSubtitleDecoderState = ImageSubtitleDecoderState.INITIALIZING
        imageSubtitleDecoderReady = false
        imageSubtitleDecoderPath = null
        imageSubtitleDecoderFfIndex = ffIndex
        imageSubtitleDecoderRequestKey = requestKey
        Log.d(TAG, "LeiaImageSub: about to post, thread=${imageSubtitleInitThread?.name} isAlive=${imageSubtitleInitThread?.isAlive} handler=$imageSubtitleInitHandler looper=${imageSubtitleInitHandler?.looper}")
        val postedOk = imageSubtitleInitHandler?.post {
            Log.d(TAG, "LeiaImageSub: background init task STARTED, ${pathCandidates.size} candidate(s): $pathCandidates")
            var ok = false
            var selectedPath: String? = null
            for (candidate in pathCandidates) {
                Log.d(TAG, "LeiaImageSub: trying candidate: $candidate")
                if (candidate.startsWith("content://")) {
                    // ffmpeg's avformat_open_input doesn't understand content:// URIs
                    // (that's mpv's own Android integration, not something this app's
                    // bundled ffmpeg has a protocol handler for). Open the URI via the
                    // ContentResolver instead and hand the native decoder a /proc/self/fd
                    // path to the already-open descriptor, which regular file I/O can read.
                    try {
                        applicationContext.contentResolver.openFileDescriptor(Uri.parse(candidate), "r")?.use { pfd ->
                            val fdPath = "/proc/self/fd/${pfd.fd}"
                            Log.d(TAG, "LeiaImageSub: calling native initImageSubtitleDecoder with fd path $fdPath")
                            if (MPVLib.initImageSubtitleDecoder(fdPath, ffIndex, subtitleOrder, codecHint)) {
                                ok = true
                                selectedPath = candidate
                            }
                            Log.d(TAG, "LeiaImageSub: native call returned, ok=$ok")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open content URI for image subtitle decode: $e")
                    }
                } else {
                    Log.d(TAG, "LeiaImageSub: calling native initImageSubtitleDecoder with path $candidate")
                    if (MPVLib.initImageSubtitleDecoder(candidate, ffIndex, subtitleOrder, codecHint)) {
                        ok = true
                        selectedPath = candidate
                    }
                    Log.d(TAG, "LeiaImageSub: native call returned, ok=$ok")
                }
                if (ok) break
            }
            Log.d(TAG, "LeiaImageSub: background init task FINISHED ok=$ok selectedPath=$selectedPath")
            eventUiHandler.post {
                Log.d(TAG, "LeiaImageSub: decoder init callback ok=$ok selectedPath=$selectedPath generation=$generation/${imageSubtitleDecoderGeneration} stereoSubtitleModeEnabled=$stereoSubtitleModeEnabled")
                if (generation != imageSubtitleDecoderGeneration || !stereoSubtitleModeEnabled || !isImageSubtitleTrackSelected()) {
                    Log.w(TAG, "LeiaImageSub: discarding stale/irrelevant decoder init result")
                    if (ok)
                        MPVLib.releaseImageSubtitleDecoder()
                    return@post
                }
                imageSubtitleDecoderReady = ok
                imageSubtitleDecoderState = if (ok) ImageSubtitleDecoderState.READY else ImageSubtitleDecoderState.FAILED
                if (ok) {
                    imageSubtitleDecoderPath = selectedPath
                    imageSubtitleDecoderFailedKey = null
                    applySubtitleDepth(subtitleDepth)
                    updateImageSubtitleFrame(player.timePos ?: 0.0)
                } else {
                    Log.e(TAG, "LeiaImageSub: native decoder init FAILED for all candidates: $pathCandidates")
                    imageSubtitleDecoderFailedKey = requestKey
                    subtitleBitmap?.recycle()
                    subtitleBitmap = null
                    player.setStereoSubtitleBitmap(null)
                }
            }
        }
        Log.d(TAG, "LeiaImageSub: post() returned $postedOk")
    }

    private fun stopImageSubtitleDecoder(resetNative: Boolean, shutdownThread: Boolean = false) {
        imageSubtitleDecoderGeneration += 1
        imageSubtitleDecoderReady = false
        imageSubtitleDecoderState = ImageSubtitleDecoderState.IDLE
        imageSubtitleDecoderPath = null
        imageSubtitleDecoderFfIndex = null
        imageSubtitleDecoderRequestKey = null
        subtitleBitmap?.recycle()
        subtitleBitmap = null
        player.setStereoSubtitleBitmap(null)
        if (shutdownThread) {
            if (resetNative) {
                MPVLib.releaseImageSubtitleDecoder()
            }
            imageSubtitleInitHandler?.removeCallbacksAndMessages(null)
            imageSubtitleInitThread?.quitSafely()
            imageSubtitleInitThread = null
            imageSubtitleInitHandler = null
        } else if (resetNative) {
            imageSubtitleInitHandler?.post { MPVLib.releaseImageSubtitleDecoder() } ?: MPVLib.releaseImageSubtitleDecoder()
        }
    }

    private fun updateImageSubtitleFrame(timePosSec: Double) {
        if (!stereoSubtitleModeEnabled || !isImageSubtitleTrackSelected() ||
            !imageSubtitleDecoderReady || imageSubtitleDecoderState != ImageSubtitleDecoderState.READY) {
            Log.d(TAG, "LeiaImageSub: updateImageSubtitleFrame gate check failed stereoSubtitleModeEnabled=$stereoSubtitleModeEnabled imageSubtitleDecoderReady=$imageSubtitleDecoderReady decoderState=$imageSubtitleDecoderState")
            return
        }
        val width = if (binding.player.width > 0) binding.player.width else resources.displayMetrics.widthPixels
        val height = if (binding.player.height > 0) binding.player.height else resources.displayMetrics.heightPixels
        val bmp = MPVLib.renderImageSubtitleAt(timePosSec, width, height)
        if (bmp == null) {
            Log.d(TAG, "LeiaImageSub: renderImageSubtitleAt returned null at t=$timePosSec (${width}x$height) - likely no active event at this timestamp, or unchanged since last call")
            return
        }
        if (!loggedFirstImageSubtitleFrame) {
            Log.d(TAG, "LeiaImageSub: first bitmap rendered successfully, ${bmp.width}x${bmp.height} at t=$timePosSec")
            loggedFirstImageSubtitleFrame = true
        }
        subtitleBitmap?.recycle()
        subtitleBitmap = bmp
        player.setStereoSubtitleBitmap(subtitleBitmap)
    }

    private fun persistSubtitleDepth() {
        getDefaultSharedPreferences(applicationContext).edit()
            .putInt("subtitle_depth_3d", subtitleDepth)
            .apply()
    }

    private fun persistSubtitlePosition() {
        getDefaultSharedPreferences(applicationContext).edit()
            .putInt("subtitle_position_3d", subtitlePosition)
            .apply()
    }

    private fun persistSubtitleSize() {
        getDefaultSharedPreferences(applicationContext).edit()
            .putInt("subtitle_size_3d", subtitleSize)
            .apply()
    }

    // Image subtitle settings are saved per opened file (like the last playback
    // position), rather than as a single global default, so each file remembers
    // its own values. A new/never-seen file falls back to the hardcoded defaults
    // below - for the two stretch sliders, that default is the same per-format
    // auto-correct value mpv previously computed on its own (0.5x for the axis
    // that's squeezed by SBS/TAB packing, 1.0x otherwise).
    private fun imageSubtitlePerFileKey(path: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(path.toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun defaultImageSubsScaleXTenths(format: LeiaFormat): Int = when (format) {
        LeiaFormat.HALF_SBS, LeiaFormat.FULL_SBS -> 5
        else -> 10
    }

    private fun defaultImageSubsScaleYTenths(format: LeiaFormat): Int = when (format) {
        LeiaFormat.HALF_TAB, LeiaFormat.FULL_TAB -> 5
        LeiaFormat.HALF_SBS -> 15
        else -> 10
    }

    private fun loadImageSubtitleSettingsForFile(path: String, format: LeiaFormat) {
        currentFilePath = path
        if (path.isEmpty()) return
        val prefs = getSharedPreferences(IMAGE_SUBTITLE_PER_FILE_PREFS, MODE_PRIVATE)
        val key = imageSubtitlePerFileKey(path)
        imageSubtitle3D = prefs.getBoolean("${key}_3d", false)
        imageSubtitleScale = prefs.getInt("${key}_scale", 0).coerceIn(-15, 15)
        imageSubtitlePosition = prefs.getInt("${key}_position", 100).coerceIn(50, 150)
        imageSubsScaleX = prefs.getInt("${key}_scale_x", defaultImageSubsScaleXTenths(format)).coerceIn(1, 30)
        imageSubsScaleY = prefs.getInt("${key}_scale_y", defaultImageSubsScaleYTenths(format)).coerceIn(1, 30)
        
        // Load and apply swap eyes
        swapEyes = prefs.getBoolean("${key}_swap_eyes", false)
        player.setSwapImages(swapEyes)
    }

    private fun persistImageSubtitle3D() {
        if (currentFilePath.isEmpty()) return
        getSharedPreferences(IMAGE_SUBTITLE_PER_FILE_PREFS, MODE_PRIVATE).edit()
            .putBoolean("${imageSubtitlePerFileKey(currentFilePath)}_3d", imageSubtitle3D)
            .apply()
    }

    private fun persistImageSubtitleScale() {
        if (currentFilePath.isEmpty()) return
        getSharedPreferences(IMAGE_SUBTITLE_PER_FILE_PREFS, MODE_PRIVATE).edit()
            .putInt("${imageSubtitlePerFileKey(currentFilePath)}_scale", imageSubtitleScale)
            .apply()
    }

    private fun persistImageSubtitlePosition() {
        if (currentFilePath.isEmpty()) return
        getSharedPreferences(IMAGE_SUBTITLE_PER_FILE_PREFS, MODE_PRIVATE).edit()
            .putInt("${imageSubtitlePerFileKey(currentFilePath)}_position", imageSubtitlePosition)
            .apply()
    }

    private fun persistImageSubsScaleX() {
        if (currentFilePath.isEmpty()) return
        getSharedPreferences(IMAGE_SUBTITLE_PER_FILE_PREFS, MODE_PRIVATE).edit()
            .putInt("${imageSubtitlePerFileKey(currentFilePath)}_scale_x", imageSubsScaleX)
            .apply()
    }

    private fun persistImageSubsScaleY() {
        if (currentFilePath.isEmpty()) return
        getSharedPreferences(IMAGE_SUBTITLE_PER_FILE_PREFS, MODE_PRIVATE).edit()
            .putInt("${imageSubtitlePerFileKey(currentFilePath)}_scale_y", imageSubsScaleY)
            .apply()
    }

    private fun persistImageSubtitleSwapEyes() {
        if (currentFilePath.isEmpty()) return
        getSharedPreferences(IMAGE_SUBTITLE_PER_FILE_PREFS, MODE_PRIVATE).edit()
        .putBoolean("${imageSubtitlePerFileKey(currentFilePath)}_swap_eyes", swapEyes)
        .apply()
    }

    // Which mpv vf=format:stereo-in=<x> value matches the current 3D packing,
    // for mono image subtitles that need to be duplicated into both eyes.
    private fun currentStereoInFilterValue(): String {
        return when (currentLeiaFormat) {
            LeiaFormat.HALF_SBS, LeiaFormat.FULL_SBS -> "sbs2l"
            LeiaFormat.HALF_TAB, LeiaFormat.FULL_TAB -> "ab2l"
            LeiaFormat.NONE -> "no"
        }
    }

    // Pre-authored stereo image subtitles already contain both eyes and must
    // not be duplicated again (stereo-in=none). Mono ones need duplicating
    // to match the current SBS/TAB packing, same as apply3DMode() does for
    // the video itself.
    //
    // Uses the "vf set" runtime command rather than setOptionString: the
    // latter doesn't reliably replace an already-active filter chain during
    // playback (the previous sbs2l/ab2l filter stayed active even after
    // switching back to stereo-in=none), whereas "vf set" is mpv's
    // documented mechanism for atomically replacing the whole filter chain.
    private fun applyImageSubtitleStereoMode() {
        val stereoIn = if (imageSubtitle3D || !leiaEnabled) "no" else currentStereoInFilterValue()
        MPVLib.setOptionString("vf", "format:stereo-in=$stereoIn")
    }

    private fun mapImageSubtitleScale(slider: Int): Double {
        val clamped = slider.coerceIn(-15, 15)
        // Piecewise linear mapping: -15 -> 0.1, 0 -> 1.0, 15 -> 3.0
        return if (clamped <= 0) {
            0.1 + (clamped + 15) / 15.0 * 0.9
        } else {
            1.0 + clamped / 15.0 * 2.0
        }
    }

    private fun applyImageSubtitleScale(scale: Int) {
        MPVLib.setPropertyDouble("sub-scale", mapImageSubtitleScale(scale))
    }

    private fun applyImageSubtitlePosition(position: Int) {
        MPVLib.setPropertyInt("sub-pos", position.coerceIn(50, 150))
    }

    private fun guessNetworkSubtitles(filepath: String) {
        if (!filepath.startsWith("http://")) return
        if (hasGuessedNetworkSubtitles) return
        hasGuessedNetworkSubtitles = true

        try {
            val lastSlash = filepath.lastIndexOf('/')
            if (lastSlash == -1 || lastSlash == filepath.length - 1) return

            val dirUrl = filepath.substring(0, lastSlash + 1)
            val filename = filepath.substring(lastSlash + 1)
            
            val queryIndex = filename.indexOf('?')
            val cleanFilename = if (queryIndex != -1) filename.substring(0, queryIndex) else filename
            val queryString = if (queryIndex != -1) filename.substring(queryIndex) else ""

            val lastDot = cleanFilename.lastIndexOf('.')
            val baseName = if (lastDot != -1) cleanFilename.substring(0, lastDot) else cleanFilename

            // 1. Attempt to fetch directory listing first (1 HTTP request)
            val foundSubtitleUrls = fetchDirectorySubtitleUrls(dirUrl, baseName, queryString)
            
            if (foundSubtitleUrls.isNotEmpty()) {
                for (subUrl in foundSubtitleUrls) {
                    MPVLib.command(arrayOf("sub-add", subUrl))
                }
                return // Success: skip blind guessing
            }

            // 2. Fallback: Blind guessing if directory listing is unsupported or empty
            val extensions = listOf("srt", "ass", "ssa", "vtt", "txt")
            val wildcards = listOf(
                "en", "eng", "es", "spa", "fr", "fre", "de", "ger", "it", "ita", "pt", "por", 
                "ru", "rus", "zh", "chi", "jp", "jpn", "ko", "kor", "ar", "ara", "hi", "hin",
                "sv", "se", "fi", "no", "forced", "sdh", "cc", "default"
            )

            for (ext in extensions) {
                MPVLib.command(arrayOf("sub-add", "$dirUrl$baseName.$ext$queryString"))
                for (wildcard in wildcards) {
                    MPVLib.command(arrayOf("sub-add", "$dirUrl$baseName.$wildcard.$ext$queryString"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to guess network subtitles: $e")
        }
    }

    private fun fetchDirectorySubtitleUrls(dirUrl: String, baseName: String, queryString: String): List<String> {
        val subtitleUrls = mutableListOf<String>()
        try {
            Log.d(TAG, "Subsearch: Attempting directory listing: $dirUrl")
            val connection = java.net.URL(dirUrl).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000

            if (connection.responseCode == 200) {
                val content = connection.inputStream.bufferedReader().use { it.readText() }
                // Log truncated content to avoid Logcat 4000-char limits
                Log.d(TAG, "Subsearch: Directory response (first 2000 chars): ${content.take(2000)}")
                
                val regex = Regex("""href=["']([^"']+\.(?:srt|ass|ssa|vtt|txt))["']""", RegexOption.IGNORE_CASE)
                
                for (match in regex.findAll(content)) {
                    val href = match.groupValues[1]
                    val decodedBaseName = java.net.URLDecoder.decode(baseName, "UTF-8")
                    
                    if (href.contains(baseName, ignoreCase = true) || href.contains(decodedBaseName, ignoreCase = true)) {
                        val fullUrl = java.net.URL(java.net.URL(dirUrl), href).toString() + queryString
                        val decodedUrl = java.net.URLDecoder.decode(fullUrl, "UTF-8")
                        
                        if (!subtitleUrls.contains(decodedUrl)) {
                            subtitleUrls.add(decodedUrl)
                        }
                    }
                }
                Log.d(TAG, "Subsearch: Successfully parsed subtitle URLs: $subtitleUrls")
            } else {
                Log.d(TAG, "Subsearch: Directory listing returned non-200 response code: ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Subsearch: Directory listing failed or unsupported (falling back to blind guess): ${e.message}")
        }
        return subtitleUrls
    }

    private fun updateStereoSubtitleMode() {
        val shouldEnableStereoSubs = isSbs3DActive() && player.sid != -1
        stereoSubtitleModeEnabled = shouldEnableStereoSubs
        val imageTrack = shouldEnableStereoSubs && isImageSubtitleTrackSelected()
        Log.d(TAG, "LeiaImageSub: updateStereoSubtitleMode sid=${player.sid} shouldEnableStereoSubs=$shouldEnableStereoSubs imageTrack=$imageTrack")
        applyImageSubtitleStereoMode()

        if (!shouldEnableStereoSubs) {
            MPVLib.setPropertyBoolean("sub-visibility", true)
            MPVLib.setPropertyDouble("sub-scale", 1.0)
            MPVLib.setPropertyInt("sub-pos", 100)
            player.setStereoSubtitleEnabled(false)
            stopImageSubtitleDecoder(resetNative = true)
            return
        }

        if (imageTrack) {
            // Pre-authored stereo PGS/VobSub/etc already has its L/R content
            // positioned to match the packed SBS/TAB frame, the same way the
            // video itself is packed — so mpv's own native subtitle rendering
            // already produces a correct stereo result here, same as if it
            // were part of the video. No need for the custom bitmap
            // decode/duplicate pipeline built for text subtitles, which only
            // makes sense for content that ISN'T already stereo-aware.
            //
            // Correct positioning depends on osd-keepaspect being set in
            // applyLeiaDisplayProperties() — see the comment there.
            //
            // Mono image subtitles aren't stereo-aware at all, so instead of
            // relying on the (correct, but single) image being split across
            // both eyes by our own shader, mpv's stereo-in filter duplicates
            // it into both eyes directly, matching the current SBS/TAB
            // packing. Position is meaningful either way (a pre-authored
            // stereo subtitle can still be authored slightly off-position
            // for this display), but scale correction only makes sense for
            // the duplicated/mono case.
            MPVLib.setPropertyBoolean("sub-visibility", true)
            applyImageSubtitlePosition(imageSubtitlePosition)
            if (imageSubtitle3D) {
                MPVLib.setPropertyDouble("sub-scale", 1.0)
            } else {
                applyImageSubtitleScale(imageSubtitleScale)
            }
            player.setStereoSubtitleEnabled(false)
            stopImageSubtitleDecoder(resetNative = true)
            return
        }

        MPVLib.setPropertyBoolean("sub-visibility", false)
        player.setStereoSubtitleEnabled(true)
        stopImageSubtitleDecoder(resetNative = true)
        applySubtitleDepth(subtitleDepth)
        updateStereoSubtitleText(currentSubText)
    }

    private fun onTimePositionChanged(timePosSec: Double) {
        if (stereoSubtitleModeEnabled && isImageSubtitleTrackSelected() && imageSubtitleDecoderReady) {
            updateImageSubtitleFrame(timePosSec)
        }
    }

    private fun toggle3D() {
        if (leiaEnabled) {
            userForced3DOffForCurrentFile = true
            leiaEnabled = false
            player.setMode(0)
            Disable3D()
        } else {
            userForced3DOffForCurrentFile = false
            imageSubtitleDecoderFailedKey = null
            // Default to HALF_SBS when no format was auto-detected
            if (currentLeiaFormat == LeiaFormat.NONE) {
                currentLeiaFormat = LeiaFormat.HALF_SBS
            }
            apply3DMode(currentLeiaFormat)
        }
        mPrevDesiredBacklightModeState = leiaEnabled
        update3DButton()
        updateStereoSubtitleMode()
    }

    private fun pick3D() {
        val restore = pauseForDialog()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_3d, null)

        val modeGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.modeGroup)
        val modeFullSbs = dialogView.findViewById<android.widget.RadioButton>(R.id.modeFullSbs)
        val modeHalfSbs = dialogView.findViewById<android.widget.RadioButton>(R.id.modeHalfSbs)
        val modeHalfTab = dialogView.findViewById<android.widget.RadioButton>(R.id.modeHalfTab)
        val modeFullTab = dialogView.findViewById<android.widget.RadioButton>(R.id.modeFullTab)
        val swapEyesCheck = dialogView.findViewById<CheckBox>(R.id.swapEyesCheck)
        val depthSeekBar = dialogView.findViewById<android.widget.SeekBar>(R.id.depthSeekBar)
        val depthValue = dialogView.findViewById<android.widget.TextView>(R.id.depthValue)
        val positionSeekBar = dialogView.findViewById<android.widget.SeekBar>(R.id.positionSeekBar)
        val positionValue = dialogView.findViewById<android.widget.TextView>(R.id.positionValue)
        val sizeSeekBar = dialogView.findViewById<android.widget.SeekBar>(R.id.sizeSeekBar)
        val sizeValue = dialogView.findViewById<android.widget.TextView>(R.id.sizeValue)
        val imageSubtitle3DCheck = dialogView.findViewById<CheckBox>(R.id.imageSubtitle3DCheck)
        val imageSubtitleScaleLabel = dialogView.findViewById<android.widget.TextView>(R.id.imageSubtitleScaleLabel)
        val imageSubtitleScaleSeekBar = dialogView.findViewById<android.widget.SeekBar>(R.id.imageSubtitleScaleSeekBar)
        val imageSubtitleScaleValue = dialogView.findViewById<android.widget.TextView>(R.id.imageSubtitleScaleValue)
        val imageSubtitlePositionLabel = dialogView.findViewById<android.widget.TextView>(R.id.imageSubtitlePositionLabel)
        val imageSubtitlePositionSeekBar = dialogView.findViewById<android.widget.SeekBar>(R.id.imageSubtitlePositionSeekBar)
        val imageSubtitlePositionValue = dialogView.findViewById<android.widget.TextView>(R.id.imageSubtitlePositionValue)
        val imageSubsScaleXLabel = dialogView.findViewById<android.widget.TextView>(R.id.imageSubsScaleXLabel)
        val imageSubsScaleXSeekBar = dialogView.findViewById<android.widget.SeekBar>(R.id.imageSubsScaleXSeekBar)
        val imageSubsScaleXValue = dialogView.findViewById<android.widget.TextView>(R.id.imageSubsScaleXValue)
        val imageSubsScaleYLabel = dialogView.findViewById<android.widget.TextView>(R.id.imageSubsScaleYLabel)
        val imageSubsScaleYSeekBar = dialogView.findViewById<android.widget.SeekBar>(R.id.imageSubsScaleYSeekBar)
        val imageSubsScaleYValue = dialogView.findViewById<android.widget.TextView>(R.id.imageSubsScaleYValue)

        // Set initial radio selection from current format
        when (currentLeiaFormat) {
            LeiaFormat.FULL_SBS -> modeFullSbs.isChecked = true
            LeiaFormat.HALF_SBS -> modeHalfSbs.isChecked = true
            LeiaFormat.HALF_TAB -> modeHalfTab.isChecked = true
            LeiaFormat.FULL_TAB -> modeFullTab.isChecked = true
            else -> modeHalfSbs.isChecked = true
        }

        // Restore saved values
        depthSeekBar.progress = subtitleDepth + 15
        depthValue.text = if (subtitleDepth >= 0) "+$subtitleDepth" else "$subtitleDepth"

        positionSeekBar.progress = subtitlePosition + 15
        positionValue.text = if (subtitlePosition >= 0) "+$subtitlePosition" else "$subtitlePosition"

        sizeSeekBar.progress = subtitleSize + 15
        sizeValue.text = if (subtitleSize >= 0) "+$subtitleSize" else "$subtitleSize"

        fun formatImageSubtitleScale(slider: Int): String {
            return String.format("%.1f", mapImageSubtitleScale(slider))
        }
        fun formatImageSubtitlePosition(position: Int): String {
            return "$position"
        }
        fun formatImageSubsScale(tenths: Int): String {
            return String.format("%.1fx", tenths / 10.0)
        }
        fun setImageSubtitleScaleEnabled(enabled: Boolean) {
            imageSubtitleScaleLabel.isEnabled = enabled
            imageSubtitleScaleSeekBar.isEnabled = enabled
            imageSubtitleScaleValue.isEnabled = enabled
            imageSubsScaleXLabel.isEnabled = enabled
            imageSubsScaleXSeekBar.isEnabled = enabled
            imageSubsScaleXValue.isEnabled = enabled
            imageSubsScaleYLabel.isEnabled = enabled
            imageSubsScaleYSeekBar.isEnabled = enabled
            imageSubsScaleYValue.isEnabled = enabled
        }

        imageSubtitle3DCheck.isChecked = imageSubtitle3D
        imageSubtitleScaleSeekBar.progress = imageSubtitleScale + 15
        imageSubtitleScaleValue.text = formatImageSubtitleScale(imageSubtitleScale)
        imageSubtitlePositionSeekBar.progress = imageSubtitlePosition - 50
        imageSubtitlePositionValue.text = formatImageSubtitlePosition(imageSubtitlePosition)
        imageSubsScaleXSeekBar.progress = imageSubsScaleX - 1
        imageSubsScaleXValue.text = formatImageSubsScale(imageSubsScaleX)
        imageSubsScaleYSeekBar.progress = imageSubsScaleY - 1
        imageSubsScaleYValue.text = formatImageSubsScale(imageSubsScaleY)
        setImageSubtitleScaleEnabled(!imageSubtitle3D)

        // Change initialization and listener:
        swapEyesCheck.isChecked = swapEyes
        swapEyesCheck.setOnCheckedChangeListener { _, isChecked ->
            swapEyes = isChecked
            player.setSwapImages(isChecked)
            persistImageSubtitleSwapEyes()
        }

        depthSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val depth = progress - 15
                depthValue.text = if (depth >= 0) "+$depth" else "$depth"
                if (fromUser) {
                    subtitleDepth = depth
                    applySubtitleDepth(subtitleDepth)
                    persistSubtitleDepth()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })

        positionSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val position = progress - 15
                positionValue.text = if (position >= 0) "+$position" else "$position"
                if (fromUser) {
                    subtitlePosition = position
                    applySubtitlePosition(subtitlePosition)
                    persistSubtitlePosition()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })

        sizeSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val size = progress - 15
                sizeValue.text = if (size >= 0) "+$size" else "$size"
                if (fromUser) {
                    subtitleSize = size
                    applySubtitleSize(subtitleSize)
                    persistSubtitleSize()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })

        imageSubtitle3DCheck.setOnCheckedChangeListener { _, isChecked ->
            imageSubtitle3D = isChecked
            setImageSubtitleScaleEnabled(!isChecked)
            applyImageSubtitleStereoMode()
            applyImageSubtitlePosition(imageSubtitlePosition)
            if (isChecked) {
                MPVLib.setPropertyDouble("sub-scale", 1.0)
            } else {
                applyImageSubtitleScale(imageSubtitleScale)
            }
            applyLeiaDisplayProperties(currentLeiaFormat, leiaEnabled)
            persistImageSubtitle3D()
        }

        imageSubtitleScaleSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val scale = progress - 15
                imageSubtitleScaleValue.text = formatImageSubtitleScale(scale)
                if (fromUser) {
                    imageSubtitleScale = scale
                    applyImageSubtitleScale(imageSubtitleScale)
                    persistImageSubtitleScale()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })

        imageSubtitlePositionSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val position = progress + 50
                imageSubtitlePositionValue.text = formatImageSubtitlePosition(position)
                if (fromUser) {
                    imageSubtitlePosition = position
                    applyImageSubtitlePosition(imageSubtitlePosition)
                    persistImageSubtitlePosition()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })

        imageSubsScaleXSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val tenths = progress + 1
                imageSubsScaleXValue.text = formatImageSubsScale(tenths)
                if (fromUser) {
                    imageSubsScaleX = tenths
                    applyLeiaDisplayProperties(currentLeiaFormat, leiaEnabled)
                    persistImageSubsScaleX()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })

        imageSubsScaleYSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val tenths = progress + 1
                imageSubsScaleYValue.text = formatImageSubsScale(tenths)
                if (fromUser) {
                    imageSubsScaleY = tenths
                    applyLeiaDisplayProperties(currentLeiaFormat, leiaEnabled)
                    persistImageSubsScaleY()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })

        lateinit var dialog: AlertDialog
        dialog = with(AlertDialog.Builder(this)) {
            setTitle(R.string.title_3d_dialog)
            setView(dialogView)
            setPositiveButton(R.string.dialog_ok) { _, _ ->
                val checkedId = modeGroup.checkedRadioButtonId
                val newFormat = when (checkedId) {
                    R.id.modeFullSbs -> LeiaFormat.FULL_SBS
                    R.id.modeHalfSbs -> LeiaFormat.HALF_SBS
                    R.id.modeHalfTab -> LeiaFormat.HALF_TAB
                    R.id.modeFullTab -> LeiaFormat.FULL_TAB
                    else -> LeiaFormat.HALF_SBS
                }
                subtitleDepth = depthSeekBar.progress - 15
                subtitlePosition = positionSeekBar.progress - 15
                subtitleSize = sizeSeekBar.progress - 15
                persistSubtitleDepth()
                persistSubtitlePosition()
                persistSubtitleSize()
                imageSubtitle3D = imageSubtitle3DCheck.isChecked
                imageSubtitleScale = imageSubtitleScaleSeekBar.progress - 15
                imageSubtitlePosition = imageSubtitlePositionSeekBar.progress + 50
                imageSubsScaleX = imageSubsScaleXSeekBar.progress + 1
                imageSubsScaleY = imageSubsScaleYSeekBar.progress + 1
                swapEyes = swapEyesCheck.isChecked
                persistImageSubtitle3D()
                persistImageSubtitleScale()
                persistImageSubtitlePosition()
                persistImageSubsScaleX()
                persistImageSubsScaleY()
                persistImageSubtitleSwapEyes()
                apply3DMode(newFormat)
                restore()
            }
            setNegativeButton(R.string.dialog_cancel) { _, _ -> restore() }
            setOnCancelListener { restore() }
            create()
        }
        dialog.show()
        dialog.window?.decorView?.post {
            val screenWidth = resources.displayMetrics.widthPixels
            val desiredWidth = Utils.convertDp(this, 820f)
            val maxWidth = (screenWidth * 0.95f).toInt()
            dialog.window?.setLayout(minOf(desiredWidth, maxWidth), WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun apply3DMode(format: LeiaFormat) {
        currentLeiaFormat = format
        Log.d("HALF_TAB_DEBUG", "apply3DMode called: format=$format")
        when (format) {
            LeiaFormat.NONE -> {
                userForced3DOffForCurrentFile = true
                leiaEnabled = false
                player.setMode(0)
                Disable3D()
            }
            LeiaFormat.HALF_SBS -> {
                userForced3DOffForCurrentFile = false
                imageSubtitleDecoderFailedKey = null
                leiaEnabled = true
                player.setMode(1)
                Enable3D()
            }
            LeiaFormat.HALF_TAB -> {
                userForced3DOffForCurrentFile = false
                imageSubtitleDecoderFailedKey = null
                leiaEnabled = true
                player.setMode(2)
                Log.d("HALF_TAB_DEBUG", "HALF_TAB mode activated, setting mode=2")
                Enable3D()
            }
            LeiaFormat.FULL_SBS -> {
                userForced3DOffForCurrentFile = false
                imageSubtitleDecoderFailedKey = null
                leiaEnabled = true
                player.setMode(3)
                Enable3D()
            }
            LeiaFormat.FULL_TAB -> {
                userForced3DOffForCurrentFile = false
                imageSubtitleDecoderFailedKey = null
                leiaEnabled = true
                // For now, use mode 2 (TAB conversion) for FULL_TAB as well
                player.setMode(2)
                Log.d("HALF_TAB_DEBUG", "FULL_TAB mode activated, setting mode=2")
                Enable3D()
            }
        }
        applyLeiaDisplayProperties(format, leiaEnabled)
        updateLeiaContentAspect()
        updateOrientation()
        mPrevDesiredBacklightModeState = leiaEnabled
        update3DButton()
        updateStereoSubtitleMode()
    }

    fun checkShouldToggle3D(desired_state: Boolean) {
        if (desired_state) {
            Enable3D()
        } else {
            Disable3D()
        }
        mPrevDesiredBacklightModeState = desired_state
    }
}