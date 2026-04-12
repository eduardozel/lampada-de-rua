package com.example.btntest1

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okio.ByteString

class MainActivity : AppCompatActivity() {

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifiManager: WifiManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectivityManager = getSystemService(ConnectivityManager::class.java)
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val button: Button = findViewById(R.id.myButton)
        button.setOnClickListener {
            checkPermissionsAndConnect()
        }
    }

    private fun checkPermissionsAndConnect() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            connectToLampNetwork()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                connectToLampNetwork()
            } else {
                Toast.makeText(this, "Разрешение ACCESS_FINE_LOCATION обязательно", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToLampNetwork() {
        val ssid = "lamp1"
        val password = "lamp4567"

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Поддерживается только Android 10 и выше", Toast.LENGTH_LONG).show()
            return
        }

        // Отключаем текущее WiFi соединение
        wifiManager.disconnect()

        val wifiNetworkSpecifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(wifiNetworkSpecifier)
            .build()

        // Отпишемся от старого коллбэка, если он есть
        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                try {
                    connectivityManager.bindProcessToNetwork(network)
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Ошибка bindProcessToNetwork: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    return
                }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Подключено к сети $ssid", Toast.LENGTH_LONG).show()
                    cancelTimeout()
                    Handler(Looper.getMainLooper()).postDelayed({
                        startWebSocket()
                    }, 1000)
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Не удалось подключиться к $ssid", Toast.LENGTH_LONG).show()
                    cancelTimeout()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Соединение с $ssid потеряно", Toast.LENGTH_LONG).show()
                    cancelTimeout()
                    // Можно закрыть WS при отключении
                    closeWebSocket()
                }
            }
        }

        try {
            connectivityManager.requestNetwork(networkRequest, networkCallback!!)
            Toast.makeText(this, "Запрос подключения к $ssid...", Toast.LENGTH_SHORT).show()
            startTimeout()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка запроса сети: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    // Таймаут 20 сек для попытки подключения, отменяем callback и показываем сообщение
    private fun startTimeout() {
        cancelTimeout()
        timeoutRunnable = Runnable {
            Toast.makeText(this, "Превышено время ожидания подключения", Toast.LENGTH_LONG).show()
            networkCallback?.let {
                try {
                    connectivityManager.unregisterNetworkCallback(it)
                } catch (_: Exception) {}
            }
        }
        handler.postDelayed(timeoutRunnable!!, 20_000)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let {
            handler.removeCallbacks(it)
            timeoutRunnable = null
        }
    }

    //-----------------------------------------------
    // WebSocket часть с OkHttp

    private fun startWebSocket() {
        val request = Request.Builder()
            .url("ws://192.168.4.1/ws")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "WebSocket соединение открыто", Toast.LENGTH_SHORT).show()
                }
                sendToggleCommand()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Ответ: $text", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "WebSocket ошибка: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "WebSocket закрывается: $reason", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun sendToggleCommand() {
        val json = """{"act":"togglelight"}"""
        webSocket?.send(json)
    }

    private fun closeWebSocket() {
        webSocket?.close(1000, "Activity destroyed")
        webSocket = null
    }

    //-----------------------------------------------

    override fun onDestroy() {
        super.onDestroy()
        cancelTimeout()
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) { }
        }
        closeWebSocket()
    }
}