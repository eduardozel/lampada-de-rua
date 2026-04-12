package com.example.btntest1

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat


class MainActivity : AppCompatActivity() {


    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifiManager: WifiManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    @SuppressLint("MissingPermission") // Мы сами проверяем разрешения
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
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            connectToLampNetwork()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                connectToLampNetwork()
            } else {
                Toast.makeText(
                    this,
                    "Разрешение ACCESS_FINE_LOCATION обязательно",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @SuppressLint("MissingPermission") // Мы проверяем разрешения
    private fun connectToLampNetwork() {
        // Получаем текущий SSID (если есть)
        val currentSsid = getCurrentSsid()
        if (currentSsid != null) {
            Toast.makeText(this, "Текущая сеть: $currentSsid. Отключаем...", Toast.LENGTH_SHORT)
                .show()
            // Отключаем WiFi интерфейс (это самый простой способ отключить текущее WiFi соединение на Android 10+)
            wifiManager.disconnect()
        } else {
            Toast.makeText(this, "Не подключены к WiFi", Toast.LENGTH_SHORT).show()
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Поддерживается только Android 10 и выше", Toast.LENGTH_LONG)
                .show()
            return
        }

        // Создаём WifiNetworkSpecifier c SSID и паролем
        val ssid = "lamp1"
        val password = "lamp4567"

        val wifiNetworkSpecifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(wifiNetworkSpecifier)
            .build()

        // Снимаем старый callback, если был
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }

        networkCallback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                try {
                    connectivityManager.bindProcessToNetwork(network)
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Ошибка bindProcessToNetwork: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return
                }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Подключено к $ssid", Toast.LENGTH_LONG)
                        .show()
                    cancelTimeout()
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Не удалось подключиться к $ssid",
                        Toast.LENGTH_LONG
                    ).show()
                    cancelTimeout()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Соединение с $ssid потеряно",
                        Toast.LENGTH_LONG
                    ).show()
                    cancelTimeout()
                }
            }
        }

        try {
            connectivityManager.requestNetwork(networkRequest, networkCallback!!)
            Toast.makeText(this, "Запросив подключение к $ssid...", Toast.LENGTH_SHORT).show()
            startTimeout()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка запроса сети: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun getCurrentSsid(): String? {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val wifiInfo: WifiInfo? = wifiManager.connectionInfo
        val ssid = wifiInfo?.ssid ?: return null
        if (ssid == "<unknown ssid>") return null
        // Убираем кавычки, если они есть
        return ssid.trim('"')
    }

    //private val handler = Handler(Looper.getMainLooper())
    //private var timeoutRunnable: Runnable? = null

    private fun startTimeout() {
        cancelTimeout()
        timeoutRunnable = Runnable {
            Toast.makeText(this, "Превышено время ожидания подключения", Toast.LENGTH_LONG).show()
            networkCallback?.let {
                try {
                    connectivityManager.unregisterNetworkCallback(it)
                } catch (_: Exception) {
                }
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

    override fun onDestroy() {
        super.onDestroy()
        cancelTimeout()
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
    }
}