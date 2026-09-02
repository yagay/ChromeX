package com.example.xchrome

import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val pref = getSharedPreferences("settings", Context.MODE_PRIVATE)
        
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }
        
        val title = TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 24f
            setPadding(0, 0, 0, 50)
        }
        
        val switchJump = SwitchMaterial(this).apply {
            text = getString(R.string.enable_hook)
            isChecked = pref.getBoolean("disable_jump", false)
            setOnCheckedChangeListener { _, isChecked ->
                pref.edit().putBoolean("disable_jump", isChecked).apply()
            }
        }

        val switchDownload = SwitchMaterial(this).apply {
            text = getString(R.string.enable_auto_open)
            isChecked = pref.getBoolean("auto_open_download", false)
            setOnCheckedChangeListener { _, isChecked ->
                pref.edit().putBoolean("auto_open_download", isChecked).apply()
            }
        }

        val switchOverwrite = SwitchMaterial(this).apply {
            text = getString(R.string.overwrite_download)
            isChecked = pref.getBoolean("overwrite_download", false)
            setOnCheckedChangeListener { _, isChecked ->
                pref.edit().putBoolean("overwrite_download", isChecked).apply()
            }
        }
        
        root.addView(title)
        root.addView(switchJump)
        root.addView(switchDownload)
        root.addView(switchOverwrite)
        
        setContentView(root)
    }
}
