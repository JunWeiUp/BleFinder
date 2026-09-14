package com.blefinder.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.blefinder.app.databinding.ActivityMainBinding
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    data class DeviceEntry(
        val mac: String,
        val name: String?,       // 设备广播的原始名称
        val customName: String?, // 用户自定义名称，优先显示
        val rssi: Int,
        val lastSeen: Long,
        val classic: Boolean
    ) {
        val displayName: String? get() = customName ?: name
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var scanManager: ScanManager
    private lateinit var sensorTracker: SensorTracker
    private lateinit var listAdapter: DeviceListAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanning = false
    private val devices = HashMap<String, DeviceEntry>()
    private val customNames = HashMap<String, String>()
    private val calibratedTx = HashMap<String, Double>()

    private var targetMac: String? = null

    // ---------- 追踪状态 ----------
    private var ema: Double? = null
    private val history = ArrayList<Float>()
    private val trendSamples = ArrayDeque<Pair<Long, Double>>()
    private var lastVibrateAt = 0L
    private var tone: ToneGenerator? = null
    private var beepLoopActive = false

    // ---------- 校准状态 ----------
    private var calibrationSamples: MutableList<Int>? = null

    // ---------- 罗盘 / 轨迹状态 ----------
    private val compassBins = FloatArray(CompassView.BIN_COUNT) { Float.NaN }
    private val trailPoints = ArrayList<TrailPoint>()
    private var trailX = 0.0
    private var trailY = 0.0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) afterPermissionsGranted()
        else Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { afterPermissionsGranted() }

    private val enableLocationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { afterPermissionsGranted() }

    private val refreshListRunnable = object : Runnable {
        override fun run() {
            if (scanning && targetMac == null) refreshDeviceList()
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        customNames.putAll(DeviceNameStore.all(this))
        calibratedTx.putAll(CalibrationStore.all(this))

        scanManager = ScanManager(this)
        scanManager.onDeviceFound = { found -> mainHandler.post { handleFound(found) } }

        sensorTracker = SensorTracker(this)
        sensorTracker.onRotation = { azScreen, _ ->
            if (binding.sectionCompass.isVisible) {
                binding.compassView.currentAzimuthDeg = azScreen
            }
        }
        sensorTracker.onStep = { azTop ->
            if (binding.sectionTrail.isVisible && ema != null) {
                trailX += STEP_LEN * sin(Math.toRadians(azTop.toDouble()))
                trailY += STEP_LEN * cos(Math.toRadians(azTop.toDouble()))
                trailPoints.add(TrailPoint(trailX, trailY, ema!!.toFloat()))
                binding.trailView.points = trailPoints.toList()
            }
        }

        listAdapter = DeviceListAdapter(
            onClick = { entry -> startTracking(entry.mac) },
            onRename = { entry -> showRenameDialog(entry.mac) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = listAdapter

        binding.btnScan.setOnClickListener { if (scanning) stopScan() else maybeStart() }
        binding.btnBack.setOnClickListener { stopTracking() }
        binding.btnRename.setOnClickListener { targetMac?.let { showRenameDialog(it) } }
        binding.btnCalibrate.setOnClickListener { targetMac?.let { showCalibrateDialog(it) } }
        binding.btnCompass.setOnClickListener { showTrackSection(SECTION_COMPASS) }
        binding.btnTrail.setOnClickListener { showTrackSection(SECTION_TRAIL) }
        binding.btnCompassClose.setOnClickListener { showTrackSection(SECTION_MAIN) }
        binding.btnTrailClose.setOnClickListener { showTrackSection(SECTION_MAIN) }
        binding.btnCompassReset.setOnClickListener {
            compassBins.fill(Float.NaN)
            binding.compassView.bins = compassBins.copyOf()
            binding.compassView.conclusionAzimuthDeg = Float.NaN
            binding.compassStatus.setText(R.string.compass_none)
        }
        binding.btnTrailClear.setOnClickListener {
            trailPoints.clear()
            trailX = 0.0
            trailY = 0.0
            binding.trailView.points = emptyList()
        }
        if (!sensorTracker.hasSensors) {
            binding.btnCompass.isEnabled = false
            binding.btnTrail.isEnabled = false
        }
        binding.checkBeep.setOnCheckedChangeListener { _, checked ->
            if (checked && targetMac != null && !beepLoopActive) {
                beepLoopActive = true
                mainHandler.post(beepRunnable)
            }
        }

        mainHandler.post(refreshListRunnable)
    }

    override fun onResume() {
        super.onResume()
        when {
            targetMac != null && !scanning -> {
                scanning = true
                scanManager.start()
            }
            !scanning -> maybeStart()
        }
    }

    override fun onPause() {
        super.onPause()
        if (scanning) stopScan()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        beepLoopActive = false
        tone?.release()
        tone = null
        scanManager.stop()
        sensorTracker.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    // ---------- 权限与蓝牙开关 ----------

    private fun maybeStart() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(perms)
            return
        }
        afterPermissionsGranted()
    }

    private fun afterPermissionsGranted() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !isLocationEnabled()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.location_needed_title)
                .setMessage(R.string.location_needed_msg)
                .setPositiveButton(R.string.go_settings) { _, _ ->
                    enableLocationLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        when {
            adapter == null -> Toast.makeText(this, R.string.no_bluetooth, Toast.LENGTH_LONG).show()
            !adapter.isEnabled ->
                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> startScan()
        }
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)
    }

    // ---------- 扫描 ----------

    private fun startScan() {
        if (scanning) return
        devices.clear()
        listAdapter.submitList(emptyList())
        scanning = true
        scanManager.start()
        binding.btnScan.setText(R.string.stop_scan)
        refreshDeviceList()
    }

    private fun stopScan() {
        scanning = false
        scanManager.stop()
        binding.btnScan.setText(R.string.start_scan)
        refreshDeviceList()
    }

    private fun handleFound(found: ScanManager.FoundDevice) {
        val target = targetMac
        if (target != null) {
            if (found.mac == target) updateTracking(found.rssi)
            return
        }
        if (!scanning) return
        val prev = devices[found.mac]
        devices[found.mac] = DeviceEntry(
            mac = found.mac,
            name = found.name ?: prev?.name,
            customName = customNames[found.mac],
            rssi = found.rssi,
            lastSeen = SystemClock.elapsedRealtime(),
            classic = found.classic || (prev?.classic == true)
        )
    }

    private fun refreshDeviceList() {
        val now = SystemClock.elapsedRealtime()
        devices.entries.removeAll { now - it.value.lastSeen > STALE_MS }
        val list = devices.values.sortedByDescending { it.rssi }
        listAdapter.submitList(list)
        binding.emptyView.isVisible = list.isEmpty()
        binding.emptyView.setText(if (scanning) R.string.empty_scanning else R.string.empty_stopped)
    }

    // ---------- 设备命名 ----------

    private fun showRenameDialog(mac: String) {
        val input = EditText(this).apply {
            hint = getString(R.string.rename_hint)
            setText(customNames[mac] ?: "")
            setSelection(text.length)
            isSingleLine = true
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(wrap)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                applyRename(mac, input.text.toString())
            }
            .setNeutralButton(R.string.clear_name) { _, _ -> applyRename(mac, "") }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyRename(mac: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            DeviceNameStore.put(this, mac, null)
            customNames.remove(mac)
        } else {
            DeviceNameStore.put(this, mac, trimmed)
            customNames[mac] = trimmed
        }
        devices[mac]?.let { devices[mac] = it.copy(customName = customNames[mac]) }
        if (targetMac == mac) {
            binding.trackName.text = devices[mac]?.displayName
                ?: getString(R.string.unknown_device)
        }
        refreshDeviceList()
    }

    // ---------- 1 米校准 ----------

    private fun showCalibrateDialog(mac: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.calibrate_title)
            .setMessage(R.string.calibrate_msg)
            .setPositiveButton(R.string.calibrate_start) { _, _ -> startCalibration(mac) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startCalibration(mac: String) {
        calibrationSamples = mutableListOf()
        binding.btnCalibrate.setText(R.string.calibrating)
        mainHandler.postDelayed({ finishCalibration(mac) }, CALIBRATION_MS + 200)
    }

    private fun finishCalibration(mac: String) {
        val samples = calibrationSamples ?: return
        calibrationSamples = null
        binding.btnCalibrate.setText(R.string.calibrate)
        if (targetMac != mac) return // 已退出追踪，丢弃本次采样
        if (samples.size < 6) {
            Toast.makeText(this, R.string.calibrate_failed, Toast.LENGTH_LONG).show()
            return
        }
        val tx = median(samples)
        calibratedTx[mac] = tx
        CalibrationStore.put(this, mac, tx)
        Toast.makeText(this, getString(R.string.calibrate_done, tx.roundToInt()), Toast.LENGTH_LONG).show()
        ema?.let { renderTracking(it, SystemClock.elapsedRealtime()) }
    }

    private fun median(values: List<Int>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid].toDouble()
        else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    // ---------- 追踪 ----------

    private fun startTracking(mac: String) {
        targetMac = mac
        ema = null
        history.clear()
        trendSamples.clear()
        binding.scanPanel.isVisible = false
        binding.trackPanel.isVisible = true
        showTrackSection(SECTION_MAIN)
        binding.trackName.text = devices[mac]?.displayName
            ?: getString(R.string.unknown_device)
        binding.trackMac.text = mac
        binding.textRssi.setText(R.string.waiting_signal)
        binding.textDistance.text = ""
        binding.textTrend.text = ""
        binding.signalBar.progress = 0
        binding.spark.values = emptyList()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tone = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (_: Exception) {
            null
        }
        sensorTracker.start()
        if (!scanning) {
            scanning = true
            scanManager.start()
        }
    }

    private fun stopTracking() {
        targetMac = null
        calibrationSamples = null
        binding.btnCalibrate.setText(R.string.calibrate)
        beepLoopActive = false
        mainHandler.removeCallbacks(beepRunnable)
        tone?.release()
        tone = null
        sensorTracker.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.trackPanel.isVisible = false
        binding.scanPanel.isVisible = true
    }

    private fun showTrackSection(section: Int) {
        binding.sectionMain.isVisible = section == SECTION_MAIN
        binding.sectionCompass.isVisible = section == SECTION_COMPASS
        binding.sectionTrail.isVisible = section == SECTION_TRAIL
        if (section == SECTION_COMPASS) {
            binding.compassView.bins = compassBins.copyOf()
            updateCompassConclusion()
        }
    }

    private fun updateTracking(rawRssi: Int) {
        val alpha = 0.25
        val e = ema?.let { it * (1 - alpha) + rawRssi * alpha } ?: rawRssi.toDouble()
        ema = e
        val now = SystemClock.elapsedRealtime()

        calibrationSamples?.add(rawRssi)

        history.add(e.toFloat())
        if (history.size > 150) history.removeAt(0)
        trendSamples.addLast(now to e)
        while (trendSamples.isNotEmpty() && now - trendSamples.first().first > 8000) {
            trendSamples.removeFirst()
        }

        if (binding.sectionCompass.isVisible) {
            val az = sensorTracker.lastAzimuthScreenDeg
            if (!az.isNaN()) {
                val idx = ((az / CompassView.BIN_DEGREES).toInt() + CompassView.BIN_COUNT) % CompassView.BIN_COUNT
                compassBins[idx] = if (compassBins[idx].isNaN()) e.toFloat()
                else compassBins[idx] * 0.7f + e.toFloat() * 0.3f
                binding.compassView.bins = compassBins.copyOf()
                updateCompassConclusion()
            }
        }

        renderTracking(e, now)

        if (binding.checkVibrate.isChecked &&
            computeTrend(now) >= 3.0 && e >= -85 && now - lastVibrateAt > 1200
        ) {
            lastVibrateAt = now
            vibrate()
        }
        if (!beepLoopActive && binding.checkBeep.isChecked) {
            beepLoopActive = true
            mainHandler.post(beepRunnable)
        }
    }

    private fun renderTracking(e: Double, now: Long) {
        binding.textRssi.text = getString(R.string.rssi_value, e.roundToInt())

        val pct = (((e + 100.0) / 60.0) * 100).coerceIn(0.0, 100.0).roundToInt()
        binding.signalBar.progress = pct
        binding.spark.values = history.toList()

        val zoneRes = when {
            e >= -55 -> R.string.zone_very_near
            e >= -67 -> R.string.zone_near
            e >= -80 -> R.string.zone_medium
            else -> R.string.zone_far
        }
        binding.textDistance.text =
            getString(R.string.distance_fmt, formatMeters(estimateMeters(e)), getString(zoneRes))
        binding.textDistance.setTextColor(
            ContextCompat.getColor(
                this,
                when (zoneRes) {
                    R.string.zone_very_near -> R.color.signal_green
                    R.string.zone_near -> R.color.signal_yellow
                    R.string.zone_medium -> R.color.signal_orange
                    else -> R.color.signal_red
                }
            )
        )

        val trend = computeTrend(now)
        val trendColor = when {
            trend >= TREND_THRESHOLD -> R.color.signal_green
            trend <= -TREND_THRESHOLD -> R.color.signal_red
            else -> R.color.text_secondary
        }
        binding.textTrend.setText(
            when {
                trend >= TREND_THRESHOLD -> R.string.trend_closer
                trend <= -TREND_THRESHOLD -> R.string.trend_farther
                else -> R.string.trend_stable
            }
        )
        binding.textTrend.setTextColor(ContextCompat.getColor(this, trendColor))
    }

    // ---------- 方向罗盘 ----------

    /**
     * 身体屏蔽原理：手机贴胸、屏幕朝外时，身体挡住身后的信号。
     * 信号最强的面朝方位 ≈ 设备方向；若多个不相邻方位都强（反射），置信度降为低。
     */
    private fun updateCompassConclusion() {
        val sampled = (0 until CompassView.BIN_COUNT).filter { !compassBins[it].isNaN() }
        if (sampled.size < 8) {
            binding.compassView.conclusionAzimuthDeg = Float.NaN
            binding.compassStatus.text = getString(R.string.compass_coverage, sampled.size)
            return
        }
        var bestIdx = -1
        var bestVal = Float.NEGATIVE_INFINITY
        compassBins.forEachIndexed { i, v ->
            if (!v.isNaN() && v > bestVal) {
                bestVal = v
                bestIdx = i
            }
        }
        var secondVal = Float.NEGATIVE_INFINITY
        sampled.forEach { i ->
            if (binDistance(i, bestIdx) >= 2 && compassBins[i] > secondVal) {
                secondVal = compassBins[i]
            }
        }
        val confident = secondVal == Float.NEGATIVE_INFINITY || bestVal - secondVal >= 4f
        val azimuth = bestIdx * CompassView.BIN_DEGREES + CompassView.BIN_DEGREES / 2f
        binding.compassView.conclusionAzimuthDeg = azimuth
        binding.compassStatus.text = getString(
            if (confident) R.string.compass_result_high else R.string.compass_result_low,
            directionName(azimuth)
        )
    }

    private fun binDistance(a: Int, b: Int): Int {
        val d = kotlin.math.abs(a - b)
        return minOf(d, CompassView.BIN_COUNT - d)
    }

    private fun directionName(azimuth: Float): String {
        val idx = (((azimuth + 22.5f) / 45f).toInt() % 8 + 8) % 8
        return getString(
            intArrayOf(
                R.string.dir_north, R.string.dir_northeast, R.string.dir_east,
                R.string.dir_southeast, R.string.dir_south, R.string.dir_southwest,
                R.string.dir_west, R.string.dir_northwest
            )[idx]
        )
    }

    // ---------- 距离与趋势 ----------

    /** 对数距离模型：d = 10^((txPower - rssi) / (10n))，优先用该设备的 1 米校准值 */
    private fun estimateMeters(e: Double): Double {
        val tx = targetMac?.let { calibratedTx[it] } ?: DEFAULT_TX_POWER
        return Math.pow(10.0, (tx - e) / 25.0)
    }

    private fun formatMeters(m: Double): String = when {
        m < 1.0 -> "<1"
        m > 30.0 -> "30+"
        else -> String.format(Locale.US, "%.0f", m)
    }

    /** 最近 2 秒均值 与 4~8 秒前均值 之差；正数表示信号在增强（靠近） */
    private fun computeTrend(now: Long): Double {
        val recent = trendSamples.filter { now - it.first <= 2000 }
        val older = trendSamples.filter { (now - it.first) in 4000..8000 }
        if (recent.size < 2 || older.isEmpty()) return 0.0
        return recent.map { it.second }.average() - older.map { it.second }.average()
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }

    /** 蜂鸣节奏随接近程度加快：远（约 1.2s 一次）→ 近（约 0.25s 一次） */
    private val beepRunnable = object : Runnable {
        override fun run() {
            val e = ema
            if (targetMac == null || e == null || !binding.checkBeep.isChecked) {
                beepLoopActive = false
                return
            }
            try {
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
            } catch (_: Exception) {
            }
            val pct = ((e + 100.0) / 60.0).coerceIn(0.0, 1.0)
            val delay = (1200 - 950 * pct).toLong()
            mainHandler.postDelayed(this, delay)
        }
    }

    companion object {
        private const val STALE_MS = 20_000L
        private const val TREND_THRESHOLD = 2.5
        private const val DEFAULT_TX_POWER = -59.0
        private const val CALIBRATION_MS = 3000L
        private const val STEP_LEN = 0.7
        private const val SECTION_MAIN = 0
        private const val SECTION_COMPASS = 1
        private const val SECTION_TRAIL = 2
    }
}
