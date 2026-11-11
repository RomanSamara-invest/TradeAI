package ru.tradeai.moex

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.view.View
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

data class Candle(val open: Double, val close: Double, val high: Double, val low: Double,
                  val volume: Double, val time: String)

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var price: TextView
    private lateinit var summary: TextView
    private lateinit var details: TextView
    private lateinit var chart: CandleChart
    private lateinit var tickerInput: EditText
    private lateinit var refreshButton: Button
    private var candles: List<Candle> = emptyList()
    private var busy = false
    private val prefs by lazy { getSharedPreferences("tradeai", MODE_PRIVATE) }

    private val autoRefresh = object : Runnable {
        override fun run() {
            loadData()
            main.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        main.post(autoRefresh)
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(Color.rgb(246, 248, 251))
        }
        val title = TextView(this).apply {
            text = "TRADE AI  |  MOEX ISS"
            textSize = 21f; setTextColor(Color.rgb(25, 43, 65)); gravity = Gravity.CENTER_VERTICAL
        }
        outer.addView(title)
        outer.addView(TextView(this).apply {
            text = "Независимый источник котировок Московской биржи"
            textSize = 12f; setTextColor(Color.DKGRAY)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tickerInput = EditText(this).apply {
            setSingleLine(true); setText(prefs.getString("ticker", "CRZ6"))
            hint = "Тикер MOEX"; textSize = 16f
        }
        row.addView(tickerInput, LinearLayout.LayoutParams(0, 52, 1f))
        refreshButton = Button(this).apply { text = "Обновить"; setOnClickListener { loadData() } }
        row.addView(refreshButton)
        outer.addView(row)
        price = TextView(this).apply {
            text = "Цена: —"; textSize = 25f; setTextColor(Color.rgb(25, 43, 65))
            setPadding(0, 10, 0, 2)
        }
        outer.addView(price)
        status = TextView(this).apply { text = "Подключение к MOEX…"; textSize = 13f; setTextColor(Color.DKGRAY) }
        outer.addView(status)
        chart = CandleChart(this)
        outer.addView(chart, LinearLayout.LayoutParams(-1, 220))
        summary = TextView(this).apply {
            textSize = 17f; setTextColor(Color.rgb(25, 43, 65)); setPadding(0, 12, 0, 6)
        }
        outer.addView(summary)
        details = TextView(this).apply {
            textSize = 14f; setTextColor(Color.DKGRAY); setPadding(0, 4, 0, 12)
        }
        outer.addView(details)
        outer.addView(TextView(this).apply {
            text = "Интервалы: 5 / 15 / 30 / 60 минут • RSI(14), EMA(12/26), MACD • обновление каждые 5 минут"
            textSize = 12f; setTextColor(Color.DKGRAY)
        })
        val disclaimer = TextView(this).apply {
            text = "РЕЖИМ НАБЛЮДЕНИЯ. Бесплатные данные ISS обычно задерживаются примерно на 15 минут. Это учебный анализ, не рекомендация к сделке. Приложение не отправляет заявки."
            textSize = 12f; setTextColor(Color.rgb(145, 75, 30)); setPadding(0, 14, 0, 0)
        }
        outer.addView(disclaimer)
        val scroll = ScrollView(this)
        scroll.addView(outer)
        setContentView(scroll)
    }

    private fun loadData() {
        if (busy) return
        val ticker = tickerInput.text.toString().trim().uppercase(Locale.ROOT)
        if (!ticker.matches(Regex("[A-Z0-9]{2,12}"))) {
            status.text = "Проверь тикер: используй короткий код инструмента MOEX."
            return
        }
        prefs.edit().putString("ticker", ticker).apply()
        busy = true
        refreshButton.isEnabled = false
        status.text = "Запрашиваю данные MOEX ISS для $ticker…"
        worker.execute {
            try {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, -7)
                val from = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
                val endpoint = "https://iss.moex.com/iss/engines/futures/markets/forts/boards/rfud/securities/$ticker/candles.json?from=$from&interval=1&iss.meta=off"
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 12000; readTimeout = 15000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "TradeAI-MOEX-Android/2.0")
                }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    .bufferedReader().use { it.readText() }
                conn.disconnect()
                if (code !in 200..299) throw IllegalStateException("MOEX ответила HTTP $code")
                val parsed = parseCandles(JSONObject(body))
                if (parsed.isEmpty()) throw IllegalStateException("Биржа не вернула свечи для $ticker. Проверь тикер и наличие торгов.")
                main.post { showData(ticker, parsed) }
            } catch (e: Exception) {
                main.post {
                    status.text = "Не удалось получить данные: ${e.message ?: "ошибка сети"}. Проверь интернет и тикер."
                    summary.text = "Данные не обновлены"
                }
            } finally {
                main.post { busy = false; refreshButton.isEnabled = true }
            }
        }
    }

    private fun parseCandles(root: JSONObject): List<Candle> {
        val block = root.optJSONObject("candles") ?: return emptyList()
        val cols = block.optJSONArray("columns") ?: return emptyList()
        val data = block.optJSONArray("data") ?: return emptyList()
        val index = (0 until cols.length()).associate { cols.getString(it) to it }
        fun number(row: org.json.JSONArray, key: String): Double =
            row.optDouble(index[key] ?: -1, 0.0)
        val out = mutableListOf<Candle>()
        for (i in 0 until data.length()) {
            val r = data.optJSONArray(i) ?: continue
            val time = r.optString(index["begin"] ?: -1, "")
            out.add(Candle(number(r, "open"), number(r, "close"), number(r, "high"),
                number(r, "low"), number(r, "volume"), time))
        }
        return out.sortedBy { it.time }
    }

    private fun showData(ticker: String, list: List<Candle>) {
        candles = list
        chart.setCandles(list.takeLast(90))
        val last = list.last()
        price.text = "$ticker   ${fmt(last.close)}"
        val stamp = last.time.ifBlank { "время не указано" }
        status.text = "Получено ${list.size} минутных свечей • последняя свеча: $stamp • ISS"
        val c5 = aggregate(list, 5)
        val c15 = aggregate(list, 15)
        val c30 = aggregate(list, 30)
        val c60 = aggregate(list, 60)
        val rsi = rsi(list.map { it.close }, 14)
        val e12 = ema(list.map { it.close }, 12)
        val e26 = ema(list.map { it.close }, 26)
        val macd = e12.lastOrNull()?.minus(e26.lastOrNull() ?: 0.0) ?: 0.0
        val trend = when {
            macd > 0 && rsi > 55 -> "Положительный импульс"
            macd < 0 && rsi < 45 -> "Отрицательный импульс"
            else -> "Смешанный / нейтральный импульс"
        }
        summary.text = "Состояние индикаторов: $trend"
        details.text = "RSI(14): ${"%.1f".format(Locale.US, rsi)}\n" +
            "EMA(12): ${fmt(e12.lastOrNull() ?: 0.0)}   EMA(26): ${fmt(e26.lastOrNull() ?: 0.0)}\n" +
            "MACD (EMA12−EMA26): ${"%.4f".format(Locale.US, macd)}\n" +
            "Свечей после агрегации: 5м — ${c5.size}; 15м — ${c15.size}; 30м — ${c30.size}; 60м — ${c60.size}\n" +
            "Объём последней минутной свечи: ${last.volume}"
        prefs.edit().putString("last_seen", "$ticker|${last.close}|$stamp|$trend").apply()
    }

    private fun aggregate(src: List<Candle>, minutes: Int): List<Candle> {
        if (src.isEmpty()) return emptyList()
        val groups = src.groupBy {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                val d = sdf.parse(it.time) ?: Date()
                (d.time / (minutes * 60_000L))
            } catch (_: Exception) { 0L }
        }
        return groups.toSortedMap().values.map { g ->
            Candle(g.first().open, g.last().close, g.maxOf { it.high }, g.minOf { it.low },
                g.sumOf { it.volume }, g.first().time)
        }
    }

    private fun ema(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val k = 2.0 / (period + 1)
        val out = mutableListOf(values.first())
        for (v in values.drop(1)) out.add(v * k + out.last() * (1 - k))
        return out
    }

    private fun rsi(values: List<Double>, period: Int): Double {
        if (values.size < period + 1) return 50.0
        val diffs = values.zipWithNext { a, b -> b - a }.takeLast(period)
        val gain = diffs.filter { it > 0 }.sum() / period
        val loss = -diffs.filter { it < 0 }.sum() / period
        if (loss == 0.0) return 100.0
        val rs = gain / loss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.4f", v)

    override fun onDestroy() {
        main.removeCallbacks(autoRefresh)
        worker.shutdownNow()
        super.onDestroy()
    }
}

