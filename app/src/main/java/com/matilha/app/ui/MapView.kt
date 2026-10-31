package com.matilha.app.ui

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.*

data class MapMarker(
    val id: String,
    val lat: Double,
    val lon: Double,
    val label: String,
    val color: Int,
    val badge: String? = null,
    /** Se definido, desenha um círculo com este raio em metros (área aproximada). */
    val areaRadiusM: Double? = null,
    val symbol: String? = null,
)

/**
 * Mapa OpenStreetMap leve (projeção Web Mercator). Sem chave de API.
 * Política de uso de tiles OSM: User-Agent identificável, cache local e atribuição visível.
 */
class OsmMapView(ctx: Context) : View(ctx) {
    var centerLat = -15.7942; var centerLon = -47.8822
    var zoom = 12.5
        private set
    var markers: List<MapMarker> = emptyList()
        set(v) { field = v; invalidate() }
    var myLocation: Pair<Double, Double>? = null
        set(v) { field = v; invalidate() }
    var onMarkerTap: (MapMarker) -> Unit = {}
    var onLongPress: (Double, Double) -> Unit = { _, _ -> }
    var onMoved: () -> Unit = {}
    /** Desliga a rede (testes). */
    var tilesEnabled = true

    private val tileCache = TileCache.get(ctx)
    private val density = resources.displayMetrics.density
    private val paintTile = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paintBg = Paint().apply { color = 0xFFE8E4DA.toInt() }
    private val paintGrid = Paint().apply { color = 0xFFD8D2C4.toInt(); strokeWidth = 1f }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3 * density }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 13 * density; typeface = Typeface.DEFAULT_BOLD }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F1B16.toInt(); textAlign = Paint.Align.CENTER; textSize = 12.5f * density; typeface = Typeface.DEFAULT_BOLD }
    private val labelHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 12.5f * density; typeface = Typeface.DEFAULT_BOLD; style = Paint.Style.STROKE; strokeWidth = 4 * density }
    private val areaFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val areaStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density; pathEffect = DashPathEffect(floatArrayOf(10 * density, 6 * density), 0f) }
    private val attribPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); textSize = 11 * density; textAlign = Paint.Align.RIGHT }
    private val attribBg = Paint().apply { color = 0xCCFFFFFF.toInt() }

    init {
        isFocusable = true
        contentDescription = "Mapa. Use o botão Lista para navegar pelos locais com leitor de tela."
    }

    private val tileListener: () -> Unit = { postInvalidate() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); synchronized(tileCache.listeners) { tileCache.listeners += tileListener } }
    override fun onDetachedFromWindow() { synchronized(tileCache.listeners) { tileCache.listeners -= tileListener }; super.onDetachedFromWindow() }

    fun setCenter(lat: Double, lon: Double, z: Double = zoom) {
        centerLat = lat.coerceIn(-85.0, 85.0); centerLon = lon; zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM); invalidate()
    }

    fun zoomBy(d: Double) { zoom = (zoom + d).coerceIn(MIN_ZOOM, MAX_ZOOM); invalidate(); onMoved() }

    // ---------- Projeção
    private fun worldSize(z: Double) = 256.0 * 2.0.pow(z)
    private fun lonToX(lon: Double, z: Double) = (lon + 180.0) / 360.0 * worldSize(z)
    private fun latToY(lat: Double, z: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * worldSize(z)
    }
    private fun xToLon(x: Double, z: Double) = x / worldSize(z) * 360.0 - 180.0
    private fun yToLat(y: Double, z: Double): Double {
        val n = PI - 2.0 * PI * y / worldSize(z)
        return Math.toDegrees(atan(sinh(n)))
    }

    /** Posição na tela (px) de uma coordenada, no zoom atual. Tiles são 256px escalados pela densidade. */
    fun toScreen(lat: Double, lon: Double): PointF {
        val s = density.toDouble()
        val cx = lonToX(centerLon, zoom); val cy = latToY(centerLat, zoom)
        return PointF((width / 2.0 + (lonToX(lon, zoom) - cx) * s).toFloat(), (height / 2.0 + (latToY(lat, zoom) - cy) * s).toFloat())
    }

    fun fromScreen(x: Float, y: Float): Pair<Double, Double> {
        val s = density.toDouble()
        val wx = lonToX(centerLon, zoom) + (x - width / 2.0) / s
        val wy = latToY(centerLat, zoom) + (y - height / 2.0) / s
        return yToLat(wy, zoom) to xToLon(wx, zoom)
    }

    private fun metersToPx(m: Double): Float {
        val mpp = 156543.03392 * cos(Math.toRadians(centerLat)) / 2.0.pow(zoom)
        return (m / mpp * density).toFloat()
    }

    // ---------- Desenho
    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)
        drawTiles(canvas)
        drawMarkers(canvas)
        drawAttribution(canvas)
    }

    private fun drawTiles(canvas: Canvas) {
        val tz = floor(zoom + 0.25).toInt().coerceIn(MIN_ZOOM.toInt(), TILE_MAX_ZOOM)
        val scale = 2.0.pow(zoom - tz) * density
        val tileSize = 256 * scale
        val cx = lonToX(centerLon, tz.toDouble()); val cy = latToY(centerLat, tz.toDouble())
        val left = cx - width / 2.0 / scale; val top = cy - height / 2.0 / scale
        val n = 1 shl tz
        val x0 = floor(left / 256).toInt(); val x1 = floor((left + width / scale) / 256).toInt()
        val y0 = max(0, floor(top / 256).toInt()); val y1 = min(n - 1, floor((top + height / scale) / 256).toInt())
        val dst = RectF()
        for (ty in y0..y1) for (tx in x0..x1) {
            val wx = ((tx % n) + n) % n
            val sx = ((tx * 256 - left) * scale).toFloat(); val sy = ((ty * 256 - top) * scale).toFloat()
            dst.set(sx, sy, sx + tileSize.toFloat() + 0.5f, sy + tileSize.toFloat() + 0.5f)
            val bmp = if (tilesEnabled) tileCache.tile(tz, wx, ty) else null
            if (bmp != null) { canvas.drawBitmap(bmp, null, dst, paintTile); continue }
            // Fallback: pedaço do tile pai já em memória.
            var drawn = false
            if (tilesEnabled) for (up in 1..3) {
                val pz = tz - up; if (pz < 0) break
                val parent = tileCache.memoryOnly(pz, wx shr up, ty shr up) ?: continue
                val sub = 256 shr up
                val ox = (wx and ((1 shl up) - 1)) * sub; val oy = (ty and ((1 shl up) - 1)) * sub
                canvas.drawBitmap(parent, Rect(ox, oy, ox + sub, oy + sub), dst, paintTile)
                drawn = true; break
            }
            if (!drawn) {
                canvas.drawLine(dst.left, dst.top, dst.right, dst.top, paintGrid)
                canvas.drawLine(dst.left, dst.top, dst.left, dst.bottom, paintGrid)
            }
        }
    }

    private fun drawMarkers(canvas: Canvas) {
        // Áreas aproximadas primeiro (por baixo).
        markers.filter { it.areaRadiusM != null }.forEach { m ->
            val p = toScreen(m.lat, m.lon)
            val r = max(metersToPx(m.areaRadiusM!!), 18 * density)
            areaFill.color = (m.color and 0x00FFFFFF) or 0x33000000
            areaStroke.color = m.color
            canvas.drawCircle(p.x, p.y, r, areaFill)
            canvas.drawCircle(p.x, p.y, r, areaStroke)
            if (zoom >= 12.5) drawLabel(canvas, (m.symbol?.let { "$it " } ?: "") + m.label, p.x, p.y + 5 * density)
        }
        val r = 15 * density
        markers.filter { it.areaRadiusM == null }.forEach { m ->
            val p = toScreen(m.lat, m.lon)
            if (p.x < -60 || p.y < -60 || p.x > width + 60 || p.y > height + 60) return@forEach
            pinPaint.color = m.color
            canvas.drawCircle(p.x, p.y, r, pinPaint)
            canvas.drawCircle(p.x, p.y, r, strokePaint)
            val t = m.badge ?: m.symbol ?: ""
            if (t.isNotEmpty()) canvas.drawText(t, p.x, p.y + textPaint.textSize / 3, textPaint)
            if (zoom >= 12.0) drawLabel(canvas, m.label, p.x, p.y + r + 15 * density)
        }
        myLocation?.let { (la, lo) ->
            val p = toScreen(la, lo)
            pinPaint.color = 0x332F6FDE
            canvas.drawCircle(p.x, p.y, 22 * density, pinPaint)
            pinPaint.color = 0xFF2F6FDE.toInt()
            canvas.drawCircle(p.x, p.y, 8 * density, pinPaint)
            canvas.drawCircle(p.x, p.y, 8 * density, strokePaint)
        }
    }

    private fun drawLabel(canvas: Canvas, s: String, x: Float, y: Float) {
        val txt = if (s.length > 28) s.take(27) + "…" else s
        canvas.drawText(txt, x, y, labelHalo)
        canvas.drawText(txt, x, y, labelPaint)
    }

    private fun drawAttribution(canvas: Canvas) {
        val t = "© colaboradores do OpenStreetMap"
        val w = attribPaint.measureText(t)
        val h = attribPaint.textSize
        canvas.drawRect(width - w - 12 * density, height - h - 8 * density, width.toFloat(), height.toFloat(), attribBg)
        canvas.drawText(t, width - 6 * density, height - 5 * density, attribPaint)
    }

    // ---------- Gestos
    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val (fLat, fLon) = fromScreen(d.focusX, d.focusY)
            zoom = (zoom + log2(d.scaleFactor.toDouble())).coerceIn(MIN_ZOOM, MAX_ZOOM)
            // Mantém o ponto sob os dedos fixo.
            val p = toScreen(fLat, fLon)
            panBy(p.x - d.focusX, p.y - d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean { panBy(dx, dy); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            hit(e.x, e.y)?.let { onMarkerTap(it); performClick() }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { zoomBy(1.0); return true }
        override fun onLongPress(e: MotionEvent) { val (la, lo) = fromScreen(e.x, e.y); onLongPress(la, lo) }
    })

    fun hit(x: Float, y: Float): MapMarker? {
        val tol = 26 * density
        val pins = markers.filter { it.areaRadiusM == null }.minByOrNull { val p = toScreen(it.lat, it.lon); hypot(p.x - x, p.y - y) }
        if (pins != null) { val p = toScreen(pins.lat, pins.lon); if (hypot(p.x - x, p.y - y) <= tol) return pins }
        return markers.filter { it.areaRadiusM != null }.firstOrNull { val p = toScreen(it.lat, it.lon); hypot(p.x - x, p.y - y) <= max(metersToPx(it.areaRadiusM!!), 18 * density) }
    }

    private fun panBy(dx: Float, dy: Float) {
        val (la, lo) = fromScreen(width / 2f + dx, height / 2f + dy)
        centerLat = la.coerceIn(-85.0, 85.0); centerLon = lo
        invalidate(); onMoved()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        const val MIN_ZOOM = 3.0
        const val MAX_ZOOM = 19.0
        const val TILE_MAX_ZOOM = 19
    }
}

