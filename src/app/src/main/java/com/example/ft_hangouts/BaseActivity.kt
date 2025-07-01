package com.example.hangly

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

open class BaseActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
    
    override fun onResume() {
        super.onResume()
        
        // Update toolbar color whenever activity comes to foreground
        val toolbar = findViewById<Toolbar?>(R.id.toolbar)
        toolbar?.let {
            applyToolbarColor(it)
        }
    }
    
    open fun applyToolbarColor(toolbar: Toolbar) {
        val sharedPreferences = getSharedPreferences("hangly_prefs", Context.MODE_PRIVATE)
        val colorResId = sharedPreferences.getInt("toolbar_color", R.color.purple_500)
        toolbar.setBackgroundColor(getColor(colorResId))
        window.statusBarColor = getColor(colorResId)
    }
    
    protected fun saveToolbarColor(colorResId: Int) {
        val sharedPreferences = getSharedPreferences("hangly_prefs", Context.MODE_PRIVATE)
        sharedPreferences.edit().putInt("toolbar_color", colorResId).apply()
    }
    
    // Uygulamanın arka plana gittiğini kaydet
    override fun onStop() {
        super.onStop()
        val sharedPreferences = getSharedPreferences("hangly_prefs", Context.MODE_PRIVATE)
        sharedPreferences.edit().putLong("last_background_time", System.currentTimeMillis()).apply()
    }
}