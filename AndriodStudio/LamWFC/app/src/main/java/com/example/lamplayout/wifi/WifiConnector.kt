// File: com/example/lamplayout/wifi/WifiConnector.kt
package com.example.lamplayout.wifi

import android.content.Context
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class WifiConnector(
    private val context: Context,
    private val tag: String = "WifiConnector"
) {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    private var currentCallback: ConnectivityManager.NetworkCallback? = null
    private var currentRequest: NetworkRequest? = null

    suspend fun connectTo(settings: WifiSettings): WifiConnectionResult = withContext(Dispatchers.IO) {
        if (!settings.isValid()) {
            return@withContext WifiConnectionResult.Error("SSID или пароль пустые")
        }
        try {
            connectWithNetworkSpecifier(settings)
        } catch (e: CancellationException) {
            Log.d(tag, "Подключение отменено")
            WifiConnectionResult.Cancelled
        } catch (e: Exception) {
            Log.e(tag, "Ошибка подключения: ${e.message}", e)
            WifiConnectionResult.Error(e.message ?: "Неизвестная ошибка", e)
        }
    }

    private suspend fun connectWithNetworkSpecifier(settings: WifiSettings): WifiConnectionResult =
        suspendCancellableCoroutine { cont ->

            try {
                val specifierBuilder = WifiNetworkSpecifier.Builder()
                    .setSsid(settings.ssid)
                    .setIsHiddenSsid(settings.isHidden)

                if (settings.password.isNotEmpty()) { // WPA2 passphrase required only when there's password
                    specifierBuilder.setWpa2Passphrase(settings.password)
                }

                val specifier = specifierBuilder.build()

                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()

                currentRequest = request

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.d(tag, "✅ Сеть ${settings.ssid} доступна")
                        connectivityManager.bindProcessToNetwork(network)
                        if (cont.isActive) cont.resume(WifiConnectionResult.Success)
                    }

                    override fun onUnavailable() {
                        Log.e(tag, "❌ Сеть ${settings.ssid} недоступна")
                        if (cont.isActive) cont.resume(WifiConnectionResult.Error("Сеть не найдена или неверный пароль"))
                    }

                    override fun onLost(network: Network) {
                        Log.d(tag, "🔌 Сеть ${settings.ssid} потеряна")
                        connectivityManager.bindProcessToNetwork(null)
                    }

                    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                        if (blocked) Log.w(tag, "⚠️ Подключение к ${settings.ssid} заблокировано системой")
                    }
                }

                currentCallback = callback
                connectivityManager.requestNetwork(request, callback)

                cont.invokeOnCancellation {
                    cleanup()
                }

            } catch (e: Exception) {
                Log.e(tag, "Ошибка при создании запроса: ${e.message}", e)
                if (cont.isActive) {
                    cont.resume(WifiConnectionResult.Error(e.message ?: "Ошибка запроса", e))
                }
            }
        }

    fun disconnect() {
        Log.d(tag, "Отключение от сети...")
        connectivityManager.bindProcessToNetwork(null)
        cleanup()
    }

    private fun cleanup() {
        currentCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                Log.d(tag, "Callback отписан")
            } catch (e: IllegalArgumentException) {
                Log.w(tag, "Callback уже отписан: ${e.message}")
            }
        }
        currentCallback = null
        currentRequest = null
    }

    fun restoreInternetConnection() {
        try {
            connectivityManager.requestNetwork(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {}
            )
            Log.d(tag, "Запрос на восстановление интернета отправлен")
        } catch (e: SecurityException) {
            Log.e(tag, "Нет разрешения на запрос сети: ${e.message}")
        }
    }

    fun onDestroy() {
        disconnect()
    }
}