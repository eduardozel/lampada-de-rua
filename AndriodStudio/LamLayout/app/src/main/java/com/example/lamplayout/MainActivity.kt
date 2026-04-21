package com.example.lamplayout


import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.net.*
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View

import androidx.core.widget.doAfterTextChanged

import android.view.inputmethod.EditorInfo

import android.widget.Toast

import androidx.appcompat.widget.Toolbar
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Button
import androidx.appcompat.app.AlertDialog

import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.lamplayout.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit

import android.net.wifi.WifiNetworkSpecifier

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager

    private lateinit var prefs: SharedPreferences
    private lateinit var toolbar: Toolbar

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var requestNetwork: NetworkRequest? = null
    private var webSocket: WebSocket? = null

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.d(TAG, "Все разрешения получены")
        } else {
            showToast("Необходимы разрешения для работы с WiFi")
            binding.btnToggle.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("Lamp_Settings", Context.MODE_PRIVATE)

        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        connectivityManager = applicationContext.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        requestPermissionsIfNeeded()
        setupListeners()
        updateToolbarTitle()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.INTERNET
        )
        // На Android 10+ нужен доступ к локации для сканирования WiFi
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // На Android 13+ нужен доступ к Nearby Wi-Fi Devices для управления Wi-Fi напрямую
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun setupListeners() {

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        binding.btnToggle.setOnClickListener {
            if (binding.btnToggle.isEnabled) {
                toggleLamp()
            }
        }
        binding.btnClose.setOnClickListener {
            cleanup()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            } else {
                finishAffinity()
            }
        }
    }
// * * * * * * * *

private fun updateToolbarTitle() {
    val wifiKey = prefs.getString( WIFI_SSD, "")
    supportActionBar?.title = if (wifiKey.isNullOrEmpty()) {
        "Lamp"
    } else {
        "Lamp: $wifiKey"
    }
}

override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.main_menu, menu)
    return true
}

    // Обработка нажатия на пункты меню
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_wifi_settings, null)
        val editText = dialogView.findViewById<EditText>(R.id.etWifiKey)
        val editPWD = dialogView.findViewById<EditText>(R.id.etWifiPwd)

        editText.setText(prefs.getString(WIFI_SSD, ""))
        editText.setSelection(editText.text.length)

        editPWD.setText(prefs.getString(WIFI_PWD, ""))

        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        editText.doAfterTextChanged { editable ->
            val hasText = !editable.toString().trim().isEmpty()
            btnSave.isEnabled = hasText
            btnSave.alpha = if (hasText) 1.0f else 0.5f
        }

        val dialog = AlertDialog.Builder(this, R.style.CustomAlertDialog)
            .setTitle("WiFi")
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val wifiValue = editText.text.toString().trim()
            prefs.edit().putString(WIFI_SSD, wifiValue).apply()

            val wifiPWD = editPWD.text.toString().trim()
            prefs.edit().putString(WIFI_PWD, wifiPWD).apply()

            updateToolbarTitle()
            Toast.makeText(this, "WiFi сохранён: $wifiValue", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btnSave.performClick()
                true
            } else false
        }

        editText.post {
            editText.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        dialog.show()



    }

//  * * * * * * * *
    private fun toggleLamp() {
        lifecycleScope.launch {
            setUiLoading(true)
            try {
                updateStatus("Отключение от текущей сети...")
                disconnectFromCurrentWifi()

                delay(1000)

                updateStatus("Подключение к lamp1...")
                val connected = connectToLampWifi()
                if (!connected) {
                    throw Exception("Не удалось подключиться к lamp1")
                }

                delay(1500) // Ждём IP
                updateStatus("Отправка команды...")
                sendToggleCommand()

                updateStatus("✅ Команда отправлена!")
                showToast("Команда отправлена")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка: ${e.message}", e)
                updateStatus("❌ Ошибка: ${e.message}")
                showToast("Ошибка: ${e.message}")
            } finally {
                updateStatus("Восстановление подключения...")
                disconnectFromLampWifi()
                delay(500)

                setUiLoading(false)
            }
        }
    }

    private fun disconnectFromCurrentWifi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "Android 10+: отключение принудительно не поддерживается")
            // Можно попытаться сбросить привязку к сети, чтобы вернуть штатное состояние
            connectivityManager.bindProcessToNetwork(null)
        } else {
            @Suppress("DEPRECATION")
            wifiManager.disconnect()
        }
    }

    private suspend fun connectToLampWifi(): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectWithNetworkSpecifier()
        } else {
            connectWithLegacyWifi()
        }
    }

    private suspend fun connectWithNetworkSpecifier(): Boolean = suspendCancellableCoroutine { cont ->
        try {

            val wf_ssid = prefs.getString("wifi_ssid", "").orEmpty()
            val wf_pass = prefs.getString("wifi_pwd", "").orEmpty()

/*
            if (wf_ssid.isBlank() || wf_pass.isBlank()) {
                Log.w(TAG, "Настройки WiFi не заполнены")
                runOnUiThread { showToast("Заполните настройки WiFi!") }
                cont.resume(false)
                return@suspendCancellableCoroutine
            }
*/

            val specifierBuilder = WifiNetworkSpecifier.Builder()
                .setSsid(wf_ssid)
                .setWpa2Passphrase(wf_pass)

            val specifier = specifierBuilder.build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            requestNetwork = request

            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Сеть lamp1 доступна")
                    connectivityManager.bindProcessToNetwork(network)
                    if (cont.isActive) cont.resume(true) {}
                }

                override fun onUnavailable() {
                    Log.e(TAG, "Сеть lamp1 недоступна")
                    if (cont.isActive) cont.resume(false) {}
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Сеть lamp1 потеряна")
                    connectivityManager.bindProcessToNetwork(null)
                }
            }

            wifiCallback = networkCallback
            connectivityManager.requestNetwork(request, networkCallback)

            cont.invokeOnCancellation {
                wifiCallback?.let { callback ->
                    connectivityManager.unregisterNetworkCallback(callback)
                }
                wifiCallback = null
                requestNetwork = null
                connectivityManager.bindProcessToNetwork(null)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка NetworkSpecifier: ${e.message}", e)
            if (cont.isActive) cont.resume(false) {}
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun connectWithLegacyWifi(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!wifiManager.isWifiEnabled) {
                wifiManager.isWifiEnabled = true
                delay(1500)
            }

            val wifiConfig = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"lamp1\""
                preSharedKey = "\"lamp4567\""
                allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
            }

            val networkId = wifiManager.addNetwork(wifiConfig)
            if (networkId == -1) {
                Log.e(TAG, "Не удалось добавить конфигурацию WiFi")
                return@withContext false
            }

            wifiManager.disconnect()
            val enabled = wifiManager.enableNetwork(networkId, true)
            val reconnected = wifiManager.reconnect()

            if (!enabled || !reconnected) {
                Log.e(TAG, "Не удалось включить или переподключиться к сети")
                return@withContext false
            }

            repeat(10) {
                delay(500)
                val info = wifiManager.connectionInfo
                val connectedSsid = info.ssid?.replace("\"", "")
                if (connectedSsid == "lamp1" && info.networkId != -1) {
                    return@withContext true
                }
            }

            Log.e(TAG, "Не удалось подключиться к lamp1 в отведённое время")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Ошибки при Legacy WiFi: ${e.message}", e)
            false
        }
    }
