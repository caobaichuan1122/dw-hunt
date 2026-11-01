package com.local.dwbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.view.Display
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A screenshot request that the system refused; [code] is one of AccessibilityService.ERROR_TAKE_SCREENSHOT_*. */
class ShotException(val code: Int) : RuntimeException("screenshot failed, code $code")

/**
 * Screen, taps and template matching. Templates and coordinates come from a 2000x2800 tablet, the game UI
 * scales with screen height, so everything is multiplied by s = height / 2800.
 *
 * Screenshots and taps go through the accessibility service, so nothing here needs root or a PC.
 * Every [Mat] returned by [screenshot] must be released by the caller.
 */
class Device(private val service: AccessibilityService) {
    private val executor = Executors.newSingleThreadExecutor()

    // template cache: full size and half size, rescaled to this screen the first time a screenshot reveals its size
    private val tpls = HashMap<String, Mat>()
    private val halfTpls = HashMap<String, Mat>()

    // screen width / height in pixels and the scale factor s = h / 2800 (set by the first screenshot)
    var w = 0
        private set
    var h = 0
        private set
    var s = 1.0
        private set

    // ---- screen ----

    /** Takes a BGR screenshot, retrying when the system throttles us or the callback never arrives. */
    suspend fun screenshot(): Mat {
        repeat(10) {
            try {
                // takeScreenshot has been seen never to call back, which hung the bot for hours
                return withTimeout(10_000) { capture() }
            } catch (e: TimeoutCancellationException) {
                delay(500)
            } catch (e: ShotException) {
                if (e.code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) delay(350) else throw e
            }
        }
        throw RuntimeException("screenshot kept failing")
    }

    /** One accessibility screenshot, converted from the hardware buffer to an OpenCV BGR matrix. */
    private suspend fun capture(): Mat = suspendCancellableCoroutine { cont ->
        service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                try {
                    val hb = r.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)!!
                    val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                    hw.recycle()
                    hb.close()
                    val mat = Mat()
                    Utils.bitmapToMat(soft, mat)
                    soft.recycle()
                    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
                    // first frame, or the screen size changed (rotation): rescale from scratch
                    if (w != mat.cols() || h != mat.rows()) {
                        w = mat.cols()
                        h = mat.rows()
                        s = h / 2800.0
                        tpls.values.forEach { it.release() }
                        tpls.clear()
                        halfTpls.values.forEach { it.release() }
                        halfTpls.clear()
                    }
                    if (cont.isActive) cont.resume(mat)
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }

