// File: com/example/lamplayout/wifi/WifiConnectionResult.kt
package com.example.lamplayout.wifi

sealed class WifiConnectionResult {
    object Success : WifiConnectionResult()
    data class Error(val message: String, val exception: Exception? = null) : WifiConnectionResult()
    object Timeout : WifiConnectionResult()
    object Cancelled : WifiConnectionResult()
}