// ** * * sendToggleCommand
private suspend fun sendToggleCommand() = withContext(Dispatchers.IO) {
//    val url = "ws://192.168.4.1/ws"

    val request = Request.Builder()
        .url( WS_URL )
        .build()

    val webSocketResult = CompletableDeferred<Boolean>()

    val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected")
            runOnUiThread { showToast("WebSocket connected") }

            val command = """{"act": "togglelight"}"""
            val sent = webSocket.send(command)
            Log.d(TAG, "Команда отправлена: $command, send() = $sent")
            runOnUiThread { showToast("Команда отправлена") }

            if (!sent) {
                webSocketResult.completeExceptionally(Exception("Не удалось отправить команду"))
            }
        } // onOpen

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Получено сообщение от лампы: $text")
            runOnUiThread { showToast("Ответ лампы: $text") }

            if (text.contains("OK", ignoreCase = true) || text.contains("Success", ignoreCase = true)) {
                if (!webSocketResult.isCompleted)
                    webSocketResult.complete(true)
                webSocket.close(1000, "Команда выполнена")
            }
        } // onMessage

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Ошибка WebSocket: ${t.message}")
            runOnUiThread { showToast("Ошибка WebSocket: ${t.message}") }
            if (!webSocketResult.isCompleted) {
                webSocketResult.completeExceptionally(t)
            }
        } // onFailure

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket закрыт: $reason")
            runOnUiThread { showToast("WebSocket закрыт: $reason") }
            if (!webSocketResult.isCompleted) {
                webSocketResult.complete(true)
            }
        } // onClosed
    } // listener

    webSocket = okHttpClient.newWebSocket(request, listener)

    try {
        withTimeout(5000) {
            webSocketResult.await()
        }
        runOnUiThread { showToast("Команда успешно выполнена") }
    } catch (e: TimeoutCancellationException) {
        Log.e(TAG, "Таймаут ожидания ответа от лампы")
        runOnUiThread { showToast("Таймаут ожидания ответа от лампы") }
    } catch (e: Exception) {
        Log.e(TAG, "Ошибка при отправке команды: ${e.message}")
        runOnUiThread { showToast("Ошибка: ${e.message}") }
    } finally {
        webSocket?.close(1000, "Done")
        webSocket = null
    }
}
// -- end send
    private fun disconnectFromLampWifi() {
        lifecycleScope.launch {
            connectivityManager.bindProcessToNetwork(null)
            wifiCallback?.let { callback ->
                requestNetwork?.let { request ->
                    try {
                        connectivityManager.unregisterNetworkCallback(callback)
                    } catch (_: Exception) {}
                }
            }

            wifiCallback = null
            requestNetwork = null

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                wifiManager.disconnect()
            }

            // Восстанавливаем интернет-соединение
            try {
                connectivityManager.requestNetwork(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    object : ConnectivityManager.NetworkCallback() {}
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "Нет разрешения на запрос сети: ${e.message}")
            }
        }
    }

    private fun cleanup() {
        webSocket?.close(1000, "App closing")
        webSocket = null
        disconnectFromLampWifi()
    }

    private fun setUiLoading(loading: Boolean) {
        binding.btnToggle.isEnabled = !loading
        binding.btnClose.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            binding.tvStatus.text = message
        }
    }

    private fun showToast(message: String) {
/*
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
*/
    } // showToast

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    companion object {
        private const val TAG = "ESP_Lamp_App"
//        private const val WS_URL = "ws://192.168.4.1/ws"
        private const val LAMP_IP = "192.168.4.1"
        private const val WS_PATH = "/ws"
        private const val WS_URL = "ws://" + LAMP_IP + WS_PATH

        private const val WIFI_SSD = "wifi_ssd"
        private const val WIFI_PWD = "wifi_pwd"
    }
}