package com.ruich97.beastlocatorwatch

import android.annotation.SuppressLint
import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.location.Location
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.ceil

class MainActivity : AppCompatActivity(), SensorEventListener {
    companion object {
        private const val ARROW_IMAGE_FORWARD_OFFSET_DEGREES = 45f
        private const val ARRIVAL_THRESHOLD_METERS = 50f
        private const val DISTANCE_MASK_STEP_KM = 100
        private const val LOCATION_TIMEOUT_MS = 30_000L
        const val ACTION_LOCATION_UPDATE = "com.ruich97.beastlocatorwatch.ACTION_LOCATION_UPDATE"
    }

    private lateinit var store: DestinationStore
    private lateinit var arrowView: ImageView
    private lateinit var distanceMaskToggleButton: ImageButton
    private lateinit var distanceView: TextView
    private lateinit var directionView: TextView
    private lateinit var centerContent: LinearLayout
    private lateinit var arrivalContent: LinearLayout
    private lateinit var arrivalNameView: TextView
    private lateinit var arrivalCoordsView: TextView
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var loadingArrowAnimator: ObjectAnimator? = null

    // PendingIntent 兜底：同时通过系统广播通道接收位置更新
    // 传统 LocationListener 和 PendingIntent 双通道，提高兼容性
    private val locationPendingIntent: android.app.PendingIntent by lazy {
        android.app.PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_LOCATION_UPDATE).setClassName(this, javaClass.name),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    // PendingIntent 广播接收器——通过系统广播接收位置更新
    private val locationBroadcastReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val location = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(android.location.LocationManager.KEY_LOCATION_CHANGED, android.location.Location::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(android.location.LocationManager.KEY_LOCATION_CHANGED)
            }
            if (location != null) {
                locationListener.onLocationChanged(location)
            }
        }
    }

    private var headingDegrees: Float = 0f
    private var hasHeadingSample = false
    private var isCompassSmoothingEnabled = false
    private var currentLocation: Destination? = null
    private var destination: Destination? = null
    private var hasShownInAppArrival = false
    private var isResolvingArrivalName = false
    private var isShowingPreciseLocationPermissionGuide = false
    private var isShowingBackgroundPermissionGuide = false
    private var backgroundPermissionGuideDialog: AlertDialog? = null
    private var skipPermissionGuideOnce = false
    private var isScreenCaptureCallbackRegistered = false
    private var screenCaptureCallbackRef: Any? = null
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading  = FloatArray(3)
    private val rotationMatrix       = FloatArray(9)
    private val orientationAngles    = FloatArray(3)
    private val remappedRotationMatrix = FloatArray(9)
    private val locationTimeoutRunnable = Runnable {
        if (currentLocation == null && !store.isDestinationAnswered()) {
            showLocationUnavailableState(R.string.location_timeout)
        }
    }

    // GPS 冷启动时主动轮询 getLastKnownLocation 作为兜底
    // 手表 GPS 信号差，onLocationChanged 可能很久才来，用这个加速首次定位
    private val locationPollRunnable = object : Runnable {
        override fun run() {
            val cached = getLastKnownLocationFromSystem()
            if (cached != null && currentLocation == null && !store.isDebugDistanceOverrideEnabled()) {
                        currentLocation = Destination(cached.latitude, cached.longitude)
                store.setLastKnownLocationFromSystem(cached.latitude, cached.longitude)
                store.setLastKnownHeading(headingDegrees)
                ensureDestinationExists()
                updateUi(refreshWidgets = true)
                distanceView.removeCallbacks(locationTimeoutRunnable)
            } else {
                        distanceView.postDelayed(this, 3_000L)
            }
        }
    }

    // 原生 LocationListener，同时监听 NETWORK_PROVIDER 和 GPS_PROVIDER
    // 不依赖 Google Play Services，国内国外均可用
    private val locationListener = object : android.location.LocationListener {
        override fun onLocationChanged(location: Location) {
                distanceView.removeCallbacks(locationPollRunnable)
            if (store.isDebugDistanceOverrideEnabled()) return
            distanceView.removeCallbacks(locationTimeoutRunnable)
            currentLocation = Destination(location.latitude, location.longitude)
            store.setLastKnownLocationFromSystem(location.latitude, location.longitude)
            store.setLastKnownHeading(headingDegrees)
            ensureDestinationExists()
            updateUi(refreshWidgets = true)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
            }
        override fun onProviderEnabled(provider: String) {
            }
        override fun onProviderDisabled(provider: String) {
            }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            continueAfterPermissionFlow()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = DestinationStore(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        arrowView = findViewById(R.id.arrowView)
        distanceMaskToggleButton = findViewById(R.id.distanceMaskToggleButton)
        distanceView = findViewById(R.id.distanceText)
        directionView = findViewById(R.id.directionText)
        centerContent = findViewById(R.id.centerContent)
        arrivalContent = findViewById(R.id.arrivalContent)
        arrivalNameView = findViewById(R.id.arrivalNameText)
        arrivalCoordsView = findViewById(R.id.arrivalCoordsText)
        arrowView.clearColorFilter()
        findViewById<Button>(R.id.createNextDestinationButton).setOnClickListener {
            resetDestinationProgress()
        }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        distanceMaskToggleButton.setOnClickListener {
            val enabled = !store.isManualDistanceMaskEnabled()
            store.setManualDistanceMaskEnabled(enabled)
            applyDistanceMaskToggleButtonState()
            updateUi(refreshWidgets = false)
        }
    }

    override fun onResume() {
        super.onResume()
        // 手表版：保持屏幕常亮
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        destination = store.getDestination()
        currentLocation = store.getLastKnownLocation()
        isCompassSmoothingEnabled = store.isCompassSmoothingEnabled()
        hasShownInAppArrival = store.isDestinationAnswered()
        applyDistanceMaskToggleButtonState()
        arrowView.clearColorFilter()
        updateArrivalUiIfNeeded()
        DestinationWidgetProvider.refreshAllWidgets(this)
        if (skipPermissionGuideOnce) {
            skipPermissionGuideOnce = false
            continueAfterPermissionFlow()
        } else {
            requestRuntimePermissionsIfNeeded()
        }
        syncDestinationGeofence()
        registerCompass()
        registerScreenCaptureCallbackIfSupported()
        // 注册 PendingIntent 广播接收器（通过系统广播接收位置更新）
        android.content.IntentFilter(ACTION_LOCATION_UPDATE).also {
            it.addAction(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(locationBroadcastReceiver, it, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(locationBroadcastReceiver, it)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 手表版：取消屏幕常亮
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        locationManager.removeUpdates(locationListener)
        runCatching { locationManager.removeUpdates(locationPendingIntent) }
        runCatching { unregisterReceiver(locationBroadcastReceiver) }
        distanceView.removeCallbacks(locationTimeoutRunnable)
        distanceView.removeCallbacks(locationPollRunnable)
        sensorManager.unregisterListener(this)
        stopLoadingArrowAnimation()
        unregisterScreenCaptureCallbackIfNeeded()
    }

    private fun requestRuntimePermissionsIfNeeded() {
        if (hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            // Ensure stale background registrations are stopped immediately
            // when precise location is no longer granted.
            startUpdatesIfPermitted()
            ensurePreciseLocationPermission()
            return
        }

        val required = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            required += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            required += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            required += Manifest.permission.POST_NOTIFICATIONS
        }

        if (required.isNotEmpty()) {
            permissionLauncher.launch(required.toTypedArray())
        } else {
            continueAfterPermissionFlow()
        }
    }

    private fun continueAfterPermissionFlow() {
        // 移除了后台定位权限检查 - 手表应用不需要后台定位
        // ensureBackgroundLocationPermission()
        if (maybeLaunchWelcomeScreen()) return
        startUpdatesIfPermitted()
    }

    private fun maybeLaunchWelcomeScreen(): Boolean {
        if (store.isWelcomeCompleted()) return false
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return false
        if (isShowingPreciseLocationPermissionGuide || isShowingBackgroundPermissionGuide) return false
        startActivity(Intent(this, WelcomeActivity::class.java))
        return true
    }

    private fun ensurePreciseLocationPermission() {
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            return
        }
        if (isShowingPreciseLocationPermissionGuide) {
            return
        }

        isShowingPreciseLocationPermissionGuide = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.precise_permission_guide_title)
            .setMessage(R.string.precise_permission_guide_message)
            .setCancelable(false)
            .setPositiveButton(R.string.precise_permission_guide_positive) { _, _ ->
                isShowingPreciseLocationPermissionGuide = false
                openAppPermissionSettings()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                isShowingPreciseLocationPermissionGuide = false
                startUpdatesIfPermitted()
            }
            .setOnDismissListener {
                isShowingPreciseLocationPermissionGuide = false
            }
            .show()
    }

    private fun ensureBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            if (isShowingBackgroundPermissionGuide ||
                backgroundPermissionGuideDialog?.isShowing == true
            ) {
                return
            }
            isShowingBackgroundPermissionGuide = true
            backgroundPermissionGuideDialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.background_permission_guide_title)
                .setMessage(R.string.background_permission_guide_message)
                .setCancelable(false)
                .setPositiveButton(R.string.background_permission_guide_positive) { _, _ ->
                    store.setBackgroundPermissionGuideShown(true)
                    isShowingBackgroundPermissionGuide = false
                    openAppPermissionSettings()
                }
                .setOnDismissListener {
                    isShowingBackgroundPermissionGuide = false
                    backgroundPermissionGuideDialog = null
                }
                .show()
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun startUpdatesIfPermitted() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            locationManager.removeUpdates(locationListener)
            distanceView.removeCallbacks(locationTimeoutRunnable)
            currentLocation = null
            startLoadingArrowAnimation()
            BackgroundLocationUpdater.updateRegistration(this)
            NotificationHelper.cancelApproachProgress(this)
            if (store.isDestinationAnswered()) {
                updateArrivalUiIfNeeded()
            } else {
                setArrivalStateVisible(false)
                distanceView.typeface = Typeface.DEFAULT
                distanceView.text = getString(
                    if (hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                        R.string.waiting_location
                    } else {
                        R.string.permission_needed
                    }
                )
                directionView.text = ""
                directionView.setTextColor(
                    ContextCompat.getColor(this, R.color.expressive_on_surface_variant)
                )
            }
            return
        }

        if (!isSystemLocationEnabled()) {
            startLoadingArrowAnimation()
            showLocationUnavailableState(R.string.location_service_disabled)
            return
        }

        startLoadingArrowAnimation()
        BackgroundLocationUpdater.updateRegistration(this)

        // 尝试用上一次已知位置快速显示
        val lastKnown = getLastKnownLocationFromSystem()
        if (lastKnown != null && currentLocation == null && !store.isDebugDistanceOverrideEnabled()) {
            currentLocation = Destination(lastKnown.latitude, lastKnown.longitude)
            store.setLastKnownLocationFromSystem(lastKnown.latitude, lastKnown.longitude)
            store.setLastKnownHeading(headingDegrees)
            ensureDestinationExists()
            updateUi(refreshWidgets = true)
        }

        distanceView.removeCallbacks(locationTimeoutRunnable)
        distanceView.postDelayed(locationTimeoutRunnable, LOCATION_TIMEOUT_MS)

        val netEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        val gpsEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

        // 使用原生 LocationManager，不依赖 Google Play Services
        // 传统 LocationListener + PendingIntent 双通道，兼容性更强
        var started = false
        runCatching {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 2_000L, 0f, locationListener, mainLooper
                )
                started = true
            }
        }
        runCatching {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 2_000L, 0f, locationListener, mainLooper
                )
                started = true
                // PendingIntent 兜底
                runCatching {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 1_000L, 0f, locationPendingIntent
                    )
                }
                runCatching {
                    locationManager.requestLocationUpdates(
                        LocationManager.PASSIVE_PROVIDER, 1_000L, 0f, locationPendingIntent
                    )
                }
            }
        }
        if (!started) {
            distanceView.removeCallbacks(locationTimeoutRunnable)
            showLocationUnavailableState(R.string.location_update_start_failed)
            return
        }

        // NETWORK_PROVIDER 不可用时，GPS 冷启动慢，启动主动轮询加速首次定位
        if (!netEnabled && gpsEnabled) {
            distanceView.removeCallbacks(locationPollRunnable)
            distanceView.postDelayed(locationPollRunnable, 5_000L)
        }
    }

    private fun showLocationUnavailableState(messageResId: Int, detailMessageResId: Int? = null) {
        setArrivalStateVisible(false)
        distanceView.typeface = Typeface.DEFAULT
        distanceView.text = getString(messageResId)
        directionView.text = detailMessageResId?.let(::getString).orEmpty()
        directionView.setTextColor(
            ContextCompat.getColor(this, R.color.expressive_on_surface_variant)
        )
    }

    // 获取系统最近已知位置（优先 NETWORK_PROVIDER，其次 GPS_PROVIDER）
    @SuppressLint("MissingPermission")
    private fun getLastKnownLocationFromSystem(): Location? {
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val network = runCatching {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
        if (network != null) return network
        return runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull()
    }

    private fun isSystemLocationEnabled(): Boolean {
        val manager = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun ensureDestinationExists() {
        destination = store.getDestination()
        syncDestinationGeofence()
    }

    private fun syncDestinationGeofence() {
        val target = destination ?: return
        if (!store.isDestinationAnswered() && GeofenceHelper.canRegisterDestinationGeofence(this)) {
            GeofenceHelper.registerDestinationGeofence(this, target)
        } else {
            GeofenceHelper.clearDestinationGeofence(this)
        }
    }

    private fun updateUi(refreshWidgets: Boolean) {
        val current = currentLocation ?: return
        val target = destination ?: return
        if (!isValidDestination(current) || !isValidDestination(target)) return

        stopLoadingArrowAnimation()
        if (store.isDestinationAnswered()) {
            updateArrivalUiIfNeeded()
            if (refreshWidgets) {
                DestinationWidgetProvider.refreshAllWidgets(this)
            }
            return
        }

        val (distance, bearing) = runCatching {
            GeoUtils.distanceMeters(current, target) to GeoUtils.bearingDegrees(current, target)
        }.getOrElse {
            return
        }
        if (!distance.isFinite() || !bearing.isFinite()) {
            return
        }
        if (!headingDegrees.isFinite()) return
        val relativeRotation = normalizeRotation(
            bearing - headingDegrees - ARROW_IMAGE_FORWARD_OFFSET_DEGREES
        )
        if (!relativeRotation.isFinite()) {
            return
        }

        setArrivalStateVisible(false)
        arrowView.rotation = relativeRotation
        distanceView.typeface = Typeface.MONOSPACE
        distanceView.text = formatDistanceForMainScreen(distance)
        directionView.setTextColor(
            ContextCompat.getColor(this, R.color.expressive_on_surface_variant)
        )
        directionView.text = if (store.isManualDistanceMaskEnabled()) {
            getString(R.string.direction_placeholder)
        } else {
            getString(
                R.string.direction_label,
                GeoUtils.cardinalFromBearing(bearing)
            )
        }

        updateApproachLiveUpdate(distance)

        if (store.isArrivalRearmRequired()) {
            if (distance > ARRIVAL_THRESHOLD_METERS) {
                store.setArrivalRearmRequired(false)
            }
        }

        if (!store.isArrivalRearmRequired() &&
            distance <= ARRIVAL_THRESHOLD_METERS &&
            !hasShownInAppArrival
        ) {
            hasShownInAppArrival = true
            if (store.isArrivalSoundEnabled()) {
                SoundEffectPlayer.play(this, R.raw.arrival_0km)
            }
            store.setDestinationAnswered(true)
            store.setArrivalDestinationName("${target.lat}, ${target.lng}")
            NotificationHelper.cancelApproachProgress(this)
            resolveArrivalNameIfNeeded(target, shouldNotifyWhenResolved = true)
            updateArrivalUiIfNeeded()
        }
        if (refreshWidgets) {
            DestinationWidgetProvider.refreshAllWidgets(this)
        }
    }

    private fun updateArrivalUiIfNeeded() {
        if (!store.isDestinationAnswered()) {
            setArrivalStateVisible(false)
            if (currentLocation == null) {
                startLoadingArrowAnimation()
            }
            return
        }
        stopLoadingArrowAnimation()
        setArrivalStateVisible(true)
        val target = destination
        if (target != null) {
            arrivalCoordsView.visibility = android.view.View.VISIBLE
            arrivalCoordsView.text = getString(
                R.string.arrival_coords_format,
                target.lat,
                target.lng
            )
        } else {
            arrivalCoordsView.visibility = android.view.View.GONE
        }
        val arrivalName = store.getArrivalDestinationName()
        if (arrivalName.isNullOrBlank()) {
            arrivalNameView.text = getString(R.string.arrival_name_placeholder)
            if (target != null) {
                resolveArrivalNameIfNeeded(target)
            }
            return
        }
        arrivalNameView.text = arrivalName
        if (target != null && arrivalName.contains(",")) {
            resolveArrivalNameIfNeeded(target)
        }
    }

    private fun resolveArrivalNameIfNeeded(
        target: Destination,
        shouldNotifyWhenResolved: Boolean = false
    ) {
        if (isResolvingArrivalName) return
        if (!store.isDestinationAnswered()) return
        val currentName = store.getArrivalDestinationName()
        if (!currentName.isNullOrBlank() && !currentName.contains(",")) return

        isResolvingArrivalName = true
        Thread {
            val resolved = ReverseGeocoder.resolve(this, target)
            runOnUiThread {
                isResolvingArrivalName = false
                val currentTarget = destination
                if (!store.isDestinationAnswered() || currentTarget == null || !sameDestination(currentTarget, target)) {
                    return@runOnUiThread
                }
                store.setArrivalDestinationName(resolved)
                arrivalNameView.text = resolved
                if (shouldNotifyWhenResolved) {
                    NotificationHelper.showDestinationReached(
                        this,
                        getString(R.string.notification_body, resolved)
                    )
                }
            }
        }.start()
    }

    private fun sameDestination(a: Destination, b: Destination): Boolean {
        return a.lat == b.lat && a.lng == b.lng
    }

    private fun setArrivalStateVisible(visible: Boolean) {
        arrivalContent.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        centerContent.visibility = if (visible) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun resetDestinationProgress() {
        val fixedDestination = store.getDestination()
        destination = fixedDestination
        store.setDestinationAnswered(false)
        store.setArrivalRearmRequired(true)
        hasShownInAppArrival = false
        GeofenceHelper.registerDestinationGeofence(this, fixedDestination)
        NotificationHelper.cancelApproachProgress(this)
        setArrivalStateVisible(false)
        if (currentLocation != null) {
            updateUi(refreshWidgets = true)
        } else {
            DestinationWidgetProvider.refreshAllWidgets(this)
        }
    }

    private fun updateApproachLiveUpdate(distanceMeters: Float) {
        if (!NotificationHelper.isLiveUpdateSupported()) {
            NotificationHelper.cancelApproachProgress(this)
            store.clearLiveUpdateAnchorDistanceMeters()
            return
        }

        if (!store.isLiveUpdateEnabled() || store.isDestinationAnswered()) {
            NotificationHelper.cancelApproachProgress(this)
            store.clearLiveUpdateAnchorDistanceMeters()
            return
        }

        val startDistanceMeters = store.getLiveUpdateStartDistanceMeters().coerceIn(200, 5000).toFloat()

        if (distanceMeters > startDistanceMeters) {
            NotificationHelper.cancelApproachProgress(this)
            store.clearLiveUpdateAnchorDistanceMeters()
            return
        }

        if (distanceMeters <= ARRIVAL_THRESHOLD_METERS) {
            NotificationHelper.cancelApproachProgress(this)
            store.clearLiveUpdateAnchorDistanceMeters()
            return
        }

        val anchorDistance = store.getLiveUpdateAnchorDistanceMeters()
            ?.takeIf { it > ARRIVAL_THRESHOLD_METERS } ?: distanceMeters.also {
            store.setLiveUpdateAnchorDistanceMeters(it)
        }
        val span = (anchorDistance - ARRIVAL_THRESHOLD_METERS).coerceAtLeast(1f)
        val progress = (((anchorDistance - distanceMeters) / span) * 100f).toInt().coerceIn(0, 100)
        NotificationHelper.showApproachProgress(this, distanceMeters, progress)
    }

    private fun normalizeRotation(value: Float): Float {
        if (!value.isFinite()) return 0f
        var normalized = value % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        if (abs(normalized) < 0.5f) return 0f
        return normalized
    }

    private fun applyDistanceMaskToggleButtonState() {
        val visible = store.isDistanceMaskButtonVisible()
        distanceMaskToggleButton.visibility = if (visible) View.VISIBLE else View.GONE

        val wasMaskEnabled = store.isManualDistanceMaskEnabled()
        if (!visible && wasMaskEnabled) {
            store.setManualDistanceMaskEnabled(false)
            if (!store.isDestinationAnswered()) {
                updateUi(refreshWidgets = false)
            }
        }

        val enabled = store.isManualDistanceMaskEnabled()
        distanceMaskToggleButton.setImageResource(
            if (enabled) R.drawable.ic_visibility
            else R.drawable.ic_visibility_off
        )
        distanceMaskToggleButton.contentDescription = getString(
            if (enabled) R.string.distance_mask_button_content_description_on
            else R.string.distance_mask_button_content_description_off
        )
        distanceMaskToggleButton.alpha = if (enabled) 1f else 0.68f
    }

    private fun registerScreenCaptureCallbackIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            isScreenCaptureCallbackRegistered
        ) {
            return
        }
        val registered = runCatching {
            registerScreenCaptureCallbackApi34()
        }.isSuccess
        isScreenCaptureCallbackRegistered = registered
    }

    private fun unregisterScreenCaptureCallbackIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            !isScreenCaptureCallbackRegistered
        ) {
            return
        }
        runCatching {
            unregisterScreenCaptureCallbackApi34()
        }
        isScreenCaptureCallbackRegistered = false
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun registerScreenCaptureCallbackApi34() {
        val callback = Activity.ScreenCaptureCallback {
            onMainScreenCaptured()
        }
        registerScreenCaptureCallback(mainExecutor, callback)
        screenCaptureCallbackRef = callback
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun unregisterScreenCaptureCallbackApi34() {
        val callback = screenCaptureCallbackRef as? Activity.ScreenCaptureCallback ?: return
        unregisterScreenCaptureCallback(callback)
        screenCaptureCallbackRef = null
    }

    private fun onMainScreenCaptured() {
        if (store.isScreenshotWarningEnabled()) {
            Toast.makeText(this, R.string.screenshot_privacy_warning, Toast.LENGTH_LONG).show()
        }
    }

    private fun formatDistanceForMainScreen(distanceMeters: Float): String {
        if (!store.isManualDistanceMaskEnabled()) {
            return GeoUtils.formatDistance(distanceMeters)
        }
        val distanceKm = distanceMeters / 1000f
        val maskedDistanceKm = if (distanceKm <= DISTANCE_MASK_STEP_KM.toFloat()) {
            DISTANCE_MASK_STEP_KM
        } else {
            (ceil(distanceKm / DISTANCE_MASK_STEP_KM).toInt()) * DISTANCE_MASK_STEP_KM
        }
        return getString(R.string.distance_masked_format_km, maskedDistanceKm)
    }

    private fun startLoadingArrowAnimation() {
        if (loadingArrowAnimator?.isRunning == true) {
            return
        }
        loadingArrowAnimator = ObjectAnimator.ofFloat(arrowView, View.ROTATION, 0f, 360f).apply {
            duration = 1400L
            interpolator = LinearInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopLoadingArrowAnimation() {
        loadingArrowAnimator?.cancel()
        loadingArrowAnimator = null
    }

    private fun openAppPermissionSettings() {
        val intents = listOf(
            Intent("android.settings.APP_PERMISSION_SETTINGS").apply {
                putExtra("android.provider.extra.APP_PACKAGE", packageName)
                putExtra("android.provider.extra.PERMISSION_NAME", Manifest.permission.ACCESS_FINE_LOCATION)
                putExtra("android.provider.extra.PERMISSION_GROUP_NAME", "android.permission-group.LOCATION")
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
        for (intent in intents) {
            if (intent.resolveActivity(packageManager) == null) continue
            skipPermissionGuideOnce = true
            val launched = runCatching {
                startActivity(intent)
            }.isSuccess
            if (launched) {
                return
            }
            skipPermissionGuideOnce = false
        }
    }

    private fun registerCompass() {
        sensorManager.unregisterListener(this)

        if (!store.isLegacyCompassModeEnabled()) {
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (sensor != null) {
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
        } else {
            val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val mag   = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            if (accel != null && mag != null) {
                sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
                sensorManager.registerListener(this, mag,   SensorManager.SENSOR_DELAY_UI)
            } else {
                val fallback = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                if (fallback != null) {
                    sensorManager.registerListener(this, fallback, SensorManager.SENSOR_DELAY_UI)
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val heading = calculateHeadingFromRotationVector(event.values) ?: return
                headingDegrees = if (isCompassSmoothingEnabled && hasHeadingSample) {
                    smoothAngleDegrees(headingDegrees, heading, 0.15f)
                } else {
                    hasHeadingSample = true
                    heading
                }
                updateUi(refreshWidgets = false)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                applyLowPassFilter(event.values, accelerometerReading)
                updateHeadingFromLegacyOrientation()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                applyLowPassFilter(event.values, magnetometerReading)
                updateHeadingFromLegacyOrientation()
            }
        }
    }

    private fun updateHeadingFromLegacyOrientation() {
        val success = SensorManager.getRotationMatrix(
            rotationMatrix, null,
            accelerometerReading,
            magnetometerReading
        )
        if (!success) return

        val (xAxis, yAxis) = when (getDisplayRotation()) {
            android.view.Surface.ROTATION_90 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
            android.view.Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
            android.view.Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
            else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
        }
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            xAxis,
            yAxis,
            remappedRotationMatrix
        )

        SensorManager.getOrientation(remappedRotationMatrix, orientationAngles)

        val azimuthRad = orientationAngles[0]
        if (!azimuthRad.isFinite()) return

        val heading = normalizeTo360(Math.toDegrees(azimuthRad.toDouble()).toFloat())
        headingDegrees = if (isCompassSmoothingEnabled && hasHeadingSample) {
            // Apply a slew rate limit (max 30 degrees per update) to legacy mode 
            // to ignore sudden spikes and gimbal lock flips.
            smoothAngleDegrees(headingDegrees, heading, 0.10f)
        } else {
            hasHeadingSample = true
            heading
        }
        updateUi(refreshWidgets = false)
    }

    private fun applyLowPassFilter(input: FloatArray, output: FloatArray) {
        val alpha = 0.10f
        for (i in 0 until minOf(input.size, output.size)) {
            output[i] = output[i] + alpha * (input[i] - output[i])
        }
    }


    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun calculateHeadingFromRotationVector(values: FloatArray): Float? {
        if (values.isEmpty()) return null
        val safeLen = minOf(values.size, 4)
        for (i in 0 until safeLen) {
            if (!values[i].isFinite()) return null
        }
        val safeValues = if (values.size > 4) values.sliceArray(0 until safeLen) else values

        return runCatching {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, safeValues)
            val (xAxis, yAxis) = when (getDisplayRotation()) {
                android.view.Surface.ROTATION_90 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
                android.view.Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
                android.view.Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
                else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
            }
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                xAxis,
                yAxis,
                remappedRotationMatrix
            )
            SensorManager.getOrientation(remappedRotationMatrix, orientationAngles)
            val heading = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (!heading.isFinite()) return null
            normalizeTo360(heading)
        }.getOrNull()
    }

    private fun Float.isFinite(): Boolean {
        return !isNaN() && !isInfinite()
    }

    private fun getDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: android.view.Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
    }

    private fun normalizeTo360(value: Float): Float {
        if (!value.isFinite()) return 0f
        val mod = value % 360f
        return if (mod < 0f) mod + 360f else mod
    }

    private fun smoothAngleDegrees(current: Float, target: Float, alpha: Float): Float {
        val clampedAlpha = alpha.coerceIn(0f, 1f)
        val delta = normalizeRotation(target - current)
        return normalizeTo360(current + (delta * clampedAlpha))
    }

    private fun isValidDestination(destination: Destination): Boolean {
        if (!destination.lat.isFinite() || !destination.lng.isFinite()) return false
        if (destination.lat !in -90.0..90.0) return false
        if (destination.lng !in -180.0..180.0) return false
        return true
    }
}
