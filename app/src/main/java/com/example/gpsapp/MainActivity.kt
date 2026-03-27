package com.example.gpsapp

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // ★取得したGPSデータを貯めておくための「リスト」を用意
    private val locationList = mutableListOf<Location>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    // ★GPSデータが届くたびに、リストに追加する
                    locationList.add(location)

                    // 品川周辺なら北緯35.6度、東経139.7度付近の数字が出ます
                    Log.d("GPS_TRACKER", "記録中... 緯度: ${location.latitude}, 経度: ${location.longitude}")
                    Toast.makeText(this@MainActivity, "記録しました: ${locationList.size}件目", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)

        btnStart.setOnClickListener {
            startLocationUpdates()
        }

        btnStop.setOnClickListener {
            stopLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
            return
        }

        // スタート時にリストを空にする（前回の記録をリセット）
        locationList.clear()

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 60000)
            .setMinUpdateIntervalMillis(60000)
            .build()

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        Toast.makeText(this, "トラッキングを開始しました", Toast.LENGTH_SHORT).show()
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)

        // ★ストップを押したら、貯まったリストをファイルに保存する処理を呼び出す
        if (locationList.isNotEmpty()) {
            saveToGpxAndZip()
        } else {
            Toast.makeText(this, "記録されたデータがありません", Toast.LENGTH_SHORT).show()
        }
    }

    // ★GPXを作ってZIPに圧縮し、スマホに保存する関数
    private fun saveToGpxAndZip() {
        try {
            // 1. GPXのテキスト（文字列）を組み立てる
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC") // GPXは世界標準時(UTC)で記録するのがルールです

            val gpxBuilder = java.lang.StringBuilder()
            gpxBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            gpxBuilder.append("<gpx version=\"1.1\" creator=\"MyTracker\">\n")
            gpxBuilder.append("  <trk>\n    <trkseg>\n")

            for (loc in locationList) {
                val timeString = sdf.format(Date(loc.time))
                gpxBuilder.append("      <trkpt lat=\"${loc.latitude}\" lon=\"${loc.longitude}\">\n")
                gpxBuilder.append("        <time>${timeString}</time>\n")
                gpxBuilder.append("      </trkpt>\n")
            }
//commit 前のコメント
            gpxBuilder.append("    </trkseg>\n  </trk>\n</gpx>")

            // 2. 保存するファイルの名前を現在時刻で作る（例: Track_20260326_1412.zip）
            val fileNameTime = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val zipFileName = "Track_${fileNameTime}.zip"
            val gpxFileName = "Track_${fileNameTime}.gpx"

            // 3. Pixel 9aの「ドキュメント」フォルダの中に保存先を指定する
            val directory = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val zipFile = File(directory, zipFileName)

            // 4. ZIPファイルとして書き出す処理
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
