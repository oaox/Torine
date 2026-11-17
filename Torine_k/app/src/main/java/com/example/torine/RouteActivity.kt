package no.fibula.torine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class RouteActivity : AppCompatActivity() {

    private val notificationsUrl = "https://oao.no/torine/notifications.json"
    private val routeImageUrl    = "https://oao.no/torine/rute.png"

    private val notifPrefs by lazy {
        getSharedPreferences("torine_k_notifications", Context.MODE_PRIVATE)
    }
    private val KEY_LAST_NOTIF_ID = "last_notification_id"
    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var routeView: RouteView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        routeView = RouteView(this)
        WindowCompat.enableEdgeToEdge(window)
        setContentView(routeView)

        val gesture = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    routeView.persistState()
                    finish()
                    return true
                }
                override fun onDown(e: MotionEvent): Boolean = true
            }
        )
        routeView.setOnTouchListener { _, event ->
            gesture.onTouchEvent(event)
            false
        }

        // Feed system-bar / cutout insets into RouteView. Called on every
        // configuration change (including rotation) so the usable area
        // tracks the current orientation.
        ViewCompat.setOnApplyWindowInsetsListener(routeView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
            )
            (v as RouteView).setSystemInsets(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            checkNotifications()
        }
    }

    override fun onPause() {
        super.onPause()
        routeView.persistState()
    }

    // ---- notifications (unchanged behaviour) ----

    private fun checkNotifications() {
        scope.launch {
            val body = withContext(Dispatchers.IO) { httpGet(notificationsUrl) }
            if (body.isNullOrBlank()) return@launch

            val id: Long
            val text: String
            try {
                val obj = JSONObject(body)
                id = obj.optLong("id", -1L)
                text = obj.optString("text", "").trim()
            } catch (e: Exception) {
                Log.e("Torine_k", "Notifications: parse failed. Body=${body.take(200)}", e)
                return@launch
            }
            if (id < 0L || text.isEmpty()) return@launch

            val lastSeen = notifPrefs.getLong(KEY_LAST_NOTIF_ID, -1L)
            if (id == lastSeen) return@launch

            if (isFinishing || isDestroyed) return@launch
            showNotificationDialog(id, text)
        }
    }

    private fun showNotificationDialog(id: Long, text: String) {
        AlertDialog.Builder(this)
            .setTitle("Torine")
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton("OPPDATER") { dialog, _ ->
                dialog.dismiss()
                downloadRouteAndUpdate(id)
            }
            .setNegativeButton("IKKE NÅ") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun downloadRouteAndUpdate(notificationId: Long) {
        routeView.showBusyOverlay(true)
        scope.launch {
            val result = withContext(Dispatchers.IO) { downloadRouteImage() }
            routeView.showBusyOverlay(false)

            if (result == null) {
                AlertDialog.Builder(this@RouteActivity)
                    .setTitle("Torine")
                    .setMessage("Nedlasting feilet. Start appen på nytt og prøv igjen.")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }

            notifPrefs.edit().putLong(KEY_LAST_NOTIF_ID, notificationId).apply()
            routeView.reloadImage()
        }
    }

    private fun downloadRouteImage(): File? {
        val dest = File(filesDir, "rute.png")
        val tmp  = File(filesDir, "rute.png.tmp")
        return try {
            val conn = (URL(routeImageUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.e("Torine_k", "Route download HTTP $code")
                conn.disconnect()
                return null
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.e("Torine_k", "Route download: not a valid image")
                tmp.delete()
                return null
            }

            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                Log.e("Torine_k", "Route download: rename failed")
                tmp.delete()
                return null
            }
            dest
        } catch (e: Exception) {
            Log.e("Torine_k", "Route download failed", e)
            tmp.delete()
            null
        }
    }

    private fun httpGet(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect(); body
    } catch (e: Exception) {
        Log.e("Torine_k", "HTTP error", e)
        null
    }
}