            override fun onFailure(errorCode: Int) {
                if (cont.isActive) cont.resumeWithException(ShotException(errorCode))
            }
        })
    }

    /** App-private folder, used for debug screenshots written when something unexpected happens. */
    fun filesDir() = service.filesDir

    // ---- input ----

    /** Taps [p] (screen pixels); [holdMs] is how long the finger stays down. */
    suspend fun tap(p: Point, holdMs: Long = 40) = tap(p.x.toInt(), p.y.toInt(), holdMs)

    suspend fun tap(x: Int, y: Int, holdMs: Long = 40) {
        // a gesture that is never reported back must not hang the bot
        withTimeoutOrNull(3_000) { tapOnce(x, y, holdMs) }
    }

    /** Dispatches one tap gesture and resumes when the system reports it finished or cancelled. */
    private suspend fun tapOnce(x: Int, y: Int, holdMs: Long) = suspendCancellableCoroutine<Unit> { cont ->
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, holdMs))
            .build()
        val queued = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onCancelled(g: GestureDescription?) {
                if (cont.isActive) cont.resume(Unit)
            }
        }, null)
        if (!queued && cont.isActive) cont.resume(Unit)
    }

    // ---- geometry (reference tablet -> this screen) ----

    /**
     * Converts a point of the 2000x2800 reference tablet to this screen. [ax] is the horizontal anchor (L/C/R: the
     * offset is measured from the left edge, the centre or the right edge) and [ay] the vertical one (T/C/B). Anchoring
     * keeps buttons glued to the right edge, the bottom bar and so on when the aspect ratio differs.
     */
    fun pos(x: Int, y: Int, ax: Char = 'L', ay: Char = 'T'): Point {
        val px = when (ax) {
            'C' -> w / 2.0 + s * (x - 1000)
            'R' -> w - s * (2000 - x)
            else -> s * x
        }
        val py = when (ay) {
            'C' -> h / 2.0 + s * (y - 1400)
            'B' -> h - s * (2800 - y)
            else -> s * y
        }
        return Point(px, py)
    }

    /** Rectangle between two reference points, anchored like [pos]. */
    fun box(x1: Int, y1: Int, x2: Int, y2: Int, ax: Char = 'L', ay: Char = 'T'): Rect {
        val a = pos(x1, y1, ax, ay)
        val b = pos(x2, y2, ax, ay)
        return Rect(a.x.toInt(), a.y.toInt(), (b.x - a.x).toInt(), (b.y - a.y).toInt())
    }

    /** Rectangle given as fractions (0..1) of the screen: left, top, right, bottom. */
    fun frac(x1: Double, y1: Double, x2: Double, y2: Double) =
        Rect((w * x1).toInt(), (h * y1).toInt(), (w * (x2 - x1)).toInt(), (h * (y2 - y1)).toInt())

    // ---- template matching ----

    /** Loads templates/[name].png and scales it to this screen; cached. */
    private fun template(name: String): Mat = tpls.getOrPut(name) {
        val bytes = service.assets.open("templates/$name.png").use { it.readBytes() }
        val raw = Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_COLOR)
        if (abs(s - 1.0) > 0.01) {
            val out = Mat()
            Imgproc.resize(raw, out, org.opencv.core.Size(), s, s, if (s < 1) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC)
            raw.release()
            out
        } else raw
    }

    /** Half-size copy of a template for [findFast]. */
    private fun halfTemplate(name: String): Mat = halfTpls.getOrPut(name) {
        val out = Mat()
        Imgproc.resize(template(name), out, org.opencv.core.Size(), 0.5, 0.5, Imgproc.INTER_AREA)
        out
    }

    /** Clips [r] to the screen; null means the whole screen. */
    private fun clamp(screen: Mat, r: Rect?): Rect {
        if (r == null) return Rect(0, 0, screen.cols(), screen.rows())
        val x = max(0, r.x)
        val y = max(0, r.y)
        return Rect(x, y, min(r.width, screen.cols() - x), min(r.height, screen.rows() - y))
    }

    /** Best normalised score of [name] inside [roi] and the match's centre. */
    fun best(screen: Mat, name: String, roi: Rect? = null): Pair<Double, Point>? {
        val t = template(name)
        val rr = clamp(screen, roi)
        if (rr.width < t.cols() || rr.height < t.rows()) return null
        val sub = screen.submat(rr)
        val res = Mat()
        Imgproc.matchTemplate(sub, t, res, Imgproc.TM_CCOEFF_NORMED)
        val mm = Core.minMaxLoc(res)
        res.release()
        sub.release()
        return mm.maxVal to Point(rr.x + mm.maxLoc.x + t.cols() / 2, rr.y + mm.maxLoc.y + t.rows() / 2)
    }

    /** Centre of the best match of [name] in [roi] when its score is at least [thr], else null. */
    fun find(screen: Mat, name: String, roi: Rect? = null, thr: Double = 0.85): Point? {
        val b = best(screen, name, roi) ?: return null
        return if (b.first >= thr) b.second else null
    }

    /** Same as [find] but matched at half resolution: about 4x faster, scores differ by under 0.02 on these templates. */
    fun findFast(screen: Mat, name: String, roi: Rect? = null, thr: Double = 0.85): Point? {
        val t = halfTemplate(name)
        val rr = clamp(screen, roi)
        if (rr.width / 2 < t.cols() || rr.height / 2 < t.rows()) return null
        val small = Mat()
        Imgproc.resize(screen.submat(rr), small, org.opencv.core.Size(), 0.5, 0.5, Imgproc.INTER_AREA)
        val res = Mat()
        Imgproc.matchTemplate(small, t, res, Imgproc.TM_CCOEFF_NORMED)
        val mm = Core.minMaxLoc(res)
        res.release()
        small.release()
        if (mm.maxVal < thr) return null
        return Point(rr.x + (mm.maxLoc.x + t.cols() / 2.0) * 2, rr.y + (mm.maxLoc.y + t.rows() / 2.0) * 2)
    }

    /** Every non-overlapping match of [name] inside [roi] scoring at least [thr], best first. */
    fun findAll(screen: Mat, name: String, roi: Rect? = null, thr: Double = 0.85): List<Point> {
        val t = template(name)
        val rr = clamp(screen, roi)
        if (rr.width < t.cols() || rr.height < t.rows()) return emptyList()
        val sub = screen.submat(rr)
        val res = Mat()
        Imgproc.matchTemplate(sub, t, res, Imgproc.TM_CCOEFF_NORMED)
        val out = ArrayList<Point>()
        val minDist = min(t.cols(), t.rows()) / 2.0
        while (true) {
            val mm = Core.minMaxLoc(res)
            if (mm.maxVal < thr) break
            out.add(Point(rr.x + mm.maxLoc.x + t.cols() / 2.0, rr.y + mm.maxLoc.y + t.rows() / 2.0))
            val x0 = max(0, (mm.maxLoc.x - minDist).toInt())
            val y0 = max(0, (mm.maxLoc.y - minDist).toInt())
            val x1 = min(res.cols(), (mm.maxLoc.x + minDist).toInt() + 1)
            val y1 = min(res.rows(), (mm.maxLoc.y + minDist).toInt() + 1)
            res.submat(y0, y1, x0, x1).setTo(Scalar(-1.0))
        }
        res.release()
        sub.release()
        return out
    }

    /** Number of pixels inside [roi] whose HSV value lies between [lo] and [hi] (OpenCV scale: H 0..179). */
    fun countHsv(screen: Mat, roi: Rect, lo: Scalar, hi: Scalar): Int {
        val rr = clamp(screen, roi)
        val sub = screen.submat(rr)
        val hsv = Mat()
        Imgproc.cvtColor(sub, hsv, Imgproc.COLOR_BGR2HSV)
        val mask = Mat()
        Core.inRange(hsv, lo, hi, mask)
        val n = Core.countNonZero(mask)
        mask.release()
        hsv.release()
        sub.release()
        return n
    }

    /** Count matching pixels only inside the ring rMin..rMax (reference-tablet pixels, scaled) around the box centre. */
    fun countHsvRing(screen: Mat, roi: Rect, lo: Scalar, hi: Scalar, rMin: Double, rMax: Double): Int {
        val rr = clamp(screen, roi)
        val sub = screen.submat(rr)
        val hsv = Mat()
        Imgproc.cvtColor(sub, hsv, Imgproc.COLOR_BGR2HSV)
        val mask = Mat()
        Core.inRange(hsv, lo, hi, mask)
        val w = mask.cols()
        val h = mask.rows()
        val buf = ByteArray(w * h)
        mask.get(0, 0, buf)
        var n = 0
        val cx = w / 2.0
        val cy = h / 2.0
        for (y in 0 until h) for (x in 0 until w) {
            if (buf[y * w + x].toInt() != 0) {
                val d = Math.hypot(x - cx, y - cy)
                if (d >= rMin * s && d <= rMax * s) n++
            }
        }
        mask.release()
        hsv.release()
        sub.release()
        return n
    }
}
