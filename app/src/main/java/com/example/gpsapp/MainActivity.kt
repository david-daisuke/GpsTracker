package com.example.gpsapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.net.Uri
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
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

// ==========================================
// ★ 共通ロガー機構 (Syslogのような役割)
// ==========================================
object DebugLogger {
    fun log(context: Context, tag: String, message: String) {
        Log.d("GPS_$tag", message) // 常にLogcatには出力する

        val prefs = context.getSharedPreferences("GpsSettings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("DEBUG_MODE", false)) {
            try {
                val dateDirName = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                val fileName = "DebugLog_${dateDirName}.txt"
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val debugDir = File(downloadsDir, "GpsTrackerLogs/DEBUG/$dateDirName")

                if (!debugDir.exists()) debugDir.mkdirs()

                val file = File(debugDir, fileName)
                val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS", Locale.US).format(Date())

                // true を指定して追記モードで書き込む
                FileOutputStream(file, true).use { fos ->
                    fos.write("[$timestamp] [$tag] $message\n".toByteArray())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

data class WaypointData(
    val latitude: Double, val longitude: Double, val altitude: Double,
    val time: Long, val name: String, val memo: String, val address: String
)

data class TrackLocation(
    val latitude: Double, val longitude: Double, val altitude: Double,
    val speed: Float, val time: Long, val distance: Float, var address: String = ""
)

object GpsDataRepository {
    val locationList = mutableListOf<TrackLocation>()
    val waypointList = mutableListOf<WaypointData>()
    var isRecording = false
    var totalDistance = 0.0f
}

class MainActivity : AppCompatActivity() {

    private lateinit var tvRecentLocations: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvAltitude: TextView
    private lateinit var tvDistance: TextView
    private lateinit var mapView: MapView
    private lateinit var altitudeChart: LineChart

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLogger.log(this, "MainActivity", "onCreate invoked")

        Configuration.getInstance().userAgentValue = applicationContext.packageName
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(18.0)
        mapView.controller.setCenter(GeoPoint(35.6895, 139.6917))

        altitudeChart = findViewById(R.id.altitudeChart)
        setupChart()

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnMark = findViewById<Button>(R.id.btnMark)
        val btnSettings = findViewById<Button>(R.id.btnSettings)
        val btnOpenFolder = findViewById<Button>(R.id.btnOpenFolder)
        val btnOpenLogFolder = findViewById<Button>(R.id.btnOpenLogFolder)

        tvRecentLocations = findViewById(R.id.tvRecentLocations)
        tvStatus = findViewById(R.id.tvStatus)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvAltitude = findViewById(R.id.tvAltitude)
        tvDistance = findViewById(R.id.tvDistance)

        if (GpsDataRepository.isRecording) {
            tvStatus.text = "⏺ 記録中"
            tvStatus.setTextColor("#F44336".toColorInt())
        } else {
            tvStatus.text = "⏹ 待機中"
            tvStatus.setTextColor("#B0BEC5".toColorInt())
        }

        btnStart.setOnClickListener { startTracking() }
        btnStop.setOnClickListener { stopTracking() }
        btnMark.setOnClickListener { showMarkDialog() }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnOpenFolder.setOnClickListener { openFolderInFilesApp("GPX") }
        btnOpenLogFolder.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }

        lifecycleScope.launch {
            while (isActive) {
                if (GpsDataRepository.isRecording) {
                    updateRealtimeDisplay()
                    updateRecentLocationsDisplay()
                    updateMapDisplay()
                    updateChartDisplay()
                }
                delay(5000)
            }
        }
    }

    private fun setupChart() {
        altitudeChart.description.isEnabled = false
        altitudeChart.legend.isEnabled = false
        altitudeChart.setTouchEnabled(true)
        altitudeChart.isDragEnabled = true
        altitudeChart.setScaleEnabled(true)
        altitudeChart.axisRight.isEnabled = false

        val xAxis = altitudeChart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.textColor = Color.DKGRAY

        val yAxis = altitudeChart.axisLeft
        yAxis.setDrawGridLines(true)
        yAxis.textColor = Color.DKGRAY
    }

    private fun updateChartDisplay() {
        if (GpsDataRepository.locationList.isEmpty()) {
            altitudeChart.clear()
            return
        }
        val entries = ArrayList<Entry>()
        GpsDataRepository.locationList.forEach { loc ->
            val distKm = (loc.distance / 1000f)
            entries.add(Entry(distKm, loc.altitude.toFloat()))
        }
        val dataSet = LineDataSet(entries, "高度")
        dataSet.color = Color.parseColor("#4CAF50")
        dataSet.setDrawCircles(false)
        dataSet.lineWidth = 2.5f
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER
        dataSet.setDrawValues(false)
        dataSet.setDrawFilled(true)
        dataSet.fillColor = Color.parseColor("#C8E6C9")
        dataSet.fillAlpha = 150

        val lineData = LineData(dataSet)
        altitudeChart.data = lineData
        altitudeChart.invalidate()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    private fun updateMapDisplay() {
        if (GpsDataRepository.locationList.isEmpty()) return
        mapView.overlays.clear()
        val polyline = Polyline()
        polyline.color = android.graphics.Color.BLUE
        polyline.width = 10.0f
        val geoPoints = GpsDataRepository.locationList.map { GeoPoint(it.latitude, it.longitude) }
        polyline.setPoints(geoPoints)
        mapView.overlays.add(polyline)
        GpsDataRepository.waypointList.forEach { wpt ->
            val marker = Marker(mapView)
            marker.position = GeoPoint(wpt.latitude, wpt.longitude)
            marker.title = wpt.name
            marker.snippet = wpt.memo
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            mapView.overlays.add(marker)
        }
        val lastLoc = GpsDataRepository.locationList.last()
        mapView.controller.animateTo(GeoPoint(lastLoc.latitude, lastLoc.longitude))
        mapView.invalidate()
    }

    private fun updateRealtimeDisplay() {
        if (GpsDataRepository.locationList.isNotEmpty()) {
            val lastLoc = GpsDataRepository.locationList.last()
            val speedKmH = lastLoc.speed * 3.6
            val distKm = lastLoc.distance / 1000.0
            tvSpeed.text = String.format(Locale.US, "%.1f km/h", speedKmH)
            tvAltitude.text = String.format(Locale.US, "高度: %.1f m", lastLoc.altitude)
            tvDistance.text = String.format(Locale.US, "距離: %.2f km", distKm)
        }
    }

    private fun showMarkDialog() {
        DebugLogger.log(this, "MainActivity", "showMarkDialog pressed")
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
                DebugLogger.log(this, "MainActivity", "Waypoint saved: $name ($memo)")
                Toast.makeText(this, "スポットを記録しました！", Toast.LENGTH_SHORT).show()
                updateRecentLocationsDisplay()
                updateMapDisplay()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun startTracking() {
        DebugLogger.log(this, "MainActivity", "startTracking pressed")
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            DebugLogger.log(this, "MainActivity", "Permissions missing, requesting...")
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
            return
        }

        GpsDataRepository.locationList.clear()
        GpsDataRepository.waypointList.clear()
        GpsDataRepository.isRecording = true
        GpsDataRepository.totalDistance = 0.0f

        mapView.overlays.clear()
        altitudeChart.clear()

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
        DebugLogger.log(this, "MainActivity", "stopTracking pressed")
        if (!GpsDataRepository.isRecording) return
        val serviceIntent = Intent(this, GpsTrackerService::class.java)
        stopService(serviceIntent)
        GpsDataRepository.isRecording = false

        tvStatus.text = "⏹ 待機中"
        tvStatus.setTextColor("#B0BEC5".toColorInt())

        if (GpsDataRepository.locationList.isNotEmpty()) {
            saveToGpxAndLogFile()
        } else {
            Toast.makeText(this, "記録されたデータがありません", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRecentLocationsDisplay() {
        val twentyMinutesAgo = System.currentTimeMillis() - (20 * 60 * 1000)
        val recentLocations = GpsDataRepository.locationList.filter { it.time >= twentyMinutesAgo }.map { loc ->
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
        val recentWaypoints = GpsDataRepository.waypointList.filter { it.time >= twentyMinutesAgo }.map { wpt ->
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
            displayText.append("$timeStr ${item.second}\n\n")
        }
        tvRecentLocations.text = if (combinedList.isEmpty()) "過去20分間の記録はまだありません" else displayText.toString().trim()
    }

    private fun openFolderInFilesApp(subFolderName: String) {
        DebugLogger.log(this, "MainActivity", "openFolderInFilesApp: $subFolderName")
        val dateDirName = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val path = "GpsTrackerLogs%2F$subFolderName%2F$dateDirName"
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download%2F$path")
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "vnd.android.document/directory")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            } catch (e2: Exception) {
                Toast.makeText(this, "ファイルアプリが見つかりません", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveToGpxAndLogFile() {
        DebugLogger.log(this, "MainActivity", "saveToGpxAndLogFile invoked")
        try {
            val twentyMinutesAgo = System.currentTimeMillis() - (20 * 60 * 1000)
            val recentWaypoints = GpsDataRepository.waypointList.filter { it.time >= twentyMinutesAgo }
            val recentLocations = GpsDataRepository.locationList.filter { it.time >= twentyMinutesAgo }
            if (recentLocations.isEmpty() && recentWaypoints.isEmpty()) {
                Toast.makeText(this, "直近20分間の記録データがありません", Toast.LENGTH_SHORT).show()
                return
            }
            val sdfUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val fileNameTime = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val dateDirName = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val gpxFileName = "Track_${fileNameTime}.gpx"
            val txtFileName = "Log_${fileNameTime}.txt"
            val minTime = (recentLocations.map { it.time } + recentWaypoints.map { it.time }).minOrNull() ?: System.currentTimeMillis()
            val maxTime = (recentLocations.map { it.time } + recentWaypoints.map { it.time }).maxOrNull() ?: System.currentTimeMillis()
            val timeFormatForLog = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)
            val logBuilder = StringBuilder()
            logBuilder.append("【GPSトラッカー 記録ログ】\n")
            logBuilder.append("保存対象期間: ${timeFormatForLog.format(Date(minTime))} 〜 ${timeFormatForLog.format(Date(maxTime))}\n")
            logBuilder.append("関連GPXファイル名: $gpxFileName\n")
            logBuilder.append("-----------------------------------------\n")
            val logLocations = recentLocations.map { loc ->
                val speedKmH = loc.speed * 3.6
                val distKm = loc.distance / 1000.0
                Pair(loc.time, "📍 緯度: ${loc.latitude}, 経度: ${loc.longitude}\n    速度: ${String.format(Locale.US, "%.1f", speedKmH)} km/h, 高度: ${loc.altitude} m, 距離: ${String.format(Locale.US, "%.2f", distKm)} km")
            }
            val logWaypoints = recentWaypoints.map { wpt ->
                val memoText = if (wpt.memo.isNotEmpty()) " - ${wpt.memo}" else ""
                Pair(wpt.time, "⭐ [マーク] ${wpt.name}$memoText\n    📍 緯度: ${wpt.latitude}, 経度: ${wpt.longitude}")
            }
            val combinedLogList = (logLocations + logWaypoints).sortedBy { it.first }
            val sdfTimeOnly = SimpleDateFormat("HH:mm:ss", Locale.US)
            combinedLogList.forEach { item ->
                logBuilder.append("[${sdfTimeOnly.format(Date(item.first))}] ${item.second}\n\n")
            }
            val gpxBuilder = java.lang.StringBuilder()
            gpxBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            gpxBuilder.append("<gpx version=\"1.1\" creator=\"MyTracker\">\n")
            for (wpt in recentWaypoints) {
                val timeString = sdfUtc.format(Date(wpt.time))
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
            for (loc in recentLocations) {
                val timeString = sdfUtc.format(Date(loc.time))
                gpxBuilder.append("      <trkpt lat=\"${loc.latitude}\" lon=\"${loc.longitude}\">\n")
                gpxBuilder.append("        <ele>${loc.altitude}</ele>\n")
                gpxBuilder.append("        <time>${timeString}</time>\n")
                gpxBuilder.append("      </trkpt>\n")
            }
            gpxBuilder.append("    </trkseg>\n  </trk>\n</gpx>")

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val gpxDir = File(downloadsDir, "GpsTrackerLogs/GPX/$dateDirName")
            if (!gpxDir.exists()) gpxDir.mkdirs()
            FileOutputStream(File(gpxDir, gpxFileName)).use { it.write(gpxBuilder.toString().toByteArray()) }
            val logDir = File(downloadsDir, "GpsTrackerLogs/LOGS/$dateDirName")
            if (!logDir.exists()) logDir.mkdirs()
            FileOutputStream(File(logDir, txtFileName)).use { it.write(logBuilder.toString().toByteArray()) }

            DebugLogger.log(this, "MainActivity", "Successfully saved files: $gpxFileName & $txtFileName")
            Toast.makeText(this, "GPXとテキストログを保存しました！", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            DebugLogger.log(this, "MainActivity", "Exception during save: ${e.message}")
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
        DebugLogger.log(this, "GpsTrackerService", "onCreate invoked")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {

                    // ★ 1. 高度の補正処理 (ジオイド高の補正)
                    var realAltitude = location.altitude
                    if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()) {
                        // Android 14以降の機能で正確な海抜(MSL)を取得
                        realAltitude = location.mslAltitudeMeters
                    } else {
                        // 未対応の場合は東京周辺のジオイド高(約36m)を簡易的に引く
                        realAltitude -= 36.0
                    }

                    // ログ出力 (生の高度と補正後の高度を比較できるように)
                    DebugLogger.log(this@GpsTrackerService, "GpsTrackerService", "Location received -> Lat: ${location.latitude}, Lon: ${location.longitude}, RawAlt: ${location.altitude}m, RealAlt: ${realAltitude}m")

                    val lastSavedLoc = GpsDataRepository.locationList.lastOrNull()
                    if (lastSavedLoc != null) {
                        val prevLocation = Location("").apply {
                            latitude = lastSavedLoc.latitude
                            longitude = lastSavedLoc.longitude
                        }
                        GpsDataRepository.totalDistance += prevLocation.distanceTo(location)
                    }

                    // ★ 2. 補正した realAltitude を TrackLocation に保存する
                    val trackLoc = TrackLocation(
                        location.latitude,
                        location.longitude,
                        realAltitude, // ← ここを location.altitude から変更
                        location.speed,
                        location.time,
                        GpsDataRepository.totalDistance
                    )
                    GpsDataRepository.locationList.add(trackLoc)

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
                            DebugLogger.log(this@GpsTrackerService, "GpsTrackerService", "Geocoder error: ${e.message}")
                        }
                    }
                }
            }
        }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLogger.log(this, "GpsTrackerService", "onStartCommand invoked, building foreground notification")
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
        val prefs = getSharedPreferences("GpsSettings", Context.MODE_PRIVATE)
        val intervalMs = prefs.getLong("INTERVAL", 5000L)
        val accuracy = prefs.getInt("ACCURACY", Priority.PRIORITY_HIGH_ACCURACY)

        DebugLogger.log(this, "GpsTrackerService", "Requesting updates -> Interval: ${intervalMs}ms, Accuracy setting: $accuracy")

        val locationRequest = LocationRequest.Builder(accuracy, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugLogger.log(this, "GpsTrackerService", "onDestroy invoked, removing location updates")
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "GPS Tracking Channel", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}