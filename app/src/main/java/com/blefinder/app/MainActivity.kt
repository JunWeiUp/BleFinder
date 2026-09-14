package com.blefinder.app

import android.Manifest
import android.annotation.SuppressLint
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
import kotlin.math.roundToInt

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
    private lateinit var listAdapter: DeviceListAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanning = false
    private val devices = HashMap<String, DeviceEntry>()
    private val customNames = HashMap<String, String>()

    private var targetMac: String? = null

    // ---------- 追踪状态 ----------
    private var ema: Double? = null
    private val history = ArrayList<Float>()
    private val trendSamples = ArrayDeque<Pair<Long, Double>>()
    private var lastVibrateAt = 0L
    private var tone: ToneGenerator? = null
    private var beepLoopActive = false

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

        scanManager = ScanManager(this)
        scanManager.onDeviceFound = { found -> mainHandler.post { handleFound(found) } }

        listAdapter = DeviceListAdapter(
            onClick = { entry -> startTracking(entry.mac) },
            onRename = { entry -> showRenameDialog(entry.mac) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = listAdapter

        binding.btnScan.setOnClickListener { if (scanning) stopScan() else maybeStart() }
        binding.btnBack.setOnClickListener { stopTracking() }
        binding.btnRename.setOnClickListener { targetMac?.let { showRenameDialog(it) } }
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

    // ---------- 追踪 ----------

    private fun startTracking(mac: String) {
        targetMac = mac
        ema = null
        history.clear()
        trendSamples.clear()
        binding.trackName.text = devices[mac]?.displayName
            ?: getString(R.string.unknown_device)
        binding.trackMac.text = mac
        binding.scanPanel.isVisible = false
        binding.trackPanel.isVisible = true
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
        if (!scanning) {
            scanning = true
            scanManager.start()
        }
    }

    private fun stopTracking() {
        targetMac = null
        beepLoopActive = false
        mainHandler.removeCallbacks(beepRunnable)
        tone?.release()
        tone = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.trackPanel.isVisible = false
        binding.scanPanel.isVisible = true
    }

    private fun updateTracking(rawRssi: Int) {
        val alpha = 0.25
        val e = ema?.let { it * (1 - alpha) + rawRssi * alpha } ?: rawRssi.toDouble()
        ema = e
        val now = SystemClock.elapsedRealtime()

        history.add(e.toFloat())
        if (history.size > 150) history.removeAt(0)
        trendSamples.addLast(now to e)
        while (trendSamples.isNotEmpty() && now - trendSamples.first().first > 8000) {
            trendSamples.removeFirst()
        }

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

        if (binding.checkVibrate.isChecked &&
            trend >= 3.0 && e >= -85 && now - lastVibrateAt > 1200
        ) {
            lastVibrateAt = now
            vibrate()
        }
        if (!beepLoopActive && binding.checkBeep.isChecked) {
            beepLoopActive = true
            mainHandler.post(beepRunnable)
        }
    }

    /** 对数距离模型：d = 10^((A - rssi) / (10n))，A=-59dBm@1m，n=2.5（室内粗略值） */
    private fun estimateMeters(e: Double): Double = Math.pow(10.0, (-59.0 - e) / 25.0)

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
    }
}
