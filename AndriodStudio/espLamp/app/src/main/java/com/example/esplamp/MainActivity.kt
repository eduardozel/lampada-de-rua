package com.example.esplamp

import android.Manifest
import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.esplamp.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

import android.net.wifi.WifiNetworkSpecifier

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager

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

    // Лаунчер запроса разрешений
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

        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        connectivityManager = applicationContext.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        requestPermissionsIfNeeded()
        setupListeners()
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
        binding.btnToggle.setOnClickListener {
            if (binding.btnToggle.isEnabled) {
                toggleLamp()
            }
        }
        binding.btnClose.setOnClickListener {
            cleanup()
            finishAffinity()
            exitProcess(0)
        }
    }

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
            val specifierBuilder = WifiNetworkSpecifier.Builder()
                .setSsid("lamp1")
                .setWpa2Passphrase("lamp4567")

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
    val url = "ws://192.168.4.1/ws"  // Уточните путь, если нужно

    val request = Request.Builder()
        .url(url)
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
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Получено сообщение от лампы: $text")
            runOnUiThread { showToast("Ответ лампы: $text") }

            if (text.contains("OK", ignoreCase = true) || text.contains("Success", ignoreCase = true)) {
                if (!webSocketResult.isCompleted)
                    webSocketResult.complete(true)
                webSocket.close(1000, "Команда выполнена")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Ошибка WebSocket: ${t.message}")
            runOnUiThread { showToast("Ошибка WebSocket: ${t.message}") }
            if (!webSocketResult.isCompleted) {
                webSocketResult.completeExceptionally(t)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket закрыт: $reason")
            runOnUiThread { showToast("WebSocket закрыт: $reason") }
            if (!webSocketResult.isCompleted) {
                webSocketResult.complete(true)
            }
        }
    }

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
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    companion object {
        private const val TAG = "EspLampApp"
    }
}