/**
 * Zoomable, pannable view for the route image.
 *
 * Image sources, in priority order:
 *   1. context.filesDir/rute.png  — downloaded copy
 *   2. R.drawable.rute            — bundled placeholder
 *
 * In landscape the bitmap is pre-rotated 90° clockwise so its long axis
 * matches the screen's long axis. All fit / clamp / draw logic uses the
 * current (rotated) bitmap together with inset-reduced usable bounds —
 * so the image never hides behind the system bars or cutout.
 *
 * When the usable area flips between portrait and landscape, zoom is kept
 * and the image point that was at the usable-area centre is mapped through
 * the 90° rotation so it stays at the new centre.
 */
class RouteView(context: Context) : View(context) {

    private var bitmapPortrait: Bitmap = loadBestRouteBitmap(context)
    private var bitmapLandscape: Bitmap? = null

    private val matrix = Matrix()
    private var scale = 1f
    private var transX = 0f
    private var transY = 0f

    private var lastTouch = PointF()
    private var isDragging = false
    private var busy = false

    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0

    private val prefs = context.getSharedPreferences("torine_k", Context.MODE_PRIVATE)

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                scale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
                clearSavedFocus()
                clampTranslation()
                invalidate()
                return true
            }
        }
    )

    init {
        scale = prefs.getFloat(KEY_SCALE, 1f).coerceIn(MIN_SCALE, MAX_SCALE)
        transX = prefs.getFloat(KEY_TX, 0f)
        transY = prefs.getFloat(KEY_TY, 0f)
        isClickable = true
    }

    // -------- insets --------

    fun setSystemInsets(l: Int, t: Int, r: Int, b: Int) {
        if (l == insetLeft && t == insetTop && r == insetRight && b == insetBottom) return
        insetLeft = l; insetTop = t; insetRight = r; insetBottom = b
        // Insets often arrive after onSizeChanged on a turn; re-apply the
        // saved centre point now that the usable area is final.
        if (!applySavedFocus()) clampTranslation()
        invalidate()
    }

    private fun usableWidth(): Int  = (width  - insetLeft - insetRight).coerceAtLeast(1)
    private fun usableHeight(): Int = (height - insetTop  - insetBottom).coerceAtLeast(1)
    private fun isLandscape(): Boolean = width > height

    // -------- orientation-aware bitmap --------

    /** The bitmap appropriate for the current orientation, with landscape pre-rotated. */
    private fun currentBitmap(): Bitmap = bitmapFor(isLandscape())

    private fun bitmapFor(landscape: Boolean): Bitmap {
        if (!landscape) return bitmapPortrait
        bitmapLandscape?.let { return it }
        val m = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(
            bitmapPortrait, 0, 0,
            bitmapPortrait.width, bitmapPortrait.height,
            m, true
        )
        bitmapLandscape = rotated
        return rotated
    }

    // Portrait-image point kept at the usable-area centre across a turn.
    private var focusPortrait: PointF? = null

    private fun clearSavedFocus() {
        focusPortrait = null
    }

    /**
     * Image point (in unrotated portrait-bitmap coordinates) currently at
     * the centre of the usable area, for the given view size and insets.
     *
     * Landscape is a 90° clockwise rotation of the portrait bitmap:
     * (lx, ly) = (portraitHeight - py, px), so (px, py) = (ly, portraitHeight - lx).
     */
    private fun portraitPointAtUsableCenter(
        viewW: Int, viewH: Int,
        iL: Int, iT: Int, iR: Int, iB: Int
    ): PointF {
        val landscape = viewW > viewH
        val bmp = bitmapFor(landscape)
        val uw = (viewW - iL - iR).coerceAtLeast(1).toFloat()
        val uh = (viewH - iT - iB).coerceAtLeast(1).toFloat()
        val s = minOf(uw / bmp.width, uh / bmp.height) * scale
        val bx = (uw / 2f - transX) / s
        val by = (uh / 2f - transY) / s
        return if (landscape) {
            PointF(by, bitmapPortrait.height - bx)
        } else {
            PointF(bx, by)
        }
    }

    /** Place [focusPortrait] at the current usable-area centre, then clamp. */
    private fun applySavedFocus(): Boolean {
        val focus = focusPortrait ?: return false
        val bmpX: Float
        val bmpY: Float
        if (isLandscape()) {
            bmpX = bitmapPortrait.height - focus.y
            bmpY = focus.x
        } else {
            bmpX = focus.x
            bmpY = focus.y
        }
        val s = totalScale()
        transX = usableWidth()  / 2f - bmpX * s
        transY = usableHeight() / 2f - bmpY * s
        clampTranslation()
        return true
    }

    fun reloadImage() {
        bitmapPortrait = loadBestRouteBitmap(context)
        bitmapLandscape?.recycle()
        bitmapLandscape = null
        clampTranslation()
        invalidate()
    }

    fun showBusyOverlay(show: Boolean) {
        busy = show
        alpha = if (show) 0.5f else 1f
        invalidate()
    }

    // -------- geometry --------

    private fun baseScale(): Float {
        val bmp = currentBitmap()
        return minOf(
            usableWidth().toFloat()  / bmp.width,
            usableHeight().toFloat() / bmp.height
        )
    }

    private fun totalScale(): Float = baseScale() * scale

    private fun clampTranslation() {
        if (width == 0 || height == 0) return
        val bmp = currentBitmap()
        val s = totalScale()
        val scaledW = bmp.width * s
        val scaledH = bmp.height * s
        val uw = usableWidth()
        val uh = usableHeight()

        transX = if (scaledW > uw) transX.coerceIn(uw - scaledW, 0f)
        else (uw - scaledW) / 2f
        transY = if (scaledH > uh) transY.coerceIn(uh - scaledH, 0f)
        else (uh - scaledH) / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val hadPreviousSize = oldw > 0 && oldh > 0
        if (hadPreviousSize) {
            val wasLandscape = oldw > oldh
            val nowLandscape = w > h
            if (wasLandscape != nowLandscape) {
                // Use the old size (and current insets) so the mapping is
                // based on what was actually on screen before the flip.
                focusPortrait = portraitPointAtUsableCenter(
                    oldw, oldh, insetLeft, insetTop, insetRight, insetBottom
                )
                if (!nowLandscape) {
                    bitmapLandscape?.recycle()
                    bitmapLandscape = null
                }
                applySavedFocus()
                return
            }
        }
        clampTranslation()
    }


        // -------- touch --------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (busy) return true
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch.set(event.x, event.y)
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !scaleDetector.isInProgress) {
                    val dx = event.x - lastTouch.x
                    val dy = event.y - lastTouch.y
                    transX += dx
                    transY += dy
                    lastTouch.set(event.x, event.y)
                    clearSavedFocus()
                    clampTranslation()
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> { isDragging = false }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = if (event.actionIndex == 0) 1 else 0
                lastTouch.set(event.getX(idx), event.getY(idx))
                isDragging = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // -------- draw --------

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val bmp = currentBitmap()
        matrix.reset()
        val s = totalScale()
        matrix.postScale(s, s)
        matrix.postTranslate(transX + insetLeft, transY + insetTop)
        canvas.drawBitmap(bmp, matrix, null)
    }

    // -------- persistence --------

    fun persistState() {
        prefs.edit()
            .putFloat(KEY_SCALE, scale)
            .putFloat(KEY_TX, transX)
            .putFloat(KEY_TY, transY)
            .apply()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        persistState()
    }

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 10f
        private const val KEY_SCALE = "route_scale"
        private const val KEY_TX = "route_tx"
        private const val KEY_TY = "route_ty"

        private fun loadBestRouteBitmap(context: Context): Bitmap {
            val local = File(context.filesDir, "rute.png")
            if (local.exists()) {
                val bmp = try { BitmapFactory.decodeFile(local.absolutePath) } catch (e: Exception) { null }
                if (bmp != null) return bmp
                Log.e("Torine_k", "Cached rute.png is corrupt; deleting")
                local.delete()
            }
            return BitmapFactory.decodeResource(context.resources, R.drawable.rute)
        }
    }
}