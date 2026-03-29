package com.example.gpsapp

import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// GPXファイルから抽出した情報を保持するデータクラス
data class GpxFileInfo(
    val file: File,
    val dateStr: String,
    val distanceKm: Float,
    val waypointCount: Int
)

class HistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private val gpxList = mutableListOf<GpxFileInfo>()
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        recyclerView = findViewById(R.id.recyclerView)
        tvEmpty = findViewById(R.id.tvEmpty)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = HistoryAdapter(gpxList) { file -> shareFile(file) }
        recyclerView.adapter = adapter

        loadGpxFiles()
    }

    private fun loadGpxFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val gpxBaseDir = File(downloadsDir, "GpsTrackerLogs/GPX")

            val foundFiles = mutableListOf<File>()

            // YYYYMMDD の日付フォルダを全て検索し、中の .gpx ファイルを集める
            if (gpxBaseDir.exists() && gpxBaseDir.isDirectory) {
                gpxBaseDir.listFiles()?.forEach { dateFolder ->
                    if (dateFolder.isDirectory) {
                        dateFolder.listFiles { file -> file.extension == "gpx" }?.let { files ->
                            foundFiles.addAll(files)
                        }
                    }
                }
            }

            // 日付が新しい順にソート
            foundFiles.sortByDescending { it.lastModified() }

            val parsedList = mutableListOf<GpxFileInfo>()
            val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)

            // 各GPXファイルを簡易解析して距離とマーク数を計算
            for (file in foundFiles) {
                var wpCount = 0
                var totalDist = 0.0f
                var lastLoc: Location? = null

                try {
                    file.forEachLine { line ->
                        if (line.contains("<wpt")) wpCount++
                        if (line.contains("<trkpt")) {
                            val latMatch = Regex("lat=\"([^\"]+)\"").find(line)
                            val lonMatch = Regex("lon=\"([^\"]+)\"").find(line)
                            if (latMatch != null && lonMatch != null) {
                                val lat = latMatch.groupValues[1].toDoubleOrNull()
                                val lon = lonMatch.groupValues[1].toDoubleOrNull()
                                if (lat != null && lon != null) {
                                    val loc = Location("").apply { latitude = lat; longitude = lon }
                                    if (lastLoc != null) {
                                        totalDist += lastLoc!!.distanceTo(loc)
                                    }
                                    lastLoc = loc
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                parsedList.add(
                    GpxFileInfo(
                        file = file,
                        dateStr = sdf.format(Date(file.lastModified())),
                        distanceKm = totalDist / 1000.0f,
                        waypointCount = wpCount
                    )
                )
            }

            // 画面の更新はメインスレッドで行う
            withContext(Dispatchers.Main) {
                gpxList.clear()
                gpxList.addAll(parsedList)
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (gpxList.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun shareFile(file: File) {
        try {
            // ★ 修正箇所1: パッケージ名を直接指定して確実にFileProviderを呼び出す
            val uri = FileProvider.getUriForFile(this, "com.example.gpsapp.fileprovider", file)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*" // どんなアプリでも受け取れるようにする
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "GPXファイルを共有"))

        } catch (e: Exception) {
            e.printStackTrace()
            // ★ 修正箇所2: 共有に失敗した場合は画面下部にエラーメッセージを表示して原因を分かりやすくする
            Toast.makeText(this, "共有エラー: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

// RecyclerView 用のアダプター
class HistoryAdapter(
    private val items: List<GpxFileInfo>,
    private val onShareClick: (File) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val tvDetails: TextView = view.findViewById(R.id.tvDetails)
        val btnShare: Button = view.findViewById(R.id.btnShare)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvDate.text = item.dateStr

        val distStr = String.format(Locale.US, "%.2f", item.distanceKm)
        holder.tvDetails.text = "走行距離: $distStr km  |  スポット: ${item.waypointCount}ヶ所"

        holder.btnShare.setOnClickListener {
            onShareClick(item.file)
        }
    }

    override fun getItemCount() = items.size
}