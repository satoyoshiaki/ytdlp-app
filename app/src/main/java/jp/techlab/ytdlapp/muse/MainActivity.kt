package jp.techlab.ytdlapp.muse

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

// ============================================================
// YTDLダウンローダ（yt版・完全版）
// 内蔵 yt-dlp（YouTubeDL-Android）で端末単体解析・ダウンロードする。
// 外部の解析 API は使わない。保存先は Download/YTDL。
// ============================================================

val io = Executors.newCachedThreadPool()
val dlQueue = Executors.newSingleThreadExecutor()

val BRAND_RED = Color.rgb(244, 67, 54) // #F44336（柔らかめの赤）
val BRAND_RED_DARK = Color.rgb(211, 47, 47) // #D32F2F
val LIGHT_BG = Color.rgb(250, 248, 248)
val CHIP_OFF = Color.rgb(251, 233, 231) // #FBE9E7（淡い赤）
val DARK = Color.rgb(36, 33, 33)

const val DL_DIR = "Download/YTDL"

const val SUPPORTED_SITES_URL = "https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md"

const val TERMS_TEXT = """利用規約・注意事項

・本アプリは、オープンソースソフトウェア yt-dlp のフロントエンド（操作画面）であり、汎用のメディア取得ツールです。個人的な利用を目的としています。
・本アプリが想定する利用場面は、ご自身が権利を持つコンテンツの保存、ご自身の投稿のバックアップ、クリエイティブ・コモンズやパブリックドメインの作品の保存など、正当な目的での利用です。
・権利者の許諾なく、著作権等で保護されたコンテンツを取得する行為は禁止します。保護されたコンテンツは、権利者の許諾がある場合、または法令で認められる範囲でのみ取り扱ってください。
・ご利用の際は、利用する各配信サービスの利用規約を必ず遵守してください。本アプリを、各サービスの規約に反する方法で使用しないでください。
・本アプリの利用は、ユーザー自身の責任において行ってください。
・開発者は、本アプリの利用および取得されたコンテンツに関して生じた問題について、一切の責任を負いません。本アプリは無保証で提供されます。"""

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun rounded(color: Int, radiusDp: Int, ctx: Context): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = ctx.dp(radiusDp).toFloat() }
fun Activity.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun fmtSize(b: Long): String = when {
    b < 0 -> ""
    b >= 1024 * 1024 -> String.format(Locale.US, "%.1fMB", b / 1048576.0)
    b >= 1024 -> String.format(Locale.US, "%.0fKB", b / 1024.0)
    else -> "${b}B"
}

fun fmtDur(sec: Double): String {
    if (sec.isNaN() || sec < 0) return ""
    val s = sec.toInt()
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

fun extractUrl(text: String): String? {
    val m = Regex("https?://\\S+").find(text) ?: return null
    return m.value.trimEnd(')', ']', '}', '"', '\'', '。', '、', '>', '<')
}

// ===== 履歴インデックス（先行クローン Muse版と同方式: filesDir/index.json） =====
object IndexStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "index.json")
    fun load(ctx: Context): JSONArray = try {
        if (file(ctx).exists()) JSONArray(file(ctx).readText()) else JSONArray()
    } catch (e: Exception) { JSONArray() }

    fun save(ctx: Context, arr: JSONArray) { file(ctx).writeText(arr.toString()) }
    fun add(ctx: Context, entry: JSONObject) {
        val arr = load(ctx); val next = JSONArray(); next.put(entry)
        for (i in 0 until arr.length()) next.put(arr.getJSONObject(i))
        save(ctx, next)
    }
    fun remove(ctx: Context, uri: String) {
        val arr = load(ctx); val next = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i); if (o.optString("uri") != uri) next.put(o)
        }
        save(ctx, next)
    }
}

fun saveStreamToDownloads(ctx: Context, src: File, displayName: String, mime: String): Pair<Uri, Long> {
    val safeName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, DL_DIR)
    }
    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw RuntimeException("保存先を作成できませんでした")
    var total = 0L
    src.inputStream().use { input ->
        ctx.contentResolver.openOutputStream(uri)!!.use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                out.write(buf, 0, n); total += n
            }
        }
    }
    return uri to total
}

