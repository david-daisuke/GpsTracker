package com.example.gpsapp

import android.app.AlertDialog
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

    private lateinit var tvLocalFolder: TextView
    private var selectedFolderUri: String? = null

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            selectedFolderUri = uri.toString()
            val docFile = DocumentFile.fromTreeUri(this, uri)
            tvLocalFolder.text = "現在の保存先: ${docFile?.name ?: "不明なフォルダ"}"
            Toast.makeText(this, "保存先フォルダを設定しました", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("GpsSettings", Context.MODE_PRIVATE)
        val currentInterval = prefs.getLong("INTERVAL", 5000L)
        val currentAccuracy = prefs.getInt("ACCURACY", Priority.PRIORITY_HIGH_ACCURACY)
        val currentDebug = prefs.getBoolean("DEBUG_MODE", false)
        val useCustomFolder = prefs.getBoolean("USE_CUSTOM_FOLDER", false)
        selectedFolderUri = prefs.getString("CUSTOM_FOLDER_URI", null)

        val rgInterval = findViewById<RadioGroup>(R.id.rgInterval)
        val rgAccuracy = findViewById<RadioGroup>(R.id.rgAccuracy)
        val switchDebug = findViewById<Switch>(R.id.switchDebug)
        val switchCustomFolder = findViewById<Switch>(R.id.switchCustomFolder)
        val btnSelectLocalFolder = findViewById<Button>(R.id.btnSelectLocalFolder)
        val btnPrivacyPolicy = findViewById<Button>(R.id.btnPrivacyPolicy)
        tvLocalFolder = findViewById(R.id.tvLocalFolder)

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
        switchCustomFolder.isChecked = useCustomFolder

        if (selectedFolderUri != null) {
            try {
                val uri = Uri.parse(selectedFolderUri)
                val docFile = DocumentFile.fromTreeUri(this, uri)
                tvLocalFolder.text = "現在の保存先: ${docFile?.name ?: "設定済み"}"
            } catch (e: Exception) {
                tvLocalFolder.text = "現在の保存先: 読み込みエラー"
            }
        }

        btnSelectLocalFolder.setOnClickListener { folderPickerLauncher.launch(null) }

        // ★ プライバシーポリシーの表示
        btnPrivacyPolicy.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("プライバシーポリシー")
                .setMessage("本アプリは、ユーザーの移動ルートを記録・表示する目的で、バックグラウンドを含む位置情報を収集します。\n\n" +
                        "1. 収集するデータ\n位置情報（緯度・経度・高度）、移動速度\n\n" +
                        "2. データの利用目的\n・地図上へのルート表示\n・GPXファイルとしてのエクスポート\n\n" +
                        "3. データの保存と共有\n収集した位置情報は、ユーザーの端末内、またはユーザーが明示的に指定したローカルフォルダにのみ保存されます。アプリ開発者や第三者のサーバーに自動送信されることはありません。")
                .setPositiveButton("確認しました", null)
                .show()
        }

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            val interval = when (rgInterval.checkedRadioButtonId) {
                R.id.rbInterval1s -> 1000L; R.id.rbInterval5s -> 5000L; R.id.rbInterval10s -> 10000L; R.id.rbInterval30s -> 30000L; else -> 5000L
            }
            val accuracy = when (rgAccuracy.checkedRadioButtonId) {
                R.id.rbAccHigh -> Priority.PRIORITY_HIGH_ACCURACY; R.id.rbAccBalanced -> Priority.PRIORITY_BALANCED_POWER_ACCURACY; else -> Priority.PRIORITY_HIGH_ACCURACY
            }

            if (switchCustomFolder.isChecked && selectedFolderUri == null) {
                Toast.makeText(this, "変更をONにする場合は、保存先フォルダを選択してください", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            prefs.edit().apply {
                putLong("INTERVAL", interval)
                putInt("ACCURACY", accuracy)
                putBoolean("DEBUG_MODE", switchDebug.isChecked)
                putBoolean("USE_CUSTOM_FOLDER", switchCustomFolder.isChecked)
                putString("CUSTOM_FOLDER_URI", selectedFolderUri)
                apply()
            }
            Toast.makeText(this, "設定を保存しました", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}