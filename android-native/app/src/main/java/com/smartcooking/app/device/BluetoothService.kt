package com.smartcooking.app.device

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.smartcooking.app.core.KeyValueStore
import com.smartcooking.app.core.jsonOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class BtDevice(val name: String, val address: String, val bonded: Boolean) {
    val isJdy31: Boolean get() = name.uppercase(Locale.ROOT).contains("JDY-31")
}

enum class LinkState(val id: String) {
    IDLE("idle"), SCANNING("scanning"), CONNECTING("connecting"), CONNECTED("connected"), DISCONNECTED("disconnected"),
    RECONNECTING("reconnecting"), ERROR("error"), UNSUPPORTED("unsupported"), PERMISSION_DENIED("permission-denied"), BLUETOOTH_OFF("bluetooth-off");
}

data class DeviceConnection(
    val state: LinkState = LinkState.IDLE,
    val device: BtDevice? = null,
    val message: String = "",
    val errorCode: String = "",
) {
    val connected: Boolean get() = state == LinkState.CONNECTED
    val label: String
        get() = when (state) {
            LinkState.CONNECTED -> "已连接"
            LinkState.CONNECTING -> "连接中"
            LinkState.RECONNECTING -> "正在重连"
            LinkState.SCANNING -> "扫描中"
            LinkState.ERROR -> "连接异常"
            LinkState.UNSUPPORTED -> "不支持蓝牙"
            LinkState.PERMISSION_DENIED -> "需要附近设备权限"
            LinkState.BLUETOOTH_OFF -> "蓝牙关闭"
            else -> "未连接"
        }
}

class BluetoothException(val code: String, message: String) : Exception(message) {
    val userMessage: String
        get() = mapOf(
            "BLUETOOTH_UNSUPPORTED" to "此手机不支持蓝牙",
            "BLUETOOTH_DISABLED" to "请开启手机蓝牙后继续",
            "PERMISSION_DENIED" to "请允许 CookX 使用附近设备权限",
            "SCAN_FAILED" to "蓝牙扫描启动失败，请稍后重试",
            "SCAN_BUSY" to "正在连接、已连接或正在扫描，请先结束当前操作",
            "DEVICE_NOT_FOUND" to "请选择扫描到的 JDY-31 设备",
            "CONNECT_FAILED" to "无法连接 JDY-31，请确认模块已上电并靠近手机",
            "CONNECTION_LOST" to "CookX Sense 连接已断开",
            "NOT_CONNECTED" to "设备尚未连接",
            "WRITE_FAILED" to "蓝牙数据发送失败",
        )[code] ?: message.orEmpty()
}

/**
 * Classic Bluetooth SPP link to the CookX Sense (JDY-31) probe, owned by the application so that
 * screens only subscribe. Port of the Capacitor `TemperatureBluetoothPlugin` plus `deviceHub`.
 */
@SuppressLint("MissingPermission")
class BluetoothService(private val context: Context, private val store: KeyValueStore) {
    private val tag = "CookXBluetooth"
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val discovered = ConcurrentHashMap<String, BluetoothDevice>()

    private val _connection = MutableStateFlow(DeviceConnection())
    val connection: StateFlow<DeviceConnection> = _connection.asStateFlow()
    private val _devices = MutableStateFlow<List<BtDevice>>(emptyList())
    val devices: StateFlow<List<BtDevice>> = _devices.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _samples = MutableSharedFlow<TemperatureSample>(extraBufferCapacity = 64)
    /** Parsed samples, only while connected. */
    val samples: SharedFlow<TemperatureSample> = _samples
    private val _latest = MutableStateFlow<TemperatureSample?>(null)
    /** Most recent sample while connected; cleared when the link or data goes stale. */
    val latest: StateFlow<TemperatureSample?> = _latest.asStateFlow()
    private val _lastFrameAt = MutableStateFlow<Long?>(null)
    val lastFrameAt: StateFlow<Long?> = _lastFrameAt.asStateFlow()

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var currentDevice: BluetoothDevice? = null
    @Volatile private var userDisconnected = false
    @Volatile private var reconnectAttempt = 0
    private var readerJob: Job? = null
    private var connectJob: Job? = null
    private var discoveryToken = 0L
    private var discoveryActive = false
    private var discoveryOutcome = "not-started"
    private var receiverRegistered = false