fun mimeOf(ext: String, audio: Boolean): String = when {
    audio || ext == "mp3" -> "audio/mpeg"
    ext == "m4a" -> "audio/mp4"
    ext == "opus" || ext == "ogg" -> "audio/ogg"
    ext == "webm" -> "video/webm"
    ext == "mkv" -> "video/x-matroska"
    else -> "video/mp4"
}

// ===== フォーマット表現（不変データ。選択状態はキーの Set<String> で別管理する） =====
data class Fmt(
    val formatId: String,
    val ext: String,
    val height: Int,
    val fps: Double,
    val vcodec: String,
    val acodec: String,
    val size: Long,
    val tbr: Double,
    val abr: Double
) {
    val hasVideo: Boolean get() = vcodec.isNotEmpty() && vcodec != "none"
    val hasAudio: Boolean get() = acodec != "none"
    val label: String
        get() = when {
            hasVideo -> {
                val base = if (height > 0) "${height}p" else "動画"
                val fpsPart = if (fps >= 1.0) " ${fps.toInt()}fps" else ""
                val sizePart = if (size > 0) "\n${fmtSize(size)}" else ""
                val codecPart = if (hasAudio) "" else "（映像のみ）"
                "$base $ext$fpsPart$codecPart$sizePart"
            }
            hasAudio -> {
                val br = if (abr > 0) " ${abr.toInt()}kbps" else if (tbr > 0) " ${tbr.toInt()}kbps" else ""
                "音声 $ext$br" + if (size > 0) "\n${fmtSize(size)}" else ""
            }
            else -> formatId
        }
}

data class VideoMeta(
    val title: String,
    val uploader: String,
    val id: String,
    val thumb: String?,
    val duration: Double,
    val formats: List<Fmt>
)

data class Preset(
    val key: String,
    val label: String,
    val selector: String,
    val merge: Boolean,
    val audioMp3: Boolean
)

// ===== メイン画面 =====
class MainActivity : Activity() {
    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var infoBox: LinearLayout
    private lateinit var presetLabel: TextView
    private lateinit var fmtLabel: TextView
    private lateinit var presetGrid: GridLayout
    private lateinit var fmtGrid: GridLayout
    private lateinit var dlButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var latestText: TextView

