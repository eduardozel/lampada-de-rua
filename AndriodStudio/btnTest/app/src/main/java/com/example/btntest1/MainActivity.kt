package com.example.btntest1

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // Переменные для кнопки и обработчика задержки
    private lateinit var button: Button
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Находим кнопку по ID
        button = findViewById(R.id.myButton)

        // Вешаем обработчик нажатия
        button.setOnClickListener {
            startTimerLogic()
        }
    }

    private fun startTimerLogic() {
        // 1. Меняем цвет на бледно-красный
        button.backgroundTintList = ContextCompat.getColorStateList(this, R.color.button_pale_red)
        
        // 2. Делаем кнопку не доступной (серой и некликабельной)
        button.isEnabled = false

        // 3. Запускаем таймер на 10 секунд (10000 миллисекунд)
        handler.postDelayed({
            // Код выполнится через 10 секунд
            
            // Возвращаем зеленый цвет
            button.backgroundTintList = ContextCompat.getColorStateList(this, R.color.button_green)
            
            // Делаем кнопку снова доступной
            button.isEnabled = true
            
        }, 10000) 
    }

    // Хорошая практика: очищать таймер, если приложение закрывается, чтобы не было ошибок
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
