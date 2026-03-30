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
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object DebugLogger {
    fun log(context: Context, tag: String, message: String) {
        Log.d("GPS_$tag", message)
    }
}

data class AppEvent(val time: Long, val message: String)

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
    val eventList = mutableListOf<AppEvent>()
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
    private lateinit var gpxPickerLauncher: ActivityResultLauncher<String> // GPX読込用

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = Log.getStackTraceString(throwable)
            try {
                getSharedPreferences("CrashLogPrefs", Context.MODE_PRIVATE).edit().putString("CRASH_DATA", stackTrace).commit()
            } catch (e: Exception) {}
            defaultUncaughtExceptionHandler?.uncaughtException(thread, throwable)
        }

        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = applicationContext.packageName
        osmConfig.osmdroidBasePath = applicationContext.getDir("osmdroid", Context.MODE_PRIVATE)
        val tileCache = File(osmConfig.osmdroidBasePath, "tiles")
        tileCache.mkdirs()
        osmConfig.osmdroidTileCache = tileCache

        setContentView(R.layout.activity_main)

        val crashPrefs = getSharedPreferences("CrashLogPrefs", Context.MODE_PRIVATE)
        val crashData = crashPrefs.getString("CRASH_DATA", null)
        if (crashData != null) {
            AlertDialog.Builder(this)
                .setTitle("🚨 前回のクラッシュログ")
                .setMessage(crashData)
                .setPositiveButton("閉じる", null)
                .show()
            crashPrefs.edit().remove("CRASH_DATA").apply()
        }

        takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                dialogImageView?.setImageURI(currentPhotoUri)
            } else {
                currentPhotoPath = null
            }
        }

        // ==========================================
        // ★ GPX読み込み用のファイルピッカー
        // ==========================================
        gpxPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                loadGpxFile(uri)
            }
        }

        mapView = findViewById(R.id.mapView)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(18.0)
        mapView.controller.setCenter(GeoPoint(35.6895, 139.6917))

        val customOsmTileSource = XYTileSource("CustomOSM", 0, 19, 256, ".png", arrayOf("https://tile.openstreetmap.org/"))
        mapView.setTileSource(customOsmTileSource)

        altitudeChart = findViewById(R.id.altitudeChart)
        setupChart()

        findViewById<Button>(R.id.btnStart).setOnClickListener { checkDisclosureAndStart() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopTracking() }
        findViewById<Button>(R.id.btnMark).setOnClickListener { showMarkDialog() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.btnOpenFolder).setOnClickListener { openFolderInFilesApp("GPX") }
        findViewById<Button>(R.id.btnOpenLogFolder).setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        findViewById<Button>(R.id.btnDownloadMap).setOnClickListener { downloadOfflineMap() }

        // ★ GPX読み込みボタンの動作
        findViewById<Button>(R.id.btnLoadGpx).setOnClickListener {
            if (GpsDataRepository.isRecording) {
                Toast.makeText(this, "記録中は読み込めません。STOPを押してください。", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            gpxPickerLauncher.launch("*/*") // すべてのファイルから選択
        }

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
        altitudeChart.isHighlightPerTapEnabled = true // タップによる強調表示を有効化

        // ==========================================
        // ★ グラフタップ時に地図と連動する機能
        // ==========================================
        altitudeChart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e == null || GpsDataRepository.locationList.isEmpty()) return

                val startTime = GpsDataRepository.locationList.first().time
                val targetTime = startTime + e.x.toLong() // x軸(差分時間)から実際の時刻を逆算

                // タップした時間に最も近いGPS座標を探す
                val closestLoc = GpsDataRepository.locationList.minByOrNull { Math.abs(it.time - targetTime) }

                if (closestLoc != null) {
                    val geoPoint = GeoPoint(closestLoc.latitude, closestLoc.longitude)

                    // 古い強調マーカーを消す
                    mapView.overlays.removeAll { it is Marker && it.id == "HIGHLIGHT_MARKER" }

                    // 新しい強調マーカー（ピン）を置く
                    val marker = Marker(mapView)
                    marker.id = "HIGHLIGHT_MARKER"
                    marker.position = geoPoint
                    marker.title = "選択地点: 高度 ${String.format(Locale.US, "%.1f", closestLoc.altitude)}m"
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    mapView.overlays.add(marker)

                    // 地図をその場所に飛ばして情報ウィンドウを開く
                    mapView.controller.animateTo(geoPoint)
                    marker.showInfoWindow()
                    mapView.invalidate()
                }
            }

            override fun onNothingSelected() {
                // 選択解除時に強調マーカーを消す
                mapView.overlays.removeAll { it is Marker && it.id == "HIGHLIGHT_MARKER" }
                mapView.invalidate()
            }
        })
    }

    private fun updateChartDisplay() {
        if (GpsDataRepository.locationList.isEmpty()) {
            altitudeChart.clear()
            return
        }

        val startTime = GpsDataRepository.locationList.first().time
        val entries = ArrayList<Entry>()

        GpsDataRepository.locationList.forEach { loc ->
            val timeDiff = (loc.time - startTime).toFloat()
            entries.add(Entry(timeDiff, loc.altitude.toFloat()))
        }

        altitudeChart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getAxisLabel(value: Float, axis: AxisBase?): String {
                val date = Date(startTime + value.toLong())
                return SimpleDateFormat("HH:mm", Locale.US).format(date)
            }
        }

        val dataSet = LineDataSet(entries, "高度").apply {
            color = Color.parseColor("#4CAF50")
            setDrawCircles(false)
            lineWidth = 2.5f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawValues(false)
            setDrawFilled(true)
            fillColor = Color.parseColor("#C8E6C9")
            fillAlpha = 150
            highLightColor = Color.RED // タップ時の強調線の色
            highlightLineWidth = 2f
        }
        altitudeChart.data = LineData(dataSet)
        altitudeChart.invalidate()
    }

    // ==========================================
    // ★ GPXファイルのパース（読み込み）機能
    // ==========================================
    private fun loadGpxFile(uri: Uri) {
        GpsDataRepository.locationList.clear()
        GpsDataRepository.waypointList.clear()
        GpsDataRepository.eventList.clear()
        GpsDataRepository.totalDistance = 0.0f

        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(inputStream, null)

                var eventType = parser.eventType
                var currentLat = 0.0
                var currentLon = 0.0
                var currentEle = 0.0
                var currentTime = 0L
                val sdfUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    val tagName = parser.name
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            if (tagName == "trkpt" || tagName == "wpt") {
                                currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                                currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            } else if (tagName == "ele") {
                                currentEle = parser.nextText().toDoubleOrNull() ?: 0.0
                            } else if (tagName == "time") {
                                val timeStr = parser.nextText()
                                try { currentTime = sdfUtc.parse(timeStr)?.time ?: 0L } catch (e: Exception) {}
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (tagName == "trkpt") {
                                val dist = if(GpsDataRepository.locationList.isEmpty()) 0f else {
                                    val last = GpsDataRepository.locationList.last()
                                    val res = FloatArray(1)
                                    Location.distanceBetween(last.latitude, last.longitude, currentLat, currentLon, res)
                                    last.distance + res[0]
                                }
                                GpsDataRepository.locationList.add(TrackLocation(currentLat, currentLon, currentEle, 0f, currentTime, dist))
                                GpsDataRepository.totalDistance = dist
                            } else if (tagName == "wpt") {
                                GpsDataRepository.waypointList.add(WaypointData(currentLat, currentLon, currentEle, currentTime, "GPXインポート地点", "", ""))
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }

            if (GpsDataRepository.locationList.isNotEmpty()) {
                Toast.makeText(this, "GPXを読み込みました！", Toast.LENGTH_SHORT).show()
                GpsDataRepository.eventList.add(AppEvent(System.currentTimeMillis(), "📥 GPXファイルをインポートしました"))
                updateMapDisplay()
                updateChartDisplay()
                updateRecentLocationsDisplay()
                updateRealtimeDisplay()

                // 読み込んだルートの先頭に地図を移動
                val firstLoc = GpsDataRepository.locationList.first()
                mapView.controller.animateTo(GeoPoint(firstLoc.latitude, firstLoc.longitude))
                mapView.controller.setZoom(14.0)
            } else {
                Toast.makeText(this, "GPXからデータを取り出せませんでした", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            val stackTrace = Log.getStackTraceString(e)
            DebugLogger.log(this, "GPX_LOAD", stackTrace)
            Toast.makeText(this, "GPXの読み込みに失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }

    private fun updateMapDisplay() {
        if (GpsDataRepository.locationList.isEmpty()) return

        // 選択地点マーカー（HIGHLIGHT_MARKER）以外を消去
        mapView.overlays.removeAll { it !is Marker || it.id != "HIGHLIGHT_MARKER" }

        val polyline = Polyline().apply {
            color = android.graphics.Color.BLUE
            width = 10.0f
            setPoints(GpsDataRepository.locationList.map { GeoPoint(it.latitude, it.longitude) })
        }
        mapView.overlays.add(polyline)

        GpsDataRepository.waypointList.forEach { wpt ->
            val marker = Marker(mapView)
            marker.position = GeoPoint(wpt.latitude, wpt.longitude)
            marker.title = if (wpt.photoPath.isNotEmpty()) "📸 ${wpt.name}" else wpt.name
            marker.snippet = wpt.memo
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            mapView.overlays.add(marker)
        }

        // 記録中のみ現在地へ追従する
        if (GpsDataRepository.isRecording) {
            val lastLoc = GpsDataRepository.locationList.last()
            mapView.controller.animateTo(GeoPoint(lastLoc.latitude, lastLoc.longitude))
        }
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
        val etMemo = EditText(this).apply { hint = "メモ" }
        layout.addView(etName)
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
                    Toast.makeText(this@MainActivity, "カメラ起動失敗", Toast.LENGTH_SHORT).show()
                }
            }
        }
        layout.addView(btnCamera)
        layout.addView(dialogImageView)

        AlertDialog.Builder(this)
            .setTitle("📍 現在地を記録")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val wpt = WaypointData(
                    lastLocation.latitude, lastLocation.longitude, lastLocation.altitude,
                    System.currentTimeMillis(), etName.text.toString(), etMemo.text.toString(), lastLocation.address, currentPhotoPath ?: ""
                )
                GpsDataRepository.waypointList.add(wpt)
                Toast.makeText(this, "スポットを記録しました！", Toast.LENGTH_SHORT).show()
                updateRecentLocationsDisplay()
                updateMapDisplay()
                dialogImageView = null
            }
            .setNegativeButton("キャンセル") { _, _ -> dialogImageView = null; currentPhotoPath = null }
            .show()
    }

    private fun downloadOfflineMap() {
        try {
            val boundingBox = mapView.boundingBox
            val currentZoom = mapView.zoomLevelDouble.toInt()
            val maxZoom = minOf(currentZoom + 2, 18)

            val cacheManager = CacheManager(mapView)
            val tileCount = cacheManager.possibleTilesInArea(boundingBox, currentZoom, maxZoom)

            if (tileCount > 15000) {
                Toast.makeText(this, "範囲が広すぎます(約 $tileCount 枚)。もう少し拡大してください。", Toast.LENGTH_LONG).show()
                return
            }

            AlertDialog.Builder(this)
                .setTitle("🗺️ オフライン地図の保存")
                .setMessage("現在表示されている範囲の地図を保存します。\n\n・画像数: 約 $tileCount 枚")
                .setPositiveButton("ダウンロード開始") { _, _ ->
                    cacheManager.downloadAreaAsync(this@MainActivity, boundingBox, currentZoom, maxZoom, object : CacheManager.CacheManagerCallback {
                        override fun onTaskComplete() {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "✅ オフラインマップのダウンロードが完了しました", Toast.LENGTH_LONG).show()
                                GpsDataRepository.eventList.add(AppEvent(System.currentTimeMillis(), "📥 [システム] オフライン地図の保存完了"))
                                updateRecentLocationsDisplay()
                            }
                        }
                        override fun onTaskFailed(errors: Int) {
                            runOnUiThread { Toast.makeText(this@MainActivity, "❌ ダウンロード失敗: $errors 件", Toast.LENGTH_SHORT).show() }
                        }
                        override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {}
                        override fun downloadStarted() {}
                        override fun setPossibleTilesInArea(total: Int) {}
                    })
                }
                .setNegativeButton("キャンセル", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "マップの準備エラーが発生しました", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkDisclosureAndStart() {
        val prefs = getSharedPreferences("GpsSettings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("DISCLOSURE_ACCEPTED", false)) {
            AlertDialog.Builder(this)
                .setTitle("【重要】位置情報の利用について")
                .setMessage("本アプリは、ユーザーの移動ルートを記録・表示するために位置情報を収集します。\n\n" +
                        "「START」を押して記録を開始すると、アプリを閉じている間やバックグラウンド状態であっても、移動経路を追跡するために常に位置情報を取得し続けます。\n\n" +
                        "よろしければ同意して、権限の許可へお進みください。")
                .setPositiveButton("同意して次へ") { _, _ ->
                    prefs.edit().putBoolean("DISCLOSURE_ACCEPTED", true).apply()
                    startTracking()
                }
                .setNegativeButton("キャンセル", null)
                .show()
        } else {
            startTracking()
        }
    }

    private fun startTracking() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
            return
        }

        GpsDataRepository.locationList.clear()
        GpsDataRepository.waypointList.clear()
        GpsDataRepository.eventList.clear()
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
        else Toast.makeText(this, "記録データがありません", Toast.LENGTH_SHORT).show()
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
        val recentEvents = GpsDataRepository.eventList.filter { it.time >= twentyMinutesAgo }.map { ev ->
            Pair(ev.time, ev.message)
        }

        val combinedList = (recentLocations + recentWaypoints + recentEvents).sortedByDescending { it.first }
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

            val logBuilder = java.lang.StringBuilder()
            logBuilder.append("【GPSトラッカー 記録ログ】\n関連GPXファイル名: Track_${fileNameTime}.gpx\n-----------------------------------------\n")
            for (wpt in GpsDataRepository.waypointList) {
                logBuilder.append("[${sdfLog.format(Date(wpt.time))}] ⭐ スポット記録: ${wpt.name}\n    メモ: ${wpt.memo}\n    座標: 緯度 ${wpt.latitude}, 経度 ${wpt.longitude}, 高度 ${wpt.altitude}m\n")
                if (wpt.photoPath.isNotEmpty()) logBuilder.append("    📸 写真保存先: ${wpt.photoPath}\n")
                logBuilder.append("\n")
            }

            val gpxBuilder = java.lang.StringBuilder()
            gpxBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"MyTracker\">\n")
            for (wpt in GpsDataRepository.waypointList) {
                gpxBuilder.append("  <wpt lat=\"${wpt.latitude}\" lon=\"${wpt.longitude}\">\n    <ele>${wpt.altitude}</ele>\n    <time>${sdfUtc.format(Date(wpt.time))}</time>\n")
                if (wpt.name.isNotEmpty()) gpxBuilder.append("    <name>${wpt.name.replace("<", "&lt;")}</name>\n")
                if (wpt.memo.isNotEmpty()) gpxBuilder.append("    <desc>${wpt.memo.replace("<", "&lt;")}</desc>\n")
                if (wpt.photoPath.isNotEmpty()) gpxBuilder.append("    <link href=\"file://${wpt.photoPath}\" />\n")
                gpxBuilder.append("  </wpt>\n")
            }
            gpxBuilder.append("  <trk>\n    <trkseg>\n")
            for (loc in GpsDataRepository.locationList) {
                gpxBuilder.append("      <trkpt lat=\"${loc.latitude}\" lon=\"${loc.longitude}\">\n        <ele>${loc.altitude}</ele>\n        <time>${sdfUtc.format(Date(loc.time))}</time>\n      </trkpt>\n")
            }
            gpxBuilder.append("    </trkseg>\n  </trk>\n</gpx>")

            var savedToCustom = false
            val prefs = getSharedPreferences("GpsSettings", Context.MODE_PRIVATE)

            if (prefs.getBoolean("USE_CUSTOM_FOLDER", false)) {
                val uriStr = prefs.getString("CUSTOM_FOLDER_URI", null)
                if (uriStr != null) {
                    try {
                        val treeUri = Uri.parse(uriStr)
                        val pickedDir = DocumentFile.fromTreeUri(this, treeUri)
                        var targetDir = pickedDir?.findFile(dateDirName)
                        if (targetDir == null) targetDir = pickedDir?.createDirectory(dateDirName)

                        if (targetDir != null) {
                            val gpxFile = targetDir.createFile("application/gpx+xml", "Track_${fileNameTime}.gpx")
                            gpxFile?.uri?.let { uri -> contentResolver.openOutputStream(uri)?.use { it.write(gpxBuilder.toString().toByteArray()) } }

                            val logFile = targetDir.createFile("text/plain", "Log_${fileNameTime}.txt")
                            logFile?.uri?.let { uri -> contentResolver.openOutputStream(uri)?.use { it.write(logBuilder.toString().toByteArray()) } }

                            savedToCustom = true
                            Toast.makeText(this, "指定フォルダに保存しました！", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        DebugLogger.log(this, "MainActivity", "Custom Folder Save Failed: ${e.message}")
                    }
                }
            }

            if (!savedToCustom) {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val gpxDir = File(downloadsDir, "GpsTrackerLogs/GPX/$dateDirName").apply { if (!exists()) mkdirs() }
                FileOutputStream(File(gpxDir, "Track_${fileNameTime}.gpx")).use { it.write(gpxBuilder.toString().toByteArray()) }

                val logDir = File(downloadsDir, "GpsTrackerLogs/LOGS/$dateDirName").apply { if (!exists()) mkdirs() }
                FileOutputStream(File(logDir, "Log_${fileNameTime}.txt")).use { it.write(logBuilder.toString().toByteArray()) }

                Toast.makeText(this, "デフォルト(Downloads)に保存しました！", Toast.LENGTH_LONG).show()
            }

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