package com.blefinder.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * 双通道蓝牙扫描：
 * 1. BLE 扫描（BluetoothLeScanner，LOW_LATENCY 模式，更新频率最高）
 * 2. 经典蓝牙发现（startDiscovery，结果结束后 1.2s 自动重启循环）
 * 两个通道同时运行，覆盖只发 BLE 广播的设备（耳机盒/手环/追踪器）
 * 和只响应经典发现的传统设备。
 */
class ScanManager(private val context: Context) {

    data class FoundDevice(
        val name: String?,
        val mac: String,
        val rssi: Int,
        val classic: Boolean
    )

    /** 回调可能在 binder 线程触发，调用方需自行切回主线程 */
    var onDeviceFound: ((FoundDevice) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = safeName(device) ?: result.scanRecord?.deviceName
            onDeviceFound?.invoke(FoundDevice(name, device.address, result.rssi, false))
        }

        override fun onScanFailed(errorCode: Int) {
            android.util.Log.w("ScanManager", "BLE scan failed: $errorCode")
        }
    }

    private val restartDiscovery = Runnable {
        if (running) startClassicDiscovery()
    }

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                    ) ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0).toInt()
                    if (rssi == 0) return
                    onDeviceFound?.invoke(FoundDevice(safeName(device), device.address, rssi, true))
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    handler.removeCallbacks(restartDiscovery)
                    if (running) handler.postDelayed(restartDiscovery, 1200)
                }
            }
        }
    }

    fun start() {
        if (running) return
        val btAdapter = adapter ?: return
        running = true

        ContextCompat.registerReceiver(
            context, classicReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        startClassicDiscovery()

        try {
            btAdapter.bluetoothLeScanner?.startScan(
                null,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                bleCallback
            )
        } catch (_: SecurityException) {
            // 权限被用户中途收回，上层会重新走权限流程
        } catch (_: IllegalStateException) {
            // 蓝牙被关闭
        }
    }

    @SuppressLint("MissingPermission")
    private fun startClassicDiscovery() {
        try {
            adapter?.startDiscovery()
        } catch (_: SecurityException) {
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try {
            context.unregisterReceiver(classicReceiver)
        } catch (_: IllegalArgumentException) {
            // 未注册时忽略
        }
        try {
            val btAdapter = adapter
            btAdapter?.cancelDiscovery()
            btAdapter?.bluetoothLeScanner?.stopScan(bleCallback)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
            // scan callback 未注册时忽略
        }
    }

    /** Android 12+ 读取设备名需要 BLUETOOTH_CONNECT 运行时权限 */
    private fun safeName(device: BluetoothDevice): String? = try {
        if (Build.VERSION.SDK_INT < 31 || hasConnectPermission()) device.name else null
    } catch (_: SecurityException) {
        null
    }

    private fun hasConnectPermission() = ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED
}
