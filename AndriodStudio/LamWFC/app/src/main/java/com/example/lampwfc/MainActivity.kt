// File: com/example/lamplayout/MainActivity.kt
package com.example.lampwfc

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
//import com.example.lamplayout.databinding.ActivityMainBinding
import com.example.lampwfc.databinding.ActivityMainBinding
import com.example.lamplayout.wifi.WifiConnector
import com.example.lamplayout.wifi.WifiConnectionResult
import com.example.lamplayout.wifi.WifiSettings
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var prefs: SharedPreferences
    private lateinit var toolbar: Toolbar
    private lateinit var wifiConnector: WifiConnector

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

        prefs = getSharedPreferences(LAMP_PREFS, Context.MODE_PRIVATE)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        wifiConnector = WifiConnector(this, TAG)

        requestPermissionsIfNeeded()
        setupToolbar()
        setupListeners()
        updateToolbarTitle()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.INTERNET
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun setupToolbar() {
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        // При необходимости inflate меню toolbar
    }

    private fun setupListeners() {
        binding.btnToggle.setOnClickListener {
            if (binding.btnToggle.isEnabled) toggleLamp()
        }
        binding.btnClose.setOnClickListener {
            cleanup()
            finishAndRemoveTask()
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
        runOnUiThread { binding.tvStatus.text = message }
    }

    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun updateToolbarTitle() {
        val wifiSsid = prefs.getString(WIFI_SSID, "")
        supportActionBar?.title = if (wifiSsid.isNullOrBlank()) "Lamp" else "Lamp: $wifiSsid"
    }

    // Основная бизнес-логика включения лампы
    private fun toggleLamp() = lifecycleScope.launch {
        setUiLoading(true)

        val settings = WifiSettings(
            ssid = prefs.getString(WIFI_SSID, "").orEmpty(),
            password = prefs.getString(WIFI_PWD, "").orEmpty()
        )

        try {
            updateStatus("Подключение к ${settings.ssid}...")
            when (val result = wifiConnector.connectTo(settings)) {
                is WifiConnectionResult.Success -> {
                    delay(1500) // Ждем IP-адрес
                    updateStatus("Отправка команды...")
                    sendToggleCommand()
                    updateStatus("✅ Команда отправлена!")
                    showToast("Команда отправлена")
                }
                is WifiConnectionResult.Error -> throw Exception(result.message)
                is WifiConnectionResult.Timeout -> throw Exception("Таймаут подключения")
                is WifiConnectionResult.Cancelled -> throw Exception("Подключение отменено")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка: ${e.message}", e)
            updateStatus("❌ Ошибка: ${e.message}")
            showToast("Ошибка: ${e.message}")
        } finally {
            updateStatus("Восстановление подключения...")
            wifiConnector.disconnect()
            wifiConnector.restoreInternetConnection()
            delay(500)
            setUiLoading(false)
        }
    }

    // WebSocket-команда для переключения лампы
    private suspend fun sendToggleCommand() = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(WS_URL).build()
        val webSocketResult = CompletableDeferred<Boolean>()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                runOnUiThread { showToast("WebSocket connected") }
                val cmd = """{"act": "togglelight"}"""
                if (!webSocket.send(cmd)) {
                    webSocketResult.completeExceptionally(Exception("Не удалось отправить команду"))
                } else {
                    runOnUiThread { showToast("Команда отправлена") }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Получено сообщение: $text")
                runOnUiThread { showToast("Ответ лампы: $text") }

                if (text.contains("OK", true) || text.contains("Success", true)) {
                    if (!webSocketResult.isCompleted) webSocketResult.complete(true)
                    webSocket.close(1000, "Команда выполнена")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Ошибка WebSocket: ${t.message}")
                runOnUiThread { showToast("Ошибка WebSocket: ${t.message}") }
                if (!webSocketResult.isCompleted) webSocketResult.completeExceptionally(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket закрыт: $reason")
                runOnUiThread { showToast("WebSocket закрыт: $reason") }
                if (!webSocketResult.isCompleted) webSocketResult.complete(true)
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
            Log.e(TAG, "Ошибка при отправке команды: ${e.message}", e)
            runOnUiThread { showToast("Ошибка: ${e.message}") }
        } finally {
            webSocket?.close(1000, "Done")
            webSocket = null
        }
    }

    private fun disconnectFromLampWifi() {
        wifiConnector.disconnect()
        wifiConnector.restoreInternetConnection()
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "App closing")
        wifiConnector.onDestroy()
    }

    companion object {
        private const val TAG = "ESP_Lamp_App"
        private const val LAMP_IP = "192.168.4.1"
        private const val WS_PATH = "/ws"
        private const val WS_URL = "ws://$LAMP_IP$WS_PATH"
        private const val WIFI_SSID = "wifi_ssd"
        private const val WIFI_PWD = "wifi_pwd"
        private const val LAMP_PREFS = "Lamp_Settings"
    }
}