    @Volatile private var ready = false
    private var meta: VideoMeta? = null
    private var srcUrl: String = ""
    private var presets: List<Preset> = emptyList()
    private var formats: List<Fmt> = emptyList()
    // 選択状態はキー文字列の集合で持つ（エンティティを Set に入れない＝過去の教訓）
    private val selected = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(LIGHT_BG)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        // ヘッダー
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "YTDLダウンローダ"; textSize = 21f; setTypeface(null, Typeface.BOLD); setTextColor(DARK)
        })
        header.addView(TextView(this).apply {
            text = " yt版 "; textSize = 12f; setTextColor(Color.WHITE)
            background = rounded(BRAND_RED, 8, this@MainActivity); setPadding(dp(6), dp(2), dp(6), dp(2))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(Button(this).apply {
            text = "履歴"
            setOnClickListener { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) }
        })
        root.addView(header)

        root.addView(TextView(this).apply {
            text = "yt-dlp のフロントエンドアプリ（汎用のメディア取得ツール）"
            textSize = 11.5f; setTextColor(Color.GRAY); setPadding(0, dp(4), 0, 0)
        })

        // URL 入力行
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        urlInput = EditText(this).apply {
            hint = "動画のURLを貼り付け"; textSize = 14f; maxLines = 1
            background = rounded(Color.WHITE, 24, this@MainActivity); setPadding(dp(14), dp(8), dp(10), dp(8))
        }
        row.addView(urlInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "貼り付け"
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()
                if (clip != null) { urlInput.setText(extractUrl(clip) ?: clip); toast("貼り付けました") }
                else toast("クリップボードが空です")
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        row.addView(Button(this).apply {
            text = "解析"; textSize = 14f; setTextColor(Color.WHITE)
            background = rounded(BRAND_RED, 24, this@MainActivity)
            setOnClickListener { analyze() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        root.addView(row)

        // 私のダウンロード（最新）
        root.addView(TextView(this).apply {
            text = "私のダウンロード ＞"; textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(DARK); setPadding(0, dp(14), 0, dp(2))
            setOnClickListener { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) }
        })
        latestText = TextView(this).apply { textSize = 12.5f; setTextColor(Color.GRAY) }
        root.addView(latestText)

        // yt-dlp 更新
        val toolRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        toolRow.addView(Button(this).apply {
            text = "yt-dlpを更新"; textSize = 12f
            background = rounded(Color.WHITE, 16, this@MainActivity)
            setOnClickListener { updateYtdlp() }
        })
        toolRow.addView(Button(this).apply {
            text = "使い方"; textSize = 12f
            background = rounded(Color.WHITE, 16, this@MainActivity)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("使い方")
                    .setMessage("1. 動画アプリで共有→リンクをコピー（または共有先にこのアプリを選ぶ）\n2. URLを貼って「解析」\n3. 画質プリセットか全フォーマットから選ぶ（複数可）\n4. ダウンロードボタンで保存\n保存先: Download/YTDL\n\n※ ログインが必要な投稿は取得できない場合があります")
                    .setPositiveButton("閉じる", null).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        toolRow.addView(Button(this).apply {
            text = "情報"; textSize = 12f
            background = rounded(Color.WHITE, 16, this@MainActivity)
            setOnClickListener { showInfoDialog() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        root.addView(toolRow)

        status = TextView(this).apply { textSize = 13f; setTextColor(DARK); setPadding(0, dp(10), 0, 0) }
        root.addView(status)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(BRAND_RED)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(BRAND_RED)
        }
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        infoBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addView(infoBox)

        presetLabel = TextView(this).apply {
            text = "かんたん選択"; textSize = 13.5f; setTypeface(null, Typeface.BOLD)
            setTextColor(DARK); setPadding(0, dp(14), 0, dp(4)); visibility = View.GONE
        }
        root.addView(presetLabel)
        presetGrid = GridLayout(this).apply { columnCount = 3 }
        root.addView(presetGrid)

        fmtLabel = TextView(this).apply {
            text = "全フォーマット"; textSize = 13.5f; setTypeface(null, Typeface.BOLD)
            setTextColor(DARK); setPadding(0, dp(14), 0, dp(4)); visibility = View.GONE
        }
        root.addView(fmtLabel)
        fmtGrid = GridLayout(this).apply { columnCount = 2 }
        root.addView(fmtGrid)

        dlButton = Button(this).apply {
            text = "ダウンロード（0）"; textSize = 16f; setTextColor(Color.WHITE)
            background = rounded(BRAND_RED, 12, this@MainActivity); visibility = View.GONE
            setOnClickListener { downloadSelected() }
        }
        root.addView(dlButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshLatest()
        initEngine()
        handleIntent(intent)
    }

    private fun showInfoDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(TextView(this).apply {
            text = "本アプリは、オープンソースソフトウェア yt-dlp のフロントエンド（汎用のメディア取得ツール）です。対応範囲は yt-dlp の対応状況に準じます。\nご自身が権利を持つコンテンツ、ご自身の投稿、クリエイティブ・コモンズやパブリックドメインの作品など、正当な目的でご利用ください。"
            textSize = 13.5f; setTextColor(DARK)
        })
        box.addView(TextView(this).apply {
            text = "対応サイトはこちら"; textSize = 14.5f; setTextColor(BRAND_RED)
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, dp(14), 0, 0)
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORTED_SITES_URL)))
            }
        })
        box.addView(Button(this).apply {
            text = "利用規約"; textSize = 13.5f
            background = rounded(CHIP_OFF, 12, this@MainActivity)
            setOnClickListener { showTermsDialog() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        AlertDialog.Builder(this)
            .setTitle("このアプリについて")
            .setView(box)
            .setPositiveButton("閉じる", null).show()
    }

    private fun showTermsDialog() {
        val tv = TextView(this).apply {
            text = TERMS_TEXT; textSize = 13.5f; setTextColor(DARK)
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        AlertDialog.Builder(this)
            .setTitle("利用規約")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("閉じる", null).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() { super.onResume(); refreshLatest() }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val url = extractUrl(shared)
            if (url != null) {
                urlInput.setText(url)
                analyze()
            } else {
                urlInput.setText(shared)
                toast("URLが見つかりませんでした")
            }
        }
    }

    private fun refreshLatest() {
        val arr = IndexStore.load(this)
        latestText.text = if (arr.length() > 0) "最新: " + arr.getJSONObject(0).optString("name")
            else "新しいダウンロードがここに表示されます"
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    // ===== yt-dlp エンジン =====
    private fun initEngine() {
        setStatus("yt-dlp 初期化中…")
        io.execute {
            try {
                YoutubeDL.getInstance().init(this)
                // ffmpeg の初期化（MP3 抽出・HLS ダウンロード・映像+音声マージに必須。
                // これを呼ばないと yt-dlp に ffmpeg の場所が渡らず後処理が失敗する）
                try { FFmpeg.getInstance().init(this) } catch (e: Exception) { /* 映像DL自体は継続 */ }
                val v = try { YoutubeDL.getInstance().version(this) } catch (e: Exception) { null }
                ready = true
                setStatus(if (v.isNullOrEmpty()) "準備完了。URLを貼ってください" else "準備完了（yt-dlp $v）。URLを貼ってください")
            } catch (e: Exception) {
                setStatus("yt-dlp の初期化に失敗しました: ${e.message}")
            }
        }
    }

    private fun updateYtdlp() {
        if (!ready) { toast("まだ初期化中です"); return }
        setStatus("yt-dlp を更新中…（数十秒かかることがあります）")
        io.execute {
            try {
                val st = YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel.STABLE)
                val v = try { YoutubeDL.getInstance().version(this) } catch (e: Exception) { null }
                setStatus(if (v.isNullOrEmpty()) "更新結果: $st" else "更新結果: $st（yt-dlp $v）")
            } catch (e: Exception) {
                setStatus("更新に失敗しました: ${e.message}")
            }
        }
    }

    // ===== 解析 =====
    private fun analyze() {
        val raw = urlInput.text.toString().trim()
        val url = extractUrl(raw) ?: raw
        if (!url.startsWith("http")) { toast("URLを入力してください"); return }
        if (!ready) {
            // コールドスタート直後の共有インテント等: 初期化完了を待ってから自動で解析する
            setStatus("yt-dlp 初期化中…（完了したら自動で解析します）")
            io.execute {
                var waited = 0
                while (!ready && waited < 90) { Thread.sleep(1_000); waited++ }
                if (ready) doAnalyze(url)
                else setStatus("初期化が終わりませんでした。もう一度「解析」を押してください")
            }
            return
        }
        doAnalyze(url)
    }

    private fun doAnalyze(url: String) {
        srcUrl = url
        setStatus("解析中…（内蔵 yt-dlp で取得しています）")
        io.execute {
            try {
                val req = YoutubeDLRequest(url)
                req.addOption("--no-playlist")
                req.addOption("--no-warnings")
                req.addOption("-J")
                val resp = YoutubeDL.getInstance().execute(req)
                val out = resp.out?.trim().orEmpty()
                if (out.isEmpty()) throw RuntimeException("解析結果が空でした")
                val j = JSONObject(out)
                if (j.has("entries")) throw RuntimeException("再生リストではなく動画単体のURLを入れてください")
                val m = parseMeta(j)
                meta = m; formats = m.formats
                presets = buildPresets(m)
                runOnUiThread {
                    selected.clear()
                    // 最高画質を初期選択
                    presets.firstOrNull()?.let { selected.add(it.key) }
                    showInfo(m)
                    renderSelection()
                }
                setStatus("解析できました: ${m.formats.size} フォーマット")
            } catch (e: Exception) {
                setStatus("解析に失敗しました: ${shortErr(e)}")
            }
        }
    }

    private fun shortErr(e: Exception): String {
        // yt-dlp のエラーは末尾（ERROR: 行）が本題で、先頭は警告文のことが多いため末尾を残す
        val msg = (e.message ?: "不明なエラー").replace(Regex("\\s+"), " ").trim()
        return if (msg.length > 220) "…" + msg.substring(msg.length - 220) else msg
    }

    private fun parseMeta(j: JSONObject): VideoMeta {
        val arr = j.optJSONArray("formats") ?: JSONArray()
        val fmts = mutableListOf<Fmt>()
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            val ext = f.optString("ext", "")
            if (ext == "mhtml") continue // ストーリーボード除外
            // コーデック欄が null（extractor が申告しない）の format がある（X の
            // http 形式は vcodec/acodec とも null で実体は音声入り単体ファイル、
            // X の HLS 音声は acodec が null）。「none 明示」と区別して解釈する:
            //   - vcodec="none" 明示 → 映像なし（音声のみ）
            //   - acodec="none" 明示 → 音声なし（映像のみ）
            //   - null は「不明」。両方不明なら統合ファイルとして扱う
            val vc = if (f.isNull("vcodec")) "" else f.optString("vcodec", "")
            val ac = if (f.isNull("acodec")) "" else f.optString("acodec", "")
            val isVideo = vc != "none" && !(vc.isEmpty() && ac.isNotEmpty() && ac != "none")
            val hasAud = ac != "none"
            if (!isVideo && !hasAud) continue
            val effVc = if (isVideo) vc.ifEmpty { "unknown" } else "none"
            val effAc = if (hasAud) ac.ifEmpty { "unknown" } else "none"
            val size = when {
                !f.isNull("filesize") && f.optLong("filesize") > 0 -> f.optLong("filesize")
                !f.isNull("filesize_approx") && f.optLong("filesize_approx") > 0 -> f.optLong("filesize_approx")
                else -> -1L
            }
            fmts.add(
                Fmt(
                    formatId = f.optString("format_id", ""),
                    ext = ext,
                    height = if (f.isNull("height")) 0 else f.optInt("height"),
                    fps = if (f.isNull("fps")) 0.0 else f.optDouble("fps"),
                    vcodec = effVc, acodec = effAc,
                    size = size,
                    tbr = if (f.isNull("tbr")) 0.0 else f.optDouble("tbr"),
                    abr = if (f.isNull("abr")) 0.0 else f.optDouble("abr")
                )
            )
        }
        val videos = fmts.filter { it.hasVideo }.sortedWith(compareByDescending<Fmt> { it.height }.thenByDescending { it.tbr })
        val audios = fmts.filter { !it.hasVideo && it.hasAudio }.sortedByDescending { it.abr }
        return VideoMeta(
            title = j.optString("title", "(タイトル不明)"),
            uploader = j.optString("uploader", j.optString("uploader_id", "")),
            id = j.optString("id", ""),
            thumb = j.optString("thumbnail", "").ifEmpty { null },
            duration = if (j.isNull("duration")) -1.0 else j.optDouble("duration"),
            formats = videos + audios
        )
    }

    private fun buildPresets(m: VideoMeta): List<Preset> {
        val list = mutableListOf<Preset>()
        list.add(Preset("preset:best", "最高画質", "bv*+ba/b", merge = true, audioMp3 = false))
        val heights = m.formats.filter { it.hasVideo && it.height > 0 }.map { it.height }.distinct().sortedDescending()
        for (h in heights) {
            // 先頭セレクタ best[height<=H] は Termux版の検証コマンドと同一（単体ファイル優先）
            list.add(
                Preset(
                    "preset:h$h", "${h}p",
                    "best[height<=$h]/bv*[height<=$h]+ba/b[height<=$h]/b",
                    merge = true, audioMp3 = false
                )
            )
        }
        list.add(Preset("preset:audio", "音声のみ（MP3）", "ba/b", merge = false, audioMp3 = true))
        return list
    }

    private fun showInfo(m: VideoMeta) {
        infoBox.removeAllViews(); infoBox.visibility = View.VISIBLE
        m.thumb?.let { thumbUrl ->
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190))
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            infoBox.addView(iv)
            io.execute {
                val bmp = try {
                    val c = URL(thumbUrl).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 20000
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) YtdlApp")
                    BitmapFactory.decodeStream(c.inputStream)
                } catch (e: Exception) { null }
                if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) }
            }
        }
        infoBox.addView(TextView(this).apply {
            text = m.title; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(DARK)
            setPadding(0, dp(8), 0, 0)
        })
        val sub = listOf(m.uploader, fmtDur(m.duration)).filter { it.isNotEmpty() }.joinToString(" ・ ")
        if (sub.isNotEmpty()) infoBox.addView(TextView(this).apply {
            text = sub; textSize = 12.5f; setTextColor(Color.GRAY)
        })
    }

    private fun renderSelection() {
        presetLabel.visibility = if (presets.isEmpty()) View.GONE else View.VISIBLE
        fmtLabel.visibility = if (formats.isEmpty()) View.GONE else View.VISIBLE
        // プリセット
        presetGrid.removeAllViews()
        presets.forEach { p ->
            val isSel = selected.contains(p.key)
            val chip = TextView(this).apply {
                text = (if (isSel) "✓ " else "") + p.label
                textSize = 13.5f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                setTextColor(if (isSel) Color.WHITE else DARK)
                background = rounded(if (isSel) BRAND_RED else CHIP_OFF, 12, this@MainActivity)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener {
                    if (selected.contains(p.key)) selected.remove(p.key) else selected.add(p.key)
                    renderSelection()
                }
            }
            presetGrid.addView(chip, GridLayout.LayoutParams().apply {
                width = 0; height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        // 全フォーマット
        fmtGrid.removeAllViews()
        formats.forEach { f ->
            val key = "fmt:${f.formatId}"
            val isSel = selected.contains(key)
            val card = TextView(this).apply {
                text = (if (isSel) "✓ " else "") + f.label
                textSize = 12.5f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                setTextColor(if (isSel) Color.WHITE else DARK)
                background = rounded(if (isSel) BRAND_RED else CHIP_OFF, 12, this@MainActivity)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener {
                    if (selected.contains(key)) selected.remove(key) else selected.add(key)
                    renderSelection()
                }
            }
            fmtGrid.addView(card, GridLayout.LayoutParams().apply {
                width = 0; height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        dlButton.visibility = if (presets.isEmpty() && formats.isEmpty()) View.GONE else View.VISIBLE
        dlButton.text = "ダウンロード（${selected.size}）"
    }

    // ===== ダウンロード =====
    private data class Job(val label: String, val selector: String, val merge: Boolean, val audioMp3: Boolean)

    private fun downloadSelected() {
        val m = meta ?: return
        if (selected.isEmpty()) { toast("画質かフォーマットを選んでください"); return }
        val jobs = mutableListOf<Job>()
        for (key in selected) {
            when {
                key.startsWith("preset:") -> presets.firstOrNull { it.key == key }
                    ?.let { jobs.add(Job(it.label, it.selector, it.merge, it.audioMp3)) }
                key.startsWith("fmt:") -> {
                    val f = formats.firstOrNull { "fmt:${it.formatId}" == key } ?: continue
                    val sel = if (f.hasVideo && !f.hasAudio) "${f.formatId}+ba" else f.formatId
                    jobs.add(Job(f.label.replace("\n", " "), sel, merge = f.hasVideo && !f.hasAudio, audioMp3 = false))
                }
            }
        }
        if (jobs.isEmpty()) { toast("対象がありません"); return }
        toast("${jobs.size}件のダウンロードを開始します")
        dlQueue.execute {
            var done = 0; var fail = 0
            for (job in jobs) {
                try {
                    runDownload(job, m)
                    done++
                } catch (e: Exception) {
                    fail++
                    setStatus("失敗（${job.label}）: ${shortErr(e)}")
                }
                runOnUiThread { refreshLatest() }
            }
            runOnUiThread {
                toast(if (fail == 0) "ダウンロード完了（${done}件）" else "完了 ${done}件 / 失敗 ${fail}件")
                if (fail == 0) status.text = "保存先: $DL_DIR"
                progressBar.visibility = View.GONE
                refreshLatest()
            }
        }
    }

    private fun runDownload(job: Job, m: VideoMeta) {
        val jobId = System.currentTimeMillis().toString() + "_" + (0..9999).random()
        val jobDir = File(filesDir, "work/$jobId").apply { mkdirs() }
        try {
            // Termux版と揃えたファイル名テンプレート（音声は height が無いため mp3 表記）
            val tmplName = if (job.audioMp3) "%(uploader_id)s_mp3_%(id)s.%(ext)s"
                else "%(uploader_id)s_%(height)sp_%(id)s.%(ext)s"
            val req = YoutubeDLRequest(srcUrl)
            req.addOption("--no-playlist")
            req.addOption("-f", job.selector)
            req.addOption("-o", File(jobDir, tmplName).absolutePath)
            if (job.merge) req.addOption("--merge-output-format", "mp4")
            if (job.audioMp3) {
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
                req.addOption("--audio-quality", "0")
            }
            req.addOption("--no-mtime")
            setStatus("ダウンロード中: ${job.label}")
            runOnUiThread { progressBar.visibility = View.VISIBLE; progressBar.progress = 0 }
            YoutubeDL.getInstance().execute(req, jobId) { progress, etaInSeconds, _ ->
                runOnUiThread {
                    progressBar.progress = progress.toInt()
                    status.text = "${job.label}: ${progress.toInt()}%（残り約 ${etaInSeconds} 秒）"
                }
            }
            val out = jobDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.contains(".part.") }
                ?.maxByOrNull { it.length() }
                ?: throw RuntimeException("出力ファイルが見つかりませんでした")
            val ext = out.extension.lowercase(Locale.US)
            val kind = if (job.audioMp3 || ext == "mp3" || ext == "m4a" || ext == "opus") "audio" else "video"
            val (uri, size) = saveStreamToDownloads(this, out, out.name, mimeOf(ext, kind == "audio"))
            IndexStore.add(this, JSONObject().apply {
                put("uri", uri.toString()); put("name", out.name); put("author", m.uploader)
                put("kind", kind); put("size", size); put("ts", System.currentTimeMillis())
                put("src", srcUrl)
            })
            setStatus("保存しました: ${out.name}（${fmtSize(size)}）")
        } finally {
            jobDir.deleteRecursively()
        }
    }
}

// ===== 履歴画面 =====
class HistoryActivity : Activity() {
    private lateinit var list: ListView
    private var entries = mutableListOf<JSONObject>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(LIGHT_BG)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "私のダウンロード"; textSize = 19f; setTypeface(null, Typeface.BOLD); setTextColor(DARK)
        })
        root.addView(TextView(this).apply {
            text = "保存先: $DL_DIR（長押しで操作メニュー）"; textSize = 12f; setTextColor(Color.GRAY)
        })
        list = ListView(this)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        reload()
        list.setOnItemClickListener { _, _, pos, _ ->
            if (entries.isEmpty()) return@setOnItemClickListener
            val e = entries[pos]
            val kind = e.optString("kind")
            val mime = when (kind) {
                "audio" -> "audio/*"
                "image" -> "image/*"
                else -> "video/*"
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(e.optString("uri")), mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (ex: Exception) {
                toast("開けるアプリがありません")
            }
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            if (entries.isEmpty()) return@setOnItemLongClickListener false
            val e = entries[pos]
            AlertDialog.Builder(this)
                .setTitle(e.optString("name"))
                .setItems(arrayOf("開く", "履歴だけ削除（ファイルは残す）", "消去（ファイルごと削除）")) { _, which ->
                    when (which) {
                        0 -> list.performItemClick(null, pos, 0)
                        1 -> { IndexStore.remove(this, e.optString("uri")); toast("履歴だけ削除しました"); reload() }
                        2 -> {
                            try { contentResolver.delete(Uri.parse(e.optString("uri")), null, null) } catch (ex: Exception) { }
                            IndexStore.remove(this, e.optString("uri"))
                            toast("消去しました（容量も空きました）"); reload()
                        }
                    }
                }.show()
            true
        }
    }

    private fun reload() {
        val arr = IndexStore.load(this)
        entries = mutableListOf()
        for (i in 0 until arr.length()) entries.add(arr.getJSONObject(i))
        if (entries.isEmpty()) {
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf("新しいダウンロードがここに表示されます"))
            return
        }
        list.adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(p: Int) = entries[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val row = LinearLayout(this@HistoryActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(6), dp(6), dp(6))
                    setBackgroundColor(Color.WHITE)
                }
                val iv = ImageView(this@HistoryActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(84), dp(84))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.LTGRAY)
                }
                val e = entries[p]
                io.execute {
                    val bmp = thumbOf(e)
                    if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) }
                }
                row.addView(iv)
                val texts = LinearLayout(this@HistoryActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
                }
                texts.addView(TextView(this@HistoryActivity).apply {
                    text = e.optString("name"); textSize = 13f; setTextColor(DARK); maxLines = 2
                })
                val date = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(e.optLong("ts")))
                texts.addView(TextView(this@HistoryActivity).apply {
                    text = "${e.optString("author")} ・ $date ・ ${fmtSize(e.optLong("size"))}"
                    textSize = 11.5f; setTextColor(Color.GRAY)
                })
                row.addView(texts)
                return row
            }
        }
    }

    private fun thumbOf(e: JSONObject): Bitmap? {
        val uri = Uri.parse(e.optString("uri"))
        return try {
            if (e.optString("kind") == "video") {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(this, uri)
                val bmp = mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: mmr.getFrameAtTime(0)
                mmr.release(); bmp
            } else null
        } catch (ex: Exception) { null }
    }
}
