package com.example.gpsapp

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.location.Priority

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        DebugLogger.log(this, "SettingsActivity", "onCreate invoked")

        val prefs = getSharedPreferences("GpsSettings", Context.MODE_PRIVATE)
        val currentInterval = prefs.getLong("INTERVAL", 5000L)
        val currentAccuracy = prefs.getInt("ACCURACY", Priority.PRIORITY_HIGH_ACCURACY)
        val currentDebug = prefs.getBoolean("DEBUG_MODE", false)

        val rgInterval = findViewById<RadioGroup>(R.id.rgInterval)
        val rgAccuracy = findViewById<RadioGroup>(R.id.rgAccuracy)
        val switchDebug = findViewById<Switch>(R.id.switchDebug) // ★ 追加

        // 現在の設定を画面に反映
        when (currentInterval) {
            1000L -> rgInterval.check(R.id.rbInterval1s)
            5000L -> rgInterval.check(R.id.rbInterval5s)
            10000L -> rgInterval.check(R.id.rbInterval10s)
            30000L -> rgInterval.check(R.id.rbInterval30s)
            else -> rgInterval.check(R.id.rbInterval5s)
        }
        when (currentAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY -> rgAccuracy.check(R.id.rbAccHigh)
            Priority.PRIORITY_BALANCED_POWER_ACCURACY -> rgAccuracy.check(R.id.rbAccBalanced)
            else -> rgAccuracy.check(R.id.rbAccHigh)
        }
        switchDebug.isChecked = currentDebug

        // 保存ボタン処理
        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            val interval = when (rgInterval.checkedRadioButtonId) {
                R.id.rbInterval1s -> 1000L
                R.id.rbInterval5s -> 5000L
                R.id.rbInterval10s -> 10000L
                R.id.rbInterval30s -> 30000L
                else -> 5000L
            }
            val accuracy = when (rgAccuracy.checkedRadioButtonId) {
                R.id.rbAccHigh -> Priority.PRIORITY_HIGH_ACCURACY
                R.id.rbAccBalanced -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
                else -> Priority.PRIORITY_HIGH_ACCURACY
            }
            val isDebug = switchDebug.isChecked

            prefs.edit().apply {
                putLong("INTERVAL", interval)
                putInt("ACCURACY", accuracy)
                putBoolean("DEBUG_MODE", isDebug) // ★ 追加
                apply()
            }

            DebugLogger.log(this, "SettingsActivity", "Settings saved -> Interval: $interval, Accuracy: $accuracy, Debug: $isDebug")
            Toast.makeText(this, "設定を保存しました", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}