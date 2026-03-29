package com.example.gpsapp

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.location.Priority

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // SharedPreferencesから現在の設定を読み込む（初期値は5秒、最高精度）
        val prefs = getSharedPreferences("GpsSettings", Context.MODE_PRIVATE)
        val currentInterval = prefs.getLong("INTERVAL", 5000L)
        val currentAccuracy = prefs.getInt("ACCURACY", Priority.PRIORITY_HIGH_ACCURACY)

        val rgInterval = findViewById<RadioGroup>(R.id.rgInterval)
        val rgAccuracy = findViewById<RadioGroup>(R.id.rgAccuracy)

        // 現在の設定に合わせてラジオボタンのチェックを入れる
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

        // 保存ボタンを押した時の処理
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

            // 設定を書き込んで保存する
            prefs.edit().apply {
                putLong("INTERVAL", interval)
                putInt("ACCURACY", accuracy)
                apply()
            }
            Toast.makeText(this, "設定を保存しました", Toast.LENGTH_SHORT).show()
            finish() // 画面を閉じて戻る
        }
    }
}