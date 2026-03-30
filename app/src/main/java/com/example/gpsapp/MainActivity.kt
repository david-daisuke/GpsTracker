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
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import androidx.documentfile.provider.DocumentFile // ★ 追加: クラウド同期用
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
// ★ 共通ロガー機構
// ==========================================
object DebugLogger {
    fun log(context: Context, tag: String, message: String) {
        Log.d("GPS_$tag", message)
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
    val time: Long, val name: String, val memo: String, val address: String,
    val photoPath: String = ""
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

    private var currentPhotoPath: String? = null
    private var currentPhotoUri: Uri? = null
    private var dialogImageView: ImageView? = null
    private lateinit var takePictureLauncher: ActivityResultLauncher<Uri>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLogger.log(this, "MainActivity", "onCreate invoked")

        Configuration.getInstance().userAgentValue = applicationContext.packageName
        setContentView(R.layout.activity_main)

        takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                dialogImageView?.setImageURI(currentPhotoUri)
                DebugLogger.log(this, "MainActivity", "📸 写真撮影成功！保存先フォルダとファイル: $currentPhotoPath")
            } else {
                currentPhotoPath = null
            }
        }

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

    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val appDir = File(storageDir, "GpsTrackerPhotos")
        if (!appDir.exists()) appDir.mkdirs()

        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", appDir).apply {
            currentPhotoPath = absolutePath
        }
    }

    private fun setupChart() {
        altitudeChart.description.isEnabled = false
        altitudeChart.legend.isEnabled = false
        altitudeChart.setTouchEnabled(true)
        altitudeChart.isDragEnabled = true
        altitudeChart.setScaleEnabled(true)
        altitudeChart.axisRight.isEnabled = false
        altitudeChart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        altitudeChart.xAxis.setDrawGridLines(false)
        altitudeChart.xAxis.textColor = Color.DKGRAY
        altitudeChart.axisLeft.setDrawGridLines(true)
        altitudeChart.axisLeft.textColor = Color.DKGRAY
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
        altitudeChart.data = LineData(dataSet)
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
        polyline.setPoints(GpsDataRepository.locationList.map { GeoPoint(it.latitude, it.longitude) })
        mapView.overlays.add(polyline)

        GpsDataRepository.waypointList.forEach { wpt ->
            val marker = Marker(mapView)
            marker.position = GeoPoint(wpt.latitude, wpt.longitude)
            marker.title = if (wpt.photoPath.isNotEmpty()) "📸 ${wpt.name}" else wpt.name
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
            tvSpeed.text = String.format(Locale.US, "%.1f km/h", lastLoc.speed * 3.6)
            tvAltitude.text = String.format(Locale.US, "高度: %.1f m", lastLoc.altitude)
            tvDistance.text = String.format(Locale.US, "距離: %.2f km", lastLoc.distance / 1000.0)
        }
    }

    private fun showMarkDialog() {
        if (!GpsDataRepository.isRecording || GpsDataRepository.locationList.isEmpty()) {
            Toast.makeText(this, "GPSの記録を開始してからマークできます", Toast.LENGTH_SHORT).show()
            return
        }
        val lastLocation = GpsDataRepository.locationList.last()
        currentPhotoPath = null

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val etName = EditText(this).apply { hint = "名称 (例: 絶景ポイント)" }
        layout.addView(etName)

        val etMemo = EditText(this).apply { hint = "メモ" }
        layout.addView(etMemo)

        dialogImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(0, 20, 0, 20)
        }

        val btnCamera = Button(this).apply {
            text = "📸 写真を撮る"
            backgroundTintList = getColorStateList(android.R.color.holo_blue_light)
            setTextColor(Color.WHITE)
            setOnClickListener {
                try {
                    val photoFile = createImageFile()
                    val photoURI = FileProvider.getUriForFile(this@MainActivity, "com.example.gpsapp.fileprovider", photoFile)
                    currentPhotoUri = photoURI
                    takePictureLauncher.launch(photoURI)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "カメラの起動に失敗しました", Toast.LENGTH_SHORT).show()
                }
            }
        }

        layout.addView(btnCamera)
        layout.addView(dialogImageView)

        AlertDialog.Builder(this)
            .setTitle("📍 現在地を記録")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString()
                val memo = etMemo.text.toString()
                val finalPhotoPath = currentPhotoPath ?: ""

                val wpt = WaypointData(
                    lastLocation.latitude, lastLocation.longitude, lastLocation.altitude,
                    System.currentTimeMillis(), name, memo, lastLocation.address, finalPhotoPath
                )
                GpsDataRepository.waypointList.add(wpt)
                Toast.makeText(this, "スポットを記録しました！", Toast.LENGTH_SHORT).show()
                updateRecentLocationsDisplay()
                updateMapDisplay()

                dialogImageView = null
            }
            .setNegativeButton("キャンセル") { _, _ ->
                dialogImageView = null
                currentPhotoPath = null
            }
            .show()
    }

    private fun startTracking() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
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

        val serviceIntent = Intent(this, GpsTrackerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)

        Toast.makeText(this, "トラッキングを開始しました", Toast.LENGTH_SHORT).show()
    }

    private fun stopTracking() {
        if (!GpsDataRepository.isRecording) return
        stopService(Intent(this, GpsTrackerService::class.java))
        GpsDataRepository.isRecording = false
        tvStatus.text = "⏹ 待機中"
        tvStatus.setTextColor("#B0BEC5".toColorInt())

        if (GpsDataRepository.locationList.isNotEmpty()) saveToGpxAndLogFile()
        else Toast.makeText(this, "記録されたデータがありません", Toast.LENGTH_SHORT).show()
    }

    private fun updateRecentLocationsDisplay() {
        val twentyMinutesAgo = System.currentTimeMillis() - (20 * 60 * 1000)
        val recentLocations = GpsDataRepository.locationList.filter { it.time >= twentyMinutesAgo }.map { loc ->
            Pair(loc.time, "📍 緯度: ${String.format(Locale.US, "%.4f", loc.latitude)}, 経度: ${String.format(Locale.US, "%.4f", loc.longitude)}\n    速度: ${String.format(Locale.US, "%.1f", loc.speed * 3.6)} km/h, 高度: ${String.format(Locale.US, "%.1f", loc.altitude)} m")
        }
        val recentWaypoints = GpsDataRepository.waypointList.filter { it.time >= twentyMinutesAgo }.map { wpt ->
            val photoMark = if (wpt.photoPath.isNotEmpty()) "📸" else "⭐"
            Pair(wpt.time, "$photoMark [マーク] ${if(wpt.name.isNotEmpty()) wpt.name else "名称なし"}\n    📍 緯度: ${String.format(Locale.US, "%.4f", wpt.latitude)}, 経度: ${String.format(Locale.US, "%.4f", wpt.longitude)}")
        }
        val combinedList = (recentLocations + recentWaypoints).sortedByDescending { it.first }
        val displayText = java.lang.StringBuilder("【過去20分間の記録: ${combinedList.size}件】\n")
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        combinedList.forEach { displayText.append("${sdf.format(Date(it.first))} ${it.second}\n\n") }
        tvRecentLocations.text = if (combinedList.isEmpty()) "過去20分間の記録はまだありません" else displayText.toString().trim()
    }

    private fun openFolderInFilesApp(subFolderName: String) {
        val dateDirName = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val path = "GpsTrackerLogs%2F$subFolderName%2F$dateDirName"
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download%2F$path")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "vnd.android.document/directory")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) }
        catch (e: Exception) { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
    }

    private fun saveToGpxAndLogFile() {
        try {
            val dateDirName = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val fileNameTime = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val sdfUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val sdfLog = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)

            // ==========================================
            // ★ テキストログ作成
            // ==========================================
            val logBuilder = java.lang.StringBuilder()
            logBuilder.append("【GPSトラッカー 記録ログ】\n")
            logBuilder.append("関連GPXファイル名: Track_${fileNameTime}.gpx\n")
            logBuilder.append("-----------------------------------------\n")

            for (wpt in GpsDataRepository.waypointList) {
                logBuilder.append("[${sdfLog.format(Date(wpt.time))}] ⭐ スポット記録: ${wpt.name}\n")
                logBuilder.append("    メモ: ${wpt.memo}\n")
                logBuilder.append("    座標: 緯度 ${wpt.latitude}, 経度 ${wpt.longitude}, 高度 ${wpt.altitude}m\n")
                if (wpt.photoPath.isNotEmpty()) {
                    logBuilder.append("    📸 写真保存先: ${wpt.photoPath}\n")
                }
                logBuilder.append("\n")
            }

            // ==========================================
            // ★ GPXファイル作成
            // ==========================================
            val gpxBuilder = java.lang.StringBuilder()
            gpxBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"MyTracker\">\n")

            for (wpt in GpsDataRepository.waypointList) {
                gpxBuilder.append("  <wpt lat=\"${wpt.latitude}\" lon=\"${wpt.longitude}\">\n")
                gpxBuilder.append("    <ele>${wpt.altitude}</ele>\n    <time>${sdfUtc.format(Date(wpt.time))}</time>\n")
                if (wpt.name.isNotEmpty()) gpxBuilder.append("    <name>${wpt.name.replace("<", "&lt;")}</name>\n")
                if (wpt.memo.isNotEmpty()) gpxBuilder.append("    <desc>${wpt.memo.replace("<", "&lt;")}</desc>\n")
                if (wpt.photoPath.isNotEmpty()) gpxBuilder.append("    <link href=\"file://${wpt.photoPath}\" />\n")
                gpxBuilder.append("  </wpt>\n")
            }

            gpxBuilder.append("  <trk>\n    <trkseg>\n")
            for (loc in GpsDataRepository.locationList) {
                gpxBuilder.append("      <trkpt lat=\"${loc.latitude}\" lon=\"${loc.longitude}\">\n")
                gpxBuilder.append("        <ele>${loc.altitude}</ele>\n        <time>${sdfUtc.format(Date(loc.time))}</time>\n")
                gpxBuilder.append("      </trkpt>\n")
            }
            gpxBuilder.append("    </trkseg>\n  </trk>\n</gpx>")

            // ==========================================
            // ★ 1. ローカル(スマホ内)への保存処理
            // ==========================================
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val gpxDir = File(downloadsDir, "GpsTrackerLogs/GPX/$dateDirName").apply { if (!exists()) mkdirs() }
            FileOutputStream(File(gpxDir, "Track_${fileNameTime}.gpx")).use { it.write(gpxBuilder.toString().toByteArray()) }

            val logDir = File(downloadsDir, "GpsTrackerLogs/LOGS/$dateDirName").apply { if (!exists()) mkdirs() }
            FileOutputStream(File(logDir, "Log_${fileNameTime}.txt")).use { it.write(logBuilder.toString().toByteArray()) }

            // ==========================================
            // ★ 2. クラウド(Google Drive等)への自動同期処理
            // ==========================================
            val prefs = getSharedPreferences("GpsSettings", Context.MODE_PRIVATE)
            if (prefs.getBoolean("AUTO_SYNC", false)) {
                val uriStr = prefs.getString("SYNC_FOLDER_URI", null)
                if (uriStr != null) {
                    try {
                        val treeUri = Uri.parse(uriStr)
                        val pickedDir = DocumentFile.fromTreeUri(this, treeUri)

                        // クラウド上に日付フォルダ(例: 20260330)を作成
                        var cloudDateDir = pickedDir?.findFile(dateDirName)
                        if (cloudDateDir == null) {
                            cloudDateDir = pickedDir?.createDirectory(dateDirName)
                        }

                        // クラウド上にファイルを作成して書き込み
                        if (cloudDateDir != null) {
                            val cloudGpx = cloudDateDir.createFile("application/gpx+xml", "Track_${fileNameTime}.gpx")
                            cloudGpx?.uri?.let { uri ->
                                contentResolver.openOutputStream(uri)?.use { it.write(gpxBuilder.toString().toByteArray()) }
                            }

                            val cloudLog = cloudDateDir.createFile("text/plain", "Log_${fileNameTime}.txt")
                            cloudLog?.uri?.let { uri ->
                                contentResolver.openOutputStream(uri)?.use { it.write(logBuilder.toString().toByteArray()) }
                            }
                            DebugLogger.log(this, "MainActivity", "Cloud Auto-Sync Success to: ${pickedDir?.name}")
                            Toast.makeText(this, "ローカルとクラウド(Drive等)に保存しました！", Toast.LENGTH_LONG).show()
                            return // クラウド保存成功時はここで抜ける
                        }
                    } catch (e: Exception) {
                        DebugLogger.log(this, "MainActivity", "Cloud Sync Failed: ${e.message}")
                    }
                }
            }

            Toast.makeText(this, "ローカルに保存しました！", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }
}

