package com.example.gpsapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class WaypointData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val time: Long,
    val name: String,
    val memo: String,
    val address: String
)

data class TrackLocation(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speed: Float,
    val time: Long,
    val distance: Float, // ★ 追加: STARTボタンを押してからの累積移動距離(m)
    var address: String = ""
)

object GpsDataRepository {
    val locationList = mutableListOf<TrackLocation>()
    val waypointList = mutableListOf<WaypointData>()
    var isRecording = false
    var totalDistance = 0.0f // ★ 追加: 累積距離を保存する変数
}

class MainActivity : AppCompatActivity() {

    private lateinit var tvRecentLocations: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvAltitude: TextView
    private lateinit var tvDistance: TextView // ★ 追加

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnMark = findViewById<Button>(R.id.btnMark)
        val btnOpenFolder = findViewById<Button>(R.id.btnOpenFolder)
        tvRecentLocations = findViewById(R.id.tvRecentLocations)
        tvStatus = findViewById(R.id.tvStatus)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvAltitude = findViewById(R.id.tvAltitude)
        tvDistance = findViewById(R.id.tvDistance) // ★ 追加

        if (GpsDataRepository.isRecording) {
            tvStatus.text = "⏺ 記録中"
            tvStatus.setTextColor("#F44336".toColorInt())
        } else {
            tvStatus.text = "⏹ 待機中"
            tvStatus.setTextColor("#B0BEC5".toColorInt())
        }

        btnStart.setOnClickListener { startTracking() }
        btnStop.setOnClickListener { stopTracking() }
        btnOpenFolder.setOnClickListener { openSavedFolder() }
        btnMark.setOnClickListener { showMarkDialog() }

