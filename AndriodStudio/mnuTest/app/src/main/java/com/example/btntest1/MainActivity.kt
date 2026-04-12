package com.example.btntest1

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat


class MainActivity : AppCompatActivity() {

    private lateinit var button: Button
    private lateinit var toolbar: Toolbar
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        button = findViewById(R.id.myButton)
        button.setOnClickListener {
            startTimerLogic()
        }
    } // onCreate


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
        // Создаём поле ввода
        val editText = EditText(this).apply {
            hint = "Введите название WiFi"
            // Загружаем сохранённое значение
            setText(prefs.getString("wifi_key", ""))
            setSingleLine()
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("WiFi")
            .setMessage("Введите название WiFi сети")
            .setView(editText)
            .setPositiveButton("Сохранить") { _, _ ->

                val wifiValue = editText.text.toString().trim()
                prefs.edit().putString("wifi_key", wifiValue).apply()
                Toast.makeText(this, "WiFi сохранён: $wifiValue", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Очистить") { _, _ ->
                // Очищаем сохранённое значение
                prefs.edit().remove("wifi_key").apply()
                Toast.makeText(this, "WiFi очищен", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun startTimerLogic() {
        button.backgroundTintList = ContextCompat.getColorStateList(this, R.color.button_pale_red)
        
        button.isEnabled = false

        handler.postDelayed({

            button.backgroundTintList = ContextCompat.getColorStateList(this, R.color.button_green)
            
            button.isEnabled = true
            
        }, 10000) 
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
