package com.example.gpsapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.gms.location.Priority

class SettingsActivity : AppCompatActivity() {

    private lateinit var tvSyncFolder: TextView
    private var selectedFolderUri: String? = null

    // ★ Android標準のフォルダ選択ピッカーを呼び出す準備
    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            // OS再起動後もこのフォルダに書き込めるように「永続的な権限」を取得
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            selectedFolderUri = uri.toString()

            // 選択されたフォルダの名前を表示
            val docFile = DocumentFile.fromTreeUri(this, uri)
            tvSyncFolder.text = "現在の保存先: ${docFile?.name ?: "不明なフォルダ"}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("GpsSettings", Context.MODE_PRIVATE)
        val currentInterval = prefs.getLong("INTERVAL", 5000L)
        val currentAccuracy = prefs.getInt("ACCURACY", Priority.PRIORITY_HIGH_ACCURACY)
        val currentDebug = prefs.getBoolean("DEBUG_MODE", false)

        // クラウド同期の現在の設定を読み込む
        val currentAutoSync = prefs.getBoolean("AUTO_SYNC", false)
        selectedFolderUri = prefs.getString("SYNC_FOLDER_URI", null)

        val rgInterval = findViewById<RadioGroup>(R.id.rgInterval)
        val rgAccuracy = findViewById<RadioGroup>(R.id.rgAccuracy)
        val switchDebug = findViewById<Switch>(R.id.switchDebug)
        val switchAutoSync = findViewById<Switch>(R.id.switchAutoSync)
        val btnSelectSyncFolder = findViewById<Button>(R.id.btnSelectSyncFolder)
        tvSyncFolder = findViewById(R.id.tvSyncFolder)

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
        switchAutoSync.isChecked = currentAutoSync

        // 既にフォルダが設定されていれば名前を表示
        if (selectedFolderUri != null) {
            try {
                val uri = Uri.parse(selectedFolderUri)
                val docFile = DocumentFile.fromTreeUri(this, uri)
                tvSyncFolder.text = "現在の保存先: ${docFile?.name ?: "設定済み"}"
            } catch (e: Exception) {
                tvSyncFolder.text = "現在の保存先: 読み込みエラー"
            }
        }

        // フォルダ選択ボタンが押されたらピッカーを起動
        btnSelectSyncFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            val interval = when (rgInterval.checkedRadioButtonId) {
                R.id.rbInterval1s -> 1000L; R.id.rbInterval5s -> 5000L; R.id.rbInterval10s -> 10000L; R.id.rbInterval30s -> 30000L; else -> 5000L
            }
            val accuracy = when (rgAccuracy.checkedRadioButtonId) {
                R.id.rbAccHigh -> Priority.PRIORITY_HIGH_ACCURACY; R.id.rbAccBalanced -> Priority.PRIORITY_BALANCED_POWER_ACCURACY; else -> Priority.PRIORITY_HIGH_ACCURACY
            }

            // 同期をONにしたのにフォルダが選ばれていない場合は警告
            if (switchAutoSync.isChecked && selectedFolderUri == null) {
                Toast.makeText(this, "クラウド同期をONにする場合は、保存先フォルダを選択してください", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            prefs.edit().apply {
                putLong("INTERVAL", interval)
                putInt("ACCURACY", accuracy)
                putBoolean("DEBUG_MODE", switchDebug.isChecked)
                putBoolean("AUTO_SYNC", switchAutoSync.isChecked)
                putString("SYNC_FOLDER_URI", selectedFolderUri)
                apply()
            }
            Toast.makeText(this, "設定を保存しました", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}