    private val records = ArrayDeque<JsonObject>()
    private fun record(type: String, data: Any?) {
        synchronized(records) {
            records.addLast(jsonOf("at" to System.currentTimeMillis(), "type" to type, "data" to data))
            while (records.size > 2000) records.removeFirst()
        }
    }

    private val parser = TemperatureStreamParser(
        onSample = { sample ->
            _lastFrameAt.value = System.currentTimeMillis()
            record("sample", jsonOf("temperature" to sample.temperature, "valid" to sample.valid, "seq" to sample.sequence, "ambient" to sample.ambientTemperature))
            if (_connection.value.connected) { _latest.value = sample; _samples.tryEmit(sample) }
        },
        onReject = { record("rejected-frame", jsonOf("reason" to it)) },
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (adapter?.isDiscovering != true) finishDiscovery(discoveryToken, "finished")
                BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null && (discoveryActive || intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED)) publish(device)
                }
            }
        }
    }

    val supported: Boolean get() = adapter != null
    val enabled: Boolean get() = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
    val savedAddress: String? get() = store.get(LAST_ADDRESS)

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(): Boolean = requiredPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    private fun ensureReady() {
        if (adapter == null) throw BluetoothException("BLUETOOTH_UNSUPPORTED", "Bluetooth is not supported")
        if (!hasPermissions()) throw BluetoothException("PERMISSION_DENIED", "Bluetooth permission is required")
        if (!enabled) throw BluetoothException("BLUETOOTH_DISABLED", "Please enable Bluetooth")
    }

    private fun registerReceiverOnce() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED); addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // Bluetooth broadcasts come from a privileged UID; NOT_EXPORTED would drop them on recent Android.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun setState(state: LinkState, device: BluetoothDevice? = currentDevice, message: String = "", code: String = "") {
        val next = DeviceConnection(state, device?.let(::describe), message, code)
        _connection.value = next
        if (state != LinkState.CONNECTED) { parser.reset(); _lastFrameAt.value = null; _latest.value = null }
        record("connection", jsonOf("state" to state.id, "message" to message, "errorCode" to code))
    }

    private fun describe(device: BluetoothDevice): BtDevice {
        val name = runCatching { device.name }.getOrNull() ?: "Unknown device"
        val address = runCatching { device.address }.getOrNull().orEmpty()
        val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        return BtDevice(name, address, bonded)
    }

    private fun publish(device: BluetoothDevice) {
        val info = runCatching { describe(device) }.getOrNull() ?: return
        if (info.address.isEmpty()) return
        discovered[info.address.uppercase(Locale.ROOT)] = device
        _devices.value = (_devices.value.filterNot { it.address.equals(info.address, true) } + info).sortedByDescending { it.isJdy31 }
        record("device-found", jsonOf("name" to info.name, "address" to info.address, "bonded" to info.bonded))
    }

    /** Lists bonded devices immediately, then scans for up to 25 seconds. Returns false if scanning did not start. */
    fun startDiscovery(): Boolean {
        ensureReady()
        val s = _connection.value.state
        if (s == LinkState.CONNECTED || s == LinkState.CONNECTING || s == LinkState.RECONNECTING || discoveryActive) throw BluetoothException("SCAN_BUSY", "busy")
        registerReceiverOnce()
        discovered.clear(); _devices.value = emptyList()
        val adapter = adapter!!
        adapter.bondedDevices.orEmpty().forEach(::publish)
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        val token = ++discoveryToken
        discoveryActive = true
        val started = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        record("discovery-request", jsonOf("started" to started))
        if (started) {
            discoveryOutcome = "scanning"; _scanning.value = true
            setState(LinkState.SCANNING, null)
            main.postDelayed({ if (finishDiscovery(token, "timeout")) runCatching { adapter.cancelDiscovery() } }, 25_000)
        } else finishDiscovery(token, "start-failed")
        return started
    }

    fun stopDiscovery() {
        finishDiscovery(discoveryToken, "stopped")
        runCatching { if (adapter?.isDiscovering == true) adapter.cancelDiscovery() }
    }

    private fun finishDiscovery(token: Long, reason: String): Boolean {
        if (!discoveryActive || token != discoveryToken) return false
        discoveryActive = false; discoveryOutcome = reason; _scanning.value = false
        if (_connection.value.state == LinkState.SCANNING) setState(LinkState.IDLE, null, reason, if (reason == "timeout") "SCAN_TIMEOUT" else "")
        record("discovery-finished", jsonOf("reason" to reason, "devices" to discovered.size))
        return true
    }

    /** Connects to the address (or the last used device); insecure RFCOMM first, then secure. */
    suspend fun connect(address: String?): BtDevice {
        ensureReady()
        val target = address?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: savedAddress
        if (target == null || !BluetoothAdapter.checkBluetoothAddress(target)) throw BluetoothException("DEVICE_NOT_FOUND", "Select a device")
        val device = discovered[target] ?: runCatching { adapter!!.getRemoteDevice(target) }.getOrElse { throw BluetoothException("DEVICE_NOT_FOUND", "Unknown device") }
        stopDiscovery()
        userDisconnected = false; reconnectAttempt = 0; currentDevice = device
        return withContext(Dispatchers.IO) { openLink(device, reconnecting = false) }
    }

    private fun openLink(device: BluetoothDevice, reconnecting: Boolean): BtDevice {
        setState(if (reconnecting) LinkState.RECONNECTING else LinkState.CONNECTING, device)
        try {
            try { openSocket(device, secure = false) } catch (e: IOException) {
                Log.w(tag, "Insecure RFCOMM failed; trying secure", e)
                closeResources(); openSocket(device, secure = true)
            }
        } catch (e: SecurityException) {
            setState(LinkState.ERROR, device, "Bluetooth connect permission is missing", "PERMISSION_DENIED")
            throw BluetoothException("PERMISSION_DENIED", "permission")
        } catch (e: IOException) {
            closeResources()
            if (reconnecting && !userDisconnected && reconnectAttempt < 3) { scheduleReconnect(device); throw BluetoothException("CONNECT_FAILED", "retrying") }
            setState(LinkState.ERROR, device, "无法连接 JDY-31", "CONNECT_FAILED")
            throw BluetoothException("CONNECT_FAILED", e.message ?: "connect failed")
        }
        reconnectAttempt = 0
        describe(device).address.takeIf { it.isNotEmpty() }?.let { store.put(LAST_ADDRESS, it) }
        setState(LinkState.CONNECTED, device)
        readerJob?.cancel()
        readerJob = scope.launch { readLoop() }
        return describe(device)
    }

    private fun openSocket(device: BluetoothDevice, secure: Boolean) {
        val candidate = if (secure) device.createRfcommSocketToServiceRecord(sppUuid) else device.createInsecureRfcommSocketToServiceRecord(sppUuid)
        socket = candidate
        candidate.connect()
        input = candidate.inputStream; output = candidate.outputStream
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(1024)
        try {
            while (scope.isActive) {
                val stream = input ?: break
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                val chunk = String(buffer, 0, count, StandardCharsets.US_ASCII)
                record("raw-frame", jsonOf("chunk" to chunk.take(4096)))
                withContext(Dispatchers.Main) { parser.append(chunk) }
            }
        } catch (e: IOException) {
            Log.w(tag, "SPP stream closed", e)
        } finally {
            if (!userDisconnected) connectionLost()
        }
    }

    private fun connectionLost() {
        val device = currentDevice
        closeResources()
        setState(LinkState.DISCONNECTED, device, "Connection lost", "CONNECTION_LOST")
        if (device != null && reconnectAttempt < 3) scheduleReconnect(device)
        else setState(LinkState.ERROR, device, "重连次数已用尽", "RECONNECT_EXHAUSTED")
    }

    private fun scheduleReconnect(device: BluetoothDevice) {
        reconnectAttempt++
        val delaySeconds = 1L shl (reconnectAttempt - 1)
        setState(LinkState.RECONNECTING, device, "Retry $reconnectAttempt of 3")
        connectJob?.cancel()
        connectJob = scope.launch {
            delay(delaySeconds * 1000)
            if (!userDisconnected) runCatching { openLink(device, reconnecting = true) }
        }
    }

    fun disconnect() {
        userDisconnected = true; reconnectAttempt = 3
        connectJob?.cancel(); readerJob?.cancel()
        closeResources()
        setState(LinkState.DISCONNECTED, currentDevice, "User disconnected", "USER_DISCONNECTED")
        currentDevice = null
    }

    fun write(data: String, hex: Boolean = false) {
        val out = output
        if (_connection.value.state != LinkState.CONNECTED || out == null) throw BluetoothException("NOT_CONNECTED", "not connected")
        val bytes = if (hex) data.replace(Regex("\\s+"), "").chunked(2).map { it.toInt(16).toByte() }.toByteArray() else data.toByteArray()
        try { out.write(bytes); out.flush() } catch (e: IOException) { throw BluetoothException("WRITE_FAILED", "write failed") }
    }

    /** Re-reads platform state after returning to the foreground; waits for a fresh sample. */
    fun reconcile(): DeviceConnection {
        parser.reset(); _lastFrameAt.value = null; _latest.value = null
        record("foreground-check", null)
        val linked = socket?.isConnected == true && _connection.value.state == LinkState.CONNECTED
        val derived = when {
            adapter == null -> LinkState.UNSUPPORTED
            !hasPermissions() -> LinkState.PERMISSION_DENIED
            !enabled -> LinkState.BLUETOOTH_OFF
            linked -> LinkState.CONNECTED
            _connection.value.state == LinkState.CONNECTED -> LinkState.DISCONNECTED
            else -> _connection.value.state
        }
        if (derived != _connection.value.state) setState(derived, currentDevice)
        return _connection.value
    }

    /** Called every second by the app; data older than 5 s is no longer shown as current. */
    fun expireStale(now: Long = System.currentTimeMillis()) {
        val last = _lastFrameAt.value ?: return
        if (now - last >= TEMPERATURE_STALE_MS && _latest.value != null) {
            _latest.value = null
            record("data-timeout", jsonOf("lastFrameAt" to last))
        }
    }

    fun onBackground() = record("background", jsonOf("continuousCollectionGuaranteed" to false))

    /** Diagnostics export: device info, connection events and raw frames. */
    fun diagnostics(): JsonObject {
        val appVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
        val info = jsonOf(
            "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "androidRelease" to Build.VERSION.RELEASE,
            "sdkInt" to Build.VERSION.SDK_INT, "appVersion" to appVersion, "transport" to "Bluetooth Classic SPP",
            "permissionsGranted" to hasPermissions(), "bluetoothEnabled" to enabled, "discoveryOutcome" to discoveryOutcome,
            "knownDeviceCount" to discovered.size, "bluetoothFix" to "discovery-v2-native",
        )
        val list = synchronized(records) { records.toList() }
        return jsonOf(
            "schemaVersion" to 1, "source" to "native-device-diagnostics", "exportedAt" to System.currentTimeMillis(),
            "info" to info, "state" to _connection.value.state.id, "lastFrameAt" to _lastFrameAt.value,
            "records" to JsonArray(list), "hardwareAcceptance" to "待真机验收；日志不构成识别准确率结论",
        )
    }

    fun dispose() {
        stopDiscovery()
        disconnect()
        if (receiverRegistered) { runCatching { context.unregisterReceiver(receiver) }; receiverRegistered = false }
        scope.cancel()
    }

    private fun closeResources() {
        runCatching { input?.close() }; runCatching { output?.close() }; runCatching { socket?.close() }
        input = null; output = null; socket = null
    }

    companion object { private const val LAST_ADDRESS = "cookx_last_jdy31_address" }
}
