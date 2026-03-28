package com.example.gpsapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ★ ServiceとActivityでGPSデータを共有するための入れ物
object GpsDataRepository {
    val locationList = mutableListOf<Location>()
    var isRecording = false
}

class MainActivity : AppCompatActivity() {

    private lateinit var tvRecentLocations: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnOpenFolder = findViewById<Button>(R.id.btnOpenFolder) // 追加: フォルダを開くボタン
        tvRecentLocations = findViewById(R.id.tvRecentLocations) // 追加: 記録を表示するテキスト

        btnStart.setOnClickListener {
            startTracking()
        }

        btnStop.setOnClickListener {
            stopTracking()
        }

        btnOpenFolder.setOnClickListener {
            openSavedFolder()
        }

        // ★ 1分ごとに画面を更新するループ処理 (コルーチン)
        lifecycleScope.launch {
            while (isActive) {
                if (GpsDataRepository.isRecording) {
                    updateRecentLocationsDisplay()
                }
                delay(60000) // 1分 (60000ミリ秒) 待機して繰り返す
            }
        }
    }

    private fun startTracking() {
        // 必要な権限の確認 (Android 13以上の通知権限なども含む)
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
            return
        }

        GpsDataRepository.locationList.clear()
        GpsDataRepository.isRecording = true

        // ★ フォアグラウンドサービスを起動して裏での記録を開始
        val serviceIntent = Intent(this, GpsTrackerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Toast.makeText(this, "トラッキングを開始しました", Toast.LENGTH_SHORT).show()
        updateRecentLocationsDisplay()
    }

    private fun stopTracking() {
        if (!GpsDataRepository.isRecording) return

        // サービスを停止
        val serviceIntent = Intent(this, GpsTrackerService::class.java)
        stopService(serviceIntent)
        GpsDataRepository.isRecording = false

        if (GpsDataRepository.locationList.isNotEmpty()) {
            saveToGpxAndZip()
        } else {
            Toast.makeText(this, "記録されたデータがありません", Toast.LENGTH_SHORT).show()
        }
    }

    // ★ 過去10分間のデータを画面に表示する処理
    private fun updateRecentLocationsDisplay() {
        val tenMinutesAgo = System.currentTimeMillis() - (10 * 60 * 1000)
        // 10分以内のデータだけを抽出
        val recentLocations = GpsDataRepository.locationList.filter { it.time >= tenMinutesAgo }

        val displayText = java.lang.StringBuilder("【過去10分間の記録: ${recentLocations.size}件】\n")
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)

        // 最新のものが上に来るように逆順で表示
        recentLocations.reversed().forEach { loc ->
            val timeStr = sdf.format(Date(loc.time))
            displayText.append("$timeStr - 緯度: ${String.format("%.4f", loc.latitude)}, 経度: ${String.format("%.4f", loc.longitude)}\n")
        }

        tvRecentLocations.text = if (recentLocations.isEmpty()) "過去10分間の記録はまだありません" else displayText.toString()
    }

    // ★ 保存先のフォルダを開く処理
    private fun openSavedFolder() {
        val directory = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val intent = Intent(Intent.ACTION_VIEW)
        val uri = Uri.parse(directory?.path)
        intent.setDataAndType(uri, "*/*")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "ファイルを開くアプリが見つかりません。ファイルマネージャーから確認してください。", Toast.LENGTH_LONG).show()
        }
    }

    // (元のGPX保存処理そのままです)
    private fun saveToGpxAndZip() {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")

            val gpxBuilder = java.lang.StringBuilder()
            gpxBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            gpxBuilder.append("<gpx version=\"1.1\" creator=\"MyTracker\">\n")
            gpxBuilder.append("  <trk>\n    <trkseg>\n")

            for (loc in GpsDataRepository.locationList) {
                val timeString = sdf.format(Date(loc.time))
                gpxBuilder.append("      <trkpt lat=\"${loc.latitude}\" lon=\"${loc.longitude}\">\n")
                gpxBuilder.append("        <time>${timeString}</time>\n")
                gpxBuilder.append("      </trkpt>\n")
            }
            gpxBuilder.append("    </trkseg>\n  </trk>\n</gpx>")

            val fileNameTime = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val zipFileName = "Track_${fileNameTime}.zip"
            val gpxFileName = "Track_${fileNameTime}.gpx"

            val directory = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val zipFile = File(directory, zipFileName)

            FileOutputStream(zipFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    val zipEntry = ZipEntry(gpxFileName)
                    zos.putNextEntry(zipEntry)
                    zos.write(gpxBuilder.toString().toByteArray())
                    zos.closeEntry()
                }
            }
            Toast.makeText(this, "保存完了！\n${zipFile.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "保存に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }
}

// ★ バックグラウンドで動き続けるための「フォアグラウンドサービス」
class GpsTrackerService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val CHANNEL_ID = "GpsTrackerChannel"

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    GpsDataRepository.locationList.add(location)
                    Log.d("GPS_TRACKER", "Service記録中... 緯度: ${location.latitude}")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Tracker")
            .setContentText("位置情報を記録中です...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation) // 標準アイコンをとりあえず使用
            .build()

        // Android 14 (Pixel 9a等) のためのService起動設定
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }

        requestLocationUpdates()
        return START_STICKY
    }

    private fun requestLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 60000)
            .setMinUpdateIntervalMillis(60000)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS Tracking Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}