// File: com/example/lamplayout/wifi/WifiSettings.kt
package com.example.lamplayout.wifi

data class WifiSettings(
    val ssid: String,
    val password: String,
    val isHidden: Boolean = false
) {
    fun isValid(): Boolean = ssid.isNotBlank() && password.isNotBlank()
}