        lifecycleScope.launch {
            while (isActive) {
                if (GpsDataRepository.isRecording) {
                    updateRealtimeDisplay()
                    updateRecentLocationsDisplay()
                }
                delay(5000)
            }
        }
    }

    private fun updateRealtimeDisplay() {
        if (GpsDataRepository.locationList.isNotEmpty()) {
            val lastLoc = GpsDataRepository.locationList.last()

            val speedKmH = lastLoc.speed * 3.6
            val distKm = lastLoc.distance / 1000.0 // メートルをキロメートルに変換

            tvSpeed.text = String.format(Locale.US, "%.1f km/h", speedKmH)
            tvAltitude.text = String.format(Locale.US, "高度: %.1f m", lastLoc.altitude)
            tvDistance.text = String.format(Locale.US, "距離: %.2f km", distKm) // ★ 距離を更新
        }
    }

    private fun showMarkDialog() {
        if (!GpsDataRepository.isRecording || GpsDataRepository.locationList.isEmpty()) {
            Toast.makeText(this, "GPSの記録を開始してからマークできます", Toast.LENGTH_SHORT).show()
            return
        }

        val lastLocation = GpsDataRepository.locationList.last()

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)

        val etName = EditText(this)
        etName.hint = "名称 (例: 絶景ポイント)"
        layout.addView(etName)

        val etMemo = EditText(this)
        etMemo.hint = "メモ"
        layout.addView(etMemo)

        AlertDialog.Builder(this)
            .setTitle("📍 現在地を記録")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString()
                val memo = etMemo.text.toString()

                val wpt = WaypointData(lastLocation.latitude, lastLocation.longitude, lastLocation.altitude, System.currentTimeMillis(), name, memo, lastLocation.address)
                GpsDataRepository.waypointList.add(wpt)
                Toast.makeText(this, "スポットを記録しました！", Toast.LENGTH_SHORT).show()

                updateRecentLocationsDisplay()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun startTracking() {
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
        GpsDataRepository.waypointList.clear()
        GpsDataRepository.isRecording = true
        GpsDataRepository.totalDistance = 0.0f // ★ START時に距離をリセット

        tvStatus.text = "⏺ 記録中"
        tvStatus.setTextColor("#F44336".toColorInt())

        tvSpeed.text = "0.0 km/h"
        tvAltitude.text = "高度: 0.0 m"
        tvDistance.text = "距離: 0.00 km"

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

        val serviceIntent = Intent(this, GpsTrackerService::class.java)
        stopService(serviceIntent)
        GpsDataRepository.isRecording = false

        tvStatus.text = "⏹ 待機中"
        tvStatus.setTextColor("#B0BEC5".toColorInt())

        // STOP時はメーターの表示はそのまま残しておき、最終結果を見られるようにする

        if (GpsDataRepository.locationList.isNotEmpty()) {
            saveToGpxAndZip()
        } else {
            Toast.makeText(this, "記録されたデータがありません", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRecentLocationsDisplay() {
        val twentyMinutesAgo = System.currentTimeMillis() - (20 * 60 * 1000)

        // ★ 変更: ログに「速度」「高度」「距離」を追加
        val recentLocations = GpsDataRepository.locationList
            .filter { it.time >= twentyMinutesAgo }
            .map { loc ->
                val addrStr = if (loc.address.isNotEmpty()) " [${loc.address}]" else ""
                val speedKmH = loc.speed * 3.6
                val distKm = loc.distance / 1000.0

                val latStr = String.format(Locale.US, "%.4f", loc.latitude)
                val lonStr = String.format(Locale.US, "%.4f", loc.longitude)
                val speedStr = String.format(Locale.US, "%.1f", speedKmH)
                val altStr = String.format(Locale.US, "%.1f", loc.altitude)
                val distStr = String.format(Locale.US, "%.2f", distKm)

                Pair(loc.time, "📍 緯度: $latStr, 経度: $lonStr$addrStr\n    速度: $speedStr km/h, 高度: $altStr m, 距離: $distStr km")
            }

        val recentWaypoints = GpsDataRepository.waypointList
            .filter { it.time >= twentyMinutesAgo }
            .map { wpt ->
                val displayName = if (wpt.name.isNotEmpty()) wpt.name else "名称なし"
                val displayMemo = if (wpt.memo.isNotEmpty()) " - ${wpt.memo}" else ""
                val addrStr = if (wpt.address.isNotEmpty()) " [${wpt.address}]" else ""

                val latStr = String.format(Locale.US, "%.4f", wpt.latitude)
                val lonStr = String.format(Locale.US, "%.4f", wpt.longitude)

                Pair(wpt.time, "⭐ [マーク] $displayName$displayMemo\n    📍 緯度: $latStr, 経度: $lonStr$addrStr")
            }

        val combinedList = (recentLocations + recentWaypoints).sortedByDescending { it.first }

        val displayText = java.lang.StringBuilder("【過去20分間の記録: ${combinedList.size}件】\n")
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)

        combinedList.forEach { item ->
            val timeStr = sdf.format(Date(item.first))
            displayText.append("$timeStr ${item.second}\n\n") // 見やすくするために改行を増やす
        }

        tvRecentLocations.text = if (combinedList.isEmpty()) "過去20分間の記録はまだありません" else displayText.toString().trim()
    }

    private fun openSavedFolder() {
        try {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "標準のファイルアプリが開けませんでした", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToGpxAndZip() {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")

            val gpxBuilder = java.lang.StringBuilder()
            gpxBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            gpxBuilder.append("<gpx version=\"1.1\" creator=\"MyTracker\">\n")

            for (wpt in GpsDataRepository.waypointList) {
                val timeString = sdf.format(Date(wpt.time))
                val safeName = wpt.name.replace("<", "&lt;").replace(">", "&gt;").replace("&", "&amp;")
                val safeMemo = wpt.memo.replace("<", "&lt;").replace(">", "&gt;").replace("&", "&amp;")

                gpxBuilder.append("  <wpt lat=\"${wpt.latitude}\" lon=\"${wpt.longitude}\">\n")
                gpxBuilder.append("    <ele>${wpt.altitude}</ele>\n")
                gpxBuilder.append("    <time>${timeString}</time>\n")
                if (safeName.isNotEmpty()) gpxBuilder.append("    <name>${safeName}</name>\n")
                if (safeMemo.isNotEmpty()) gpxBuilder.append("    <desc>${safeMemo}</desc>\n")
                gpxBuilder.append("  </wpt>\n")
            }

            gpxBuilder.append("  <trk>\n    <trkseg>\n")
            for (loc in GpsDataRepository.locationList) {
                val timeString = sdf.format(Date(loc.time))
                gpxBuilder.append("      <trkpt lat=\"${loc.latitude}\" lon=\"${loc.longitude}\">\n")
                gpxBuilder.append("        <ele>${loc.altitude}</ele>\n")
                gpxBuilder.append("        <time>${timeString}</time>\n")
                gpxBuilder.append("      </trkpt>\n")
            }
            gpxBuilder.append("    </trkseg>\n  </trk>\n</gpx>")

            val fileNameTime = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val zipFileName = "Track_${fileNameTime}.zip"
            val gpxFileName = "Track_${fileNameTime}.gpx"

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val directory = File(downloadsDir, "GpsTracker")
            if (!directory.exists()) {
                directory.mkdirs()
            }
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
            Log.d("GPS_TRACKER", "保存先: ${zipFile.absolutePath}")

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "保存に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }
}

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

                    // ★ 追加: 直前の場所からの「距離」を計算して累積する
                    val lastSavedLoc = GpsDataRepository.locationList.lastOrNull()
                    if (lastSavedLoc != null) {
                        // 前回記録した座標を使って仮のLocationを作る
                        val prevLocation = Location("").apply {
                            latitude = lastSavedLoc.latitude
                            longitude = lastSavedLoc.longitude
                        }
                        // 今回の場所(location)までの距離(m)を計算して足し算
                        GpsDataRepository.totalDistance += prevLocation.distanceTo(location)
                    }

                    val trackLoc = TrackLocation(
                        location.latitude,
                        location.longitude,
                        location.altitude,
                        location.speed,
                        location.time,
                        GpsDataRepository.totalDistance // ★ 追加: 累積距離をセット
                    )
                    GpsDataRepository.locationList.add(trackLoc)
                    Log.d("GPS_TRACKER", "Service記録中... 緯度: ${location.latitude}")

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val geocoder = Geocoder(applicationContext, Locale.getDefault())
                            @Suppress("DEPRECATION")
                            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)

                            if (!addresses.isNullOrEmpty()) {
                                val addr = addresses[0]
                                trackLoc.address = listOfNotNull(addr.adminArea, addr.locality, addr.subLocality).joinToString("")
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Tracker")
            .setContentText("位置情報を記録中です...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }

        requestLocationUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(5000)
            .build()

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
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