class GpsTrackerService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    var realAltitude = location.altitude
                    if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()) realAltitude = location.mslAltitudeMeters
                    else realAltitude -= 36.0

                    val lastSavedLoc = GpsDataRepository.locationList.lastOrNull()
                    if (lastSavedLoc != null) {
                        GpsDataRepository.totalDistance += Location("").apply { latitude = lastSavedLoc.latitude; longitude = lastSavedLoc.longitude }.distanceTo(location)
                    }
                    val trackLoc = TrackLocation(location.latitude, location.longitude, realAltitude, location.speed, location.time, GpsDataRepository.totalDistance)
                    GpsDataRepository.locationList.add(trackLoc)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("GpsTrackerChannel", "GPS Tracking Channel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, "GpsTrackerChannel").setContentTitle("GPS Tracker").setContentText("記録中...").setSmallIcon(android.R.drawable.ic_menu_mylocation).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1, notification)

        val prefs = getSharedPreferences("GpsSettings", Context.MODE_PRIVATE)
        val locationRequest = LocationRequest.Builder(prefs.getInt("ACCURACY", Priority.PRIORITY_HIGH_ACCURACY), prefs.getLong("INTERVAL", 5000L)).setMinUpdateIntervalMillis(prefs.getLong("INTERVAL", 5000L)).build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
    override fun onBind(intent: Intent?): IBinder? = null
}