class CandleChart(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var values: List<Candle> = emptyList()
    fun setCandles(data: List<Candle>) { values = data; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(250, 251, 253))
        if (values.isEmpty()) {
            paint.color = Color.GRAY; paint.textSize = 14f
            canvas.drawText("График появится после загрузки котировок", 12f, height / 2f, paint)
            return
        }
        val minP = values.minOf { it.low }
        val maxP = values.maxOf { it.high }
        val range = max(0.000001, maxP - minP)
        val top = 16f; val bottom = height - 16f
        val step = width.toFloat() / values.size
        val bodyW = max(2f, step * 0.58f)
        values.forEachIndexed { i, c ->
            val x = (i + 0.5f) * step
            fun y(p: Double) = bottom - ((p - minP) / range).toFloat() * (bottom - top)
            val up = c.close >= c.open
            paint.color = if (up) Color.rgb(35, 145, 105) else Color.rgb(205, 75, 75)
            paint.strokeWidth = 1.5f
            canvas.drawLine(x, y(c.high), x, y(c.low), paint)
            val y1 = y(max(c.open, c.close))
            val y2 = y(min(c.open, c.close))
            canvas.drawRect(x - bodyW / 2, y1, x + bodyW / 2, max(y1 + 2f, y2), paint)
        }
    }
}
