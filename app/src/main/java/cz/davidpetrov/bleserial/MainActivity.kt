package cz.davidpetrov.bleserial

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cz.davidpetrov.bleserial.ui.theme.BleSerialTheme
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val TAG = "BLE"
    private val DEVICE_NAME = "Zobo"

    // NUS UUIDs (Nordic UART Service)
    private val UART_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val UART_RX_UUID      = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val UART_TX_UUID      = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CCCD_UUID         = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null

    // UI state
    private var isScanning = mutableStateOf(false)
    private var isConnected = mutableStateOf(false)
    private var lastDeviceName = mutableStateOf<String?>(null)

    // Log of all messages (thread-safe updates via runOnUiThread)
    private val logLines = mutableStateListOf<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // Runtime permissions launcher
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* after granting you can restart scan */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestBlePermissions()

        val btMgr = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        scanner = btMgr.adapter?.bluetoothLeScanner

        setContent {
            BleSerialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        isScanning = isScanning.value,
                        isConnected = isConnected.value,
                        deviceName = lastDeviceName.value,
                        messages = logLines,                       // pass log list to UI
                        onStartScan = { startBleScan() },
                        onStopScan = { stopBleScan() },
                        onDisconnect = { disconnectGatt() },
                        onSend = { sendLine(it) },
                        onSendByteBlue = { sendByte(30) },
                        onSendByteRed = { sendByte(20) },
                        onSendByteGreen = { sendByte(10) },
                        onSendByteLight = { sendByte(40) },
                        onClearLog = { logLines.clear() }          // clear the log
                    )
                }
            }
        }
    }

    /* ---------------- Permissions & Location ---------------- */

    private fun hasBlePermissions(): Boolean =
        Permissions.scanPerms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestBlePermissions() {
        val missing = Permissions.scanPerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun promptEnableLocation() {
        Toast.makeText(this, "Zapni prosím Polohu (Location), aby šel BLE scan.", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    /* ---------------- Scan ---------------- */

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val nameDev = dev.name
            val nameRec = result.scanRecord?.deviceName
            val rssi = result.rssi

            Log.d(TAG, "Found: mac=${dev.address} rssi=$rssi name(dev)=${nameDev ?: "-"} name(rec)=${nameRec ?: "-"}")

            val matches = (nameDev?.contains(DEVICE_NAME, ignoreCase = true) == true) ||
                    (nameRec?.contains(DEVICE_NAME, ignoreCase = true) == true)

            if (matches) {
                Log.d(TAG, ">> Match '$DEVICE_NAME' → connecting to ${dev.address}")
                stopBleScan()
                connectGatt(dev)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (!hasBlePermissions()) { requestBlePermissions(); return }
        if (Build.VERSION.SDK_INT in 26..30 && !isLocationEnabled()) {
            promptEnableLocation(); return
        }

        val btMgr = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btMgr.adapter ?: run { Log.e(TAG, "Bluetooth adapter == null"); return }
        if (!adapter.isEnabled) { Log.e(TAG, "Bluetooth is OFF"); return }
        if (isScanning.value) return

        try {
            Log.d(TAG, "Scanning… Looking for name contains '$DEVICE_NAME'")
            scanner = adapter.bluetoothLeScanner
            scanner?.startScan(scanCallback)
            isScanning.value = true
        } catch (e: SecurityException) {
            Log.e(TAG, "No permission: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!isScanning.value) return
        try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
        isScanning.value = false
        Log.d(TAG, "Scanning stopped.")
    }

    /* ---------------- GATT + NUS ---------------- */

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        gatt = if (Build.VERSION.SDK_INT >= 33) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    private fun addLog(direction: String, payload: String) {
        val ts = timeFmt.format(Date())
        val line = "[$ts] $direction $payload"
        Log.d(TAG, line)
        runOnUiThread { logLines.add(line) } // BLE callback is not on UI thread
    }

    // Single place to handle incoming UART notifications
    private fun handleUartNotification(bytes: ByteArray?) {
        val text = bytes?.toString(StandardCharsets.UTF_8) ?: ""
        addLog("←", text)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                addLog("ERR", "GATT error: $status"); disconnectGatt(); return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                lastDeviceName.value = gatt.device.name
                addLog("INFO", "Connected → discovering services…")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                addLog("INFO", "Disconnected")
                isConnected.value = false
                lastDeviceName.value = null
                rxChar = null; txChar = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { addLog("ERR", "Service discovery failed: $status"); return }

            val svc = gatt.getService(UART_SERVICE_UUID) ?: run {
                addLog("ERR", "UART service not found"); return
            }
            rxChar = svc.getCharacteristic(UART_RX_UUID)
            txChar = svc.getCharacteristic(UART_TX_UUID)
            if (rxChar == null || txChar == null) { addLog("ERR", "UART characteristics missing"); return }

            // Enable notifications on TX characteristic
            gatt.setCharacteristicNotification(txChar, true)
            val cccd = txChar!!.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    // New API: pass the value directly
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        // Legacy path: set value on descriptor then call deprecated write
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(cccd)
                    }
                }
            } else {
                addLog("WARN", "CCCD not found – some firmwares still notify")
            }

            isConnected.value = true
            addLog("INFO", "UART ready; requesting MTU 247")
            if (Build.VERSION.SDK_INT >= 21) gatt.requestMtu(247)
        }

        // New callback (API 33+): includes value param
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == UART_TX_UUID) {
                handleUartNotification(value)
            }
        }

        // Legacy callback (API ≤ 32): use characteristic.value
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == UART_TX_UUID) {
                handleUartNotification(characteristic.value)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            addLog("INFO", "MTU=$mtu status=$status")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendLine(text: String) {
        val g = gatt ?: run { addLog("WARN", "Not connected"); return }
        val ch = rxChar ?: run { addLog("WARN", "RX characteristic missing"); return }

        val bytes = (text + "\n").toByteArray(StandardCharsets.UTF_8)
        if (Build.VERSION.SDK_INT >= 33) {
            // Non-deprecated overload for API 33+
            g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            run {
                // Legacy path for API < 33
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = bytes
                g.writeCharacteristic(ch)
            }
        }
        addLog("→", text)
    }

    @SuppressLint("MissingPermission")
    private fun sendByte(value: Int) {
        val g = gatt ?: run { addLog("WARN", "Not connected"); return }
        val ch = rxChar ?: run { addLog("WARN", "RX characteristic missing"); return }

        val bytes = byteArrayOf(value.toByte())
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = bytes
                g.writeCharacteristic(ch)
            }
        }
        addLog("→", "[byte] $value (0x${value.toString(16)})")
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        rxChar = null
        txChar = null
        isConnected.value = false
        lastDeviceName.value = null
        addLog("INFO", "Closed GATT")
    }
}

