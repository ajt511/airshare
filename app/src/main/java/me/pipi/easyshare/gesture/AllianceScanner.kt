package me.pipi.easyshare.gesture

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import android.util.SparseArray
import me.pipi.easyshare.models.DiscoveredDevice
import me.pipi.easyshare.utils.BleUtils
import me.pipi.easyshare.utils.DeviceUtils
import me.pipi.easyshare.utils.TAG
import java.nio.ByteBuffer

/**
 * Plain (non-Compose) scanner for Share-Alliance BLE advertisements, used by
 * the gesture radar. Mirrors the scan logic in ShareActivity but exposes a
 * simple callback list so any screen can consume it.
 */
class AllianceScanner(private val context: Context) {
    private var callback: ScanCallback? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private val devices = mutableListOf<DiscoveredDevice>()
    private val lock = Any()

    fun start(onUpdate: (List<DiscoveredDevice>) -> Unit) {
        stop()
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val bleScanner = try {
            adapter?.bluetoothLeScanner
        } catch (error: Exception) {
            Log.w(TAG, "BLE scanner unavailable", error)
            null
        }
        if (bleScanner == null) {
            Log.w(TAG, "Bluetooth LE scanner is null; alliance scan unavailable")
            return
        }
        synchronized(lock) { devices.clear() }
        val scanCallback = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: $errorCode")
            }

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                var supports5Ghz = false
                var deviceName: String? = null
                var deviceDisplayName: String? = null
                var brandId: Int? = null
                var senderId: String? = null

                for ((uuid, data) in record.serviceData.entries) {
                    when (data.size) {
                        6 -> {
                            val buf = ByteBuffer.allocate(16)
                            buf.putLong(uuid.uuid.mostSignificantBits)
                            buf.putLong(uuid.uuid.leastSignificantBits)
                            val arr = buf.array()
                            supports5Ghz = arr[2].toInt() == 1
                            brandId = DeviceUtils.bleByteToBrandId(arr[3])
                        }
                        27 -> {
                            senderId = BleUtils.senderIdFromAdvertisement(data)
                            deviceName = BleUtils.deviceNameFromAdvertisement(data)
                            deviceDisplayName = BleUtils.deviceDisplayNameFromAdvertisement(data)
                        }
                    }
                }
                if (deviceName == null || senderId == null) return

                val device = DiscoveredDevice(
                    device = result.device,
                    id = senderId,
                    name = deviceName,
                    brandId = brandId,
                    brand = brandId?.let { DeviceUtils.knownDeviceNameById(it) },
                    supports5Ghz = supports5Ghz,
                    displayName = deviceDisplayName ?: deviceName,
                )
                val changed = synchronized(lock) {
                    val existing = devices.indexOfFirst { it.id == senderId }
                    if (existing >= 0) devices[existing] = device else devices.add(device)
                    true
                }
                if (changed) synchronized(lock) { onUpdate(devices.toList()) }
            }
        }
        callback = scanCallback
        try {
            bleScanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleUtils.ADV_SERVICE_UUID)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback,
            )
            scanner = bleScanner
            Log.d(TAG, "Gesture radar: alliance scan started")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start alliance scan", error)
            callback = null
        }
    }

    fun stop() {
        callback?.let {
            try {
                scanner?.stopScan(it)
            } catch (error: Exception) {
                Log.w(TAG, "Failed to stop alliance scan", error)
            }
        }
        callback = null
        scanner = null
        synchronized(lock) { devices.clear() }
    }
}