/** Cache de tiles: memória (LRU) + disco (7 dias), download em 4 threads. */
class TileCache private constructor(ctx: Context) {
    private val dir = File(ctx.cacheDir, "tiles")
    private val mem = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L * 1024 * 1024)).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<String>()
    private val failedAt = HashMap<String, Long>()
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    val listeners = mutableListOf<() -> Unit>()

    fun memoryOnly(z: Int, x: Int, y: Int): Bitmap? = mem.get("$z/$x/$y")

    fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        mem.get(key)?.let { return it }
        synchronized(pending) {
            if (key in pending) return null
            val f = failedAt[key]; if (f != null && System.currentTimeMillis() - f < 30_000) return null
            pending += key
        }
        pool.execute {
            val bmp = try { load(z, x, y) } catch (_: Throwable) { null }
            synchronized(pending) { pending -= key; if (bmp == null) failedAt[key] = System.currentTimeMillis() }
            if (bmp != null) { mem.put(key, bmp); main.post { synchronized(listeners) { listeners.toList() }.forEach { it() } } }
        }
        return null
    }

    private fun load(z: Int, x: Int, y: Int): Bitmap? {
        val f = File(dir, "$z/$x/$y.png")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7L * 24 * 3600 * 1000) {
            BitmapFactory.decodeFile(f.path)?.let { return it }
        }
        val conn = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Matilha/1.0 (Android; app de socializacao de caes)")
        conn.connectTimeout = 8000; conn.readTimeout = 10000
        return try {
            if (conn.responseCode != 200) return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
            val bytes = conn.inputStream.use { it.readBytes() }
            f.parentFile?.mkdirs(); f.writeBytes(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally { conn.disconnect() }
    }

    companion object {
        @Volatile private var inst: TileCache? = null
        fun get(ctx: Context) = inst ?: synchronized(this) { inst ?: TileCache(ctx.applicationContext).also { inst = it } }
    }
}