/* ---------------- UI ---------------- */

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    isScanning: Boolean,
    isConnected: Boolean,
    deviceName: String?,
    messages: List<String>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
    onSend: (String) -> Unit,
    onSendByteBlue: () -> Unit,
    onSendByteRed: () -> Unit,
    onSendByteGreen: () -> Unit,
    onSendByteLight: () -> Unit,
    onClearLog: () -> Unit
) {
    var msg by remember { mutableStateOf("Hello ESP32") }

    // Remember and control the scroll state of the log
    val listState = rememberLazyListState()

    // Auto-scroll to the latest message whenever the list grows
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1) // or use scrollToItem for instant jump
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            when {
                isConnected -> "Connected to: ${deviceName ?: "(unknown)"}"
                isScanning -> "Scanning… (na Android 8–11 musí být Location zapnutá)"
                else -> "Idle"
            }
        )

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onStartScan, enabled = !isScanning && !isConnected) { Text("Start scan") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onStopScan, enabled = isScanning) { Text("Stop scan") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(onClick = onDisconnect, enabled = isConnected) { Text("Disconnect") }
            OutlinedButton(onClick = onClearLog) { Text("Clear log") }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = msg,
            onValueChange = { msg = it },
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { onSend(msg) }, enabled = isConnected) { Text("Send") }
            Spacer(Modifier.width(8.dp))
            Column {   // stack control buttons vertically
                Button(onClick = onSendByteBlue, enabled = isConnected) { Text("Send byte 30 (0x1E)") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onSendByteRed, enabled = isConnected) { Text("Send byte 20 (0x14)") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onSendByteGreen, enabled = isConnected) { Text("Send byte 10 (0x0A)") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onSendByteLight, enabled = isConnected) { Text("Send byte 40 (0x28)") }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("Log:")
        Spacer(Modifier.height(8.dp))

        // Scrollable log list – takes the remaining height
        LazyColumn(
            state = listState, // attach list state for programmatic scrolling
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(4.dp)
        ) {
            items(messages) { line ->
                Text(
                    line,
                    maxLines = 1,                          // show one line
                    overflow = TextOverflow.Ellipsis,      // add "…" if too long
                    style = MaterialTheme.typography.bodySmall
                )
                HorizontalDivider(thickness = 0.5.dp)      // new non-deprecated divider
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewMain() {
    val demo = listOf(
        "[12:00:01.001] INFO Connected → discovering services…",
        "[12:00:01.150] INFO UART ready; requesting MTU 247",
        "[12:00:01.200] INFO MTU=247 status=0",
        "[12:00:02.000] → Hello ESP32",
        "[12:00:02.050] ← LED_BLUE_ON"
    )
    BleSerialTheme {
        MainScreen(
            isScanning = false,
            isConnected = true,
            deviceName = "Zobo",
            messages = demo,
            onStartScan = {},
            onStopScan = {},
            onDisconnect = {},
            onSend = {},
            onSendByteBlue = {},
            onSendByteRed = {},
            onSendByteGreen = {},
            onSendByteLight = {},
            onClearLog = {}
        )
    }
}
