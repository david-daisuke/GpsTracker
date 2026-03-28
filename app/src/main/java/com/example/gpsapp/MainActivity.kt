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
    val time: Long,
    val name: String,
    val memo: String
)

// ★ 変更: GPS座標と一緒に「推測した住所文字列」を保存するためのデータクラスを新設
data class TrackLocation(
    val latitude: Double,
    val longitude: Double,
    val time: Long,
    var address: String = "" // 住所が入る箱（最初は空っぽ）
)

object GpsDataRepository {
    val locationList = mutableListOf<TrackLocation>() // ★ 変更: 新しいデータクラスのリストに
    val waypointList = mutableListOf<WaypointData>()
    var isRecording = false
}

class MainActivity : AppCompatActivity() {

    private lateinit var tvRecentLocations: TextView
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnMark = findViewById<Button>(R.id.btnMark)
        val btnOpenFolder = findViewById<Button>(R.id.btnOpenFolder)
        tvRecentLocations = findViewById(R.id.tvRecentLocations)
        tvStatus = findViewById(R.id.tvStatus)

        if (GpsDataRepository.isRecording) {
            tvStatus.text = "⏺ 記録中"
            tvStatus.setTextColor("#F44336".toColorInt())
        } else {
            tvStatus.text = "⏹ 待機中"
            tvStatus.setTextColor("#78909C".toColorInt())
        }

        btnStart.setOnClickListener { startTracking() }
        btnStop.setOnClickListener { stopTracking() }
        btnOpenFolder.setOnClickListener { openSavedFolder() }
        btnMark.setOnClickListener { showMarkDialog() }

        lifecycleScope.launch {
            while (isActive) {
                if (GpsDataRepository.isRecording) {
                    updateRecentLocationsDisplay()
                }
                delay(60000)
            }
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
                val wpt = WaypointData(lastLocation.latitude, lastLocation.longitude, System.currentTimeMillis(), name, memo)
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

        tvStatus.text = "⏺ 記録中"
        tvStatus.setTextColor("#F44336".toColorInt())

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
        tvStatus.setTextColor("#78909C".toColorInt())

        if (GpsDataRepository.locationList.isNotEmpty()) {
            saveToGpxAndZip()
        } else {
            Toast.makeText(this, "記録されたデータがありません", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRecentLocationsDisplay() {
        val twentyMinutesAgo = System.currentTimeMillis() - (20 * 60 * 1000)

        val recentLocations = GpsDataRepository.locationList
            .filter { it.time >= twentyMinutesAgo }
            .map { loc ->
                // ★ 変更: 住所データがあれば、一緒に画面に表示する
                val addrStr = if (loc.address.isNotEmpty()) " [${loc.address}]" else ""
                Pair(loc.time, "📍 緯度: ${String.format(Locale.US, "%.4f", loc.latitude)}, 経度: ${String.format(Locale.US, "%.4f", loc.longitude)}$addrStr")
            }

        val recentWaypoints = GpsDataRepository.waypointList
            .filter { it.time >= twentyMinutesAgo }
            .map { wpt ->
                val displayName = if (wpt.name.isNotEmpty()) wpt.name else "名称なし"
                val displayMemo = if (wpt.memo.isNotEmpty()) " - ${wpt.memo}" else ""
                Pair(wpt.time, "⭐ [マーク] $displayName$displayMemo")
            }

        val combinedList = (recentLocations + recentWaypoints).sortedByDescending { it.first }

        val displayText = java.lang.StringBuilder("【過去20分間の記録: ${combinedList.size}件】\n")
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)

        combinedList.forEach { item ->
            val timeStr = sdf.format(Date(item.first))
            displayText.append("$timeStr ${item.second}\n")
        }

        tvRecentLocations.text = if (combinedList.isEmpty()) "過去20分間の記録はまだありません" else displayText.toString()
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
                gpxBuilder.append("    <time>${timeString}</time>\n")
                if (safeName.isNotEmpty()) gpxBuilder.append("    <name>${safeName}</name>\n")
                if (safeMemo.isNotEmpty()) gpxBuilder.append("    <desc>${safeMemo}</desc>\n")
                gpxBuilder.append("  </wpt>\n")
            }

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
                    // ★ 変更: 取得した生データを TrackLocation という箱に詰める
                    val trackLoc = TrackLocation(location.latitude, location.longitude, location.time)
                    GpsDataRepository.locationList.add(trackLoc)
                    Log.d("GPS_TRACKER", "Service記録中... 緯度: ${location.latitude}")

                    // ★ 追加: 裏側のネットワーク処理で、緯度経度から「住所（地名）」を推測する
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val geocoder = Geocoder(applicationContext, Locale.getDefault())
                            @Suppress("DEPRECATION")
                            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)

                            if (!addresses.isNullOrEmpty()) {
                                val addr = addresses[0]
                                // 都道府県(州) + 市区町村 を繋げて見やすい文字列にする (例: 東京都品川区)
                                trackLoc.address = listOfNotNull(addr.adminArea, addr.locality, addr.subLocality).joinToString("")
                            }
                        } catch (e: Exception) {
                            // 電波がない場合はエラーを出さずに無視する（緯度経度だけ表示される）
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
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 60000)
            .setMinUpdateIntervalMillis(60000)
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