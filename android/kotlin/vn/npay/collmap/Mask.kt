package vn.npay.collmap

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.media.ExifInterface
import android.view.MotionEvent
import android.view.View
import vn.npay.collmap.Ui.dp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The ellipse, as fractions of the captured frame and a tilt in degrees. Never
 * pixels: a change of still resolution or aspect must not move the mask off the
 * subject.
 */
data class MaskGeom(val cx: Float, val cy: Float, val rx: Float, val ry: Float,
                    val rot: Float) {
    fun clamped() = MaskGeom(
        cx.coerceIn(0f, 1f), cy.coerceIn(0f, 1f),
        rx.coerceIn(Mask.MIN_R, Mask.MAX_R), ry.coerceIn(Mask.MIN_R, Mask.MAX_R),
        rot.coerceIn(-Mask.ROT_MAX, Mask.ROT_MAX))

    companion object {
        /** Roughly a circle on a portrait 3:4 viewfinder, centred, untilted. */
        val DEFAULT = MaskGeom(0.5f, 0.5f, 0.40f, 0.30f, 0f)
    }
}

/**
 * The fixed elliptical mask.
 *
 * The rig is a round turntable under a camera that never moves, so the region
 * worth reconstructing is the same ellipse in every single frame. Everything
 * outside it is the room -- and a static room photographed by a camera that
 * COLMAP believes is orbiting reconstructs into geometry that swallows the
 * actual subject. Cutting it at the source is both the cheapest fix and the
 * only one the user can see while framing, which is why the ellipse is drawn
 * over the viewfinder instead of being applied out of sight on the server.
 *
 * An ellipse and not a circle because a disc viewed from an angle is one, and
 * a tiltable ellipse because the disc is only axis-aligned when the camera is
 * square-on to it. Off to one side it leans, and without a rotation the user
 * has to oversize the mask to cover the whole table -- which drags the room
 * back in, the one thing this exists to keep out.
 *
 * Two working modes, and they are worth separating:
 *
 *  - [MODE_BLACK] paints outside the ellipse black and keeps the frame size.
 *    That alone is enough to keep the room out of the reconstruction and it is
 *    completely safe: same pixels, same width, same intrinsics.
 *  - [MODE_CROP] also cuts the frame down to the ellipse's bounding box. That
 *    buys resolution on the subject, but it changes the effective sensor and
 *    therefore the intrinsics, so it is the mode that has to be exactly right
 *    (see [copyExif]).
 *
 * Either way the geometry is set once and then left alone for the whole run:
 * the crop is what the intrinsics are solved against, so an ellipse that moved
 * between frames would hand the mapper a camera that is a different camera in
 * every photo. The capture screen refuses every mask control while a run is
 * going, exactly as it refuses zoom.
 */
object Mask {
    const val MODE_OFF = 0
    const val MODE_BLACK = 1
    const val MODE_CROP = 2

    const val MIN_R = 0.05f
    const val MAX_R = 0.62f
    /** More lean than this is a camera pointed at the table from the side. */
    const val ROT_MAX = 45f
    /** Dark enough to read as "thrown away", light enough to still frame in. */
    private const val SCRIM = 0xB3000000.toInt()

    private const val KEY_CX = "mask_cx"
    private const val KEY_CY = "mask_cy"
    private const val KEY_RX = "mask_rx"
    private const val KEY_RY = "mask_ry"
    private const val KEY_ROT = "mask_rot"
    private const val KEY_MODE = "mask_mode"

    /** Everything the mapper cares about, plus what identifies the camera. */
    private val CARRY = listOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        // Not asked for but load-bearing: Bitmap.compress writes no EXIF at
        // all, so without this the processed frame loses the rotation the rest
        // of the set still carries.
        ExifInterface.TAG_ORIENTATION)

    fun load(p: SharedPreferences) = MaskGeom(
        p.getFloat(KEY_CX, MaskGeom.DEFAULT.cx),
        p.getFloat(KEY_CY, MaskGeom.DEFAULT.cy),
        p.getFloat(KEY_RX, MaskGeom.DEFAULT.rx),
        p.getFloat(KEY_RY, MaskGeom.DEFAULT.ry),
        p.getFloat(KEY_ROT, MaskGeom.DEFAULT.rot)).clamped()

    fun save(p: SharedPreferences, m: MaskGeom) = p.edit()
        .putFloat(KEY_CX, m.cx).putFloat(KEY_CY, m.cy)
        .putFloat(KEY_RX, m.rx).putFloat(KEY_RY, m.ry)
        .putFloat(KEY_ROT, m.rot).apply()

    fun mode(p: SharedPreferences) = p.getInt(KEY_MODE, MODE_OFF).coerceIn(MODE_OFF, MODE_CROP)

    fun setMode(p: SharedPreferences, mode: Int) = p.edit().putInt(KEY_MODE, mode).apply()

    fun modeLabel(mode: Int) = when (mode) {
        MODE_BLACK -> "Bôi đen"
        MODE_CROP -> "Bôi đen + cắt"
        else -> "Tắt"
    }

    fun ellipsePath(oval: RectF, rot: Float): Path {
        val p = Path().apply { addOval(oval, Path.Direction.CW) }
        if (abs(rot) > 0.01f) {
            p.transform(Matrix().apply { setRotate(rot, oval.centerX(), oval.centerY()) })
        }
        return p
    }

    /**
     * The dim-everything-outside look, shared by the viewfinder overlay and by
     * the live frames pushed to the web page so both show the same keep region.
     */
    fun drawScrim(canvas: Canvas, frame: RectF, oval: RectF, rot: Float, strokePx: Float) {
        val ellipse = ellipsePath(oval, rot)
        val ring = Path(ellipse).apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(frame, Path.Direction.CW)
        }
        canvas.drawPath(ring, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SCRIM })
        canvas.drawPath(ellipse, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            color = Ui.AMBER
        })
    }

    /**
     * Applies the mask to a captured JPEG. Returns null when the frame could
     * not be processed, which the caller answers by uploading the original:
     * losing the mask beats losing the shot.
     *
     * Memory is the constraint here. A 12 MP ARGB_8888 decode is ~48 MB on its
     * own, so [MODE_BLACK] paints into the decoded bitmap itself rather than
     * allocating a second full-size one, [MODE_CROP] allocates only the crop
     * and recycles the source the moment it has been drawn, and the cut is a
     * PorterDuff pass (DST_IN against the antialiased ellipse, black put back
     * underneath with DST_OVER) instead of a full-frame mask bitmap.
     */
    fun process(jpeg: ByteArray, display: MaskGeom, mode: Int, cacheDir: File): ByteArray? {
        if (mode == MODE_OFF) return jpeg
        var src: Bitmap? = null
        var out: Bitmap? = null
        var tmp: File? = null
        try {
            val rot = exifRotation(jpeg)
            val opts = BitmapFactory.Options().apply { inMutable = mode == MODE_BLACK }
            src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
            val w = src.width
            val h = src.height
            val m = toBuffer(display, rot)
            val cx = m.cx * w
            val cy = m.cy * h
            val a = m.rx * w
            val b = m.ry * h
            val ellipse = ellipsePath(RectF(cx - a, cy - b, cx + a, cy + b), m.rot)
            var focalScale = 1f

            if (mode == MODE_BLACK) {
                val canvas = Canvas(src)
                canvas.drawPath(Path(ellipse).apply {
                    fillType = Path.FillType.EVEN_ODD
                    addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
                }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
                out = src
                src = null
            } else {
                /* Half-extents of the tilted ellipse's upright bounding box;
                 * the naive rx/ry box would clip the corners of a leaning one. */
                val t = Math.toRadians(m.rot.toDouble())
                val ex = sqrt((a * cos(t)) * (a * cos(t)) + (b * sin(t)) * (b * sin(t)))
                val ey = sqrt((a * sin(t)) * (a * sin(t)) + (b * cos(t)) * (b * cos(t)))
                val l = floor(cx - ex).toInt().coerceIn(0, w - 1)
                val top = floor(cy - ey).toInt().coerceIn(0, h - 1)
                val r = ceil(cx + ex).toInt().coerceIn(l + 1, w)
                val bot = ceil(cy + ey).toInt().coerceIn(top + 1, h)
                val cw = r - l
                val ch = bot - top
                if (cw < 64 || ch < 64) return null
                out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                canvas.drawBitmap(src, -l.toFloat(), -top.toFloat(), null)
                src.recycle()
                src = null
                ellipse.offset(-l.toFloat(), -top.toFloat())
                canvas.drawPath(ellipse, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                })
                canvas.drawColor(Color.BLACK, PorterDuff.Mode.DST_OVER)
                focalScale = maxOf(w, h).toFloat() / maxOf(cw, ch)
            }

            val bytes = ByteArrayOutputStream(jpeg.size).also {
                out!!.compress(Bitmap.CompressFormat.JPEG, 95, it)
            }.toByteArray()
            out!!.recycle()
            out = null

            // ExifInterface can only write into a file or a seekable stream, so
            // the result goes to cacheDir, gets patched there, and is read back.
            tmp = File.createTempFile("mask", ".jpg", cacheDir)
            tmp.writeBytes(bytes)
            copyExif(jpeg, tmp, focalScale)
            return tmp.readBytes()
        } catch (_: OutOfMemoryError) {
            return null
        } catch (_: Exception) {
            return null
        } finally {
            src?.recycle()
            out?.recycle()
            tmp?.delete()
        }
    }

    /**
     * COLMAP reads FocalLengthIn35mmFilm and turns it into pixels using the
     * image width, so a crop that narrows the frame without touching the lens
     * has to carry a 35 mm equivalent multiplied by the crop factor on
     * that same axis -- max(width, height), which is what COLMAP measures
     * against, not width. Left at the camera's value it hands the mapper a focal length
     * wrong by exactly that factor, which is worse than no prior at all.
     *
     * [focalScale] is 1 for [MODE_BLACK] and the tag is then copied untouched:
     * blacking out changes no dimension, and "correcting" it there would be the
     * same mistake in the other direction.
     */
    private fun copyExif(original: ByteArray, target: File, focalScale: Float) {
        val from = ExifInterface(ByteArrayInputStream(original))
        val to = ExifInterface(target.absolutePath)
        for (tag in CARRY) from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
        val f35 = from.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0.0)
        if (f35 > 0.0) {
            // A SHORT tag: whole millimetres, there is no fraction to keep.
            to.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                (f35 * focalScale).roundToInt().toString())
        }
        to.saveAttributes()
    }

    private fun exifRotation(jpeg: ByteArray): Int = try {
        when (ExifInterface(ByteArrayInputStream(jpeg))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }

    /**
     * The mask is dialled in against the upright viewfinder, but the JPEG comes
     * off the sensor in its own orientation with a tag saying how far it has to
     * be turned to look right. The mask works on the stored pixels -- rotating
     * a 12 MP bitmap to avoid this would cost another 48 MB -- so the ellipse
     * is turned backwards instead. A quarter turn swaps the radii, and because
     * that swap already *is* a 90 degree rotation, the user's tilt carries over
     * unchanged on top of it.
     */
    private fun toBuffer(m: MaskGeom, rotDeg: Int): MaskGeom = when (rotDeg) {
        90 -> MaskGeom(m.cy, 1f - m.cx, m.ry, m.rx, m.rot)
        180 -> MaskGeom(1f - m.cx, 1f - m.cy, m.rx, m.ry, m.rot)
        270 -> MaskGeom(1f - m.cy, m.cx, m.ry, m.rx, m.rot)
        else -> m
    }
}

/**
 * The ellipse over the viewfinder: a scrim on what will be thrown away and an
 * amber outline on what will be kept.
 *
 * It sits above the TextureView but below the control strips, and it only takes
 * touches in edit mode -- pinch is already zoom, and a view that swallowed
 * gestures it does not need would take that away.
 */
class MaskOverlay(ctx: Context) : View(ctx) {

    /** Where the letterboxed camera frame actually lands inside this view. */
    val frame = RectF()
    var geom: MaskGeom = MaskGeom.DEFAULT
        set(value) { field = value.clamped(); postInvalidate() }
    var editing = false
        set(value) { field = value; postInvalidate() }
    var showing = false
        set(value) { field = value; postInvalidate() }
    /** Fired when a drag ends, so the caller persists once and not per pixel. */
    var onCommit: ((MaskGeom) -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private val stroke = ctx.dp(2f).toFloat()

    fun setFrame(l: Float, t: Float, r: Float, b: Float) {
        frame.set(l, t, r, b)
        postInvalidate()
    }

    /** The ellipse in view pixels, before its tilt. */
    fun oval(): RectF {
        val cx = frame.left + geom.cx * frame.width()
        val cy = frame.top + geom.cy * frame.height()
        val rx = geom.rx * frame.width()
        val ry = geom.ry * frame.height()
        return RectF(cx - rx, cy - ry, cx + rx, cy + ry)
    }

    /** Frame aspect, needed to talk about the squash in what the eye sees. */
    private fun aspect(): Float =
        if (frame.height() > 0f) frame.width() / frame.height() else 0.75f

    /** 1.0 is a circle on screen; below that the disc is squashed by tilt. */
    fun squash(): Float =
        if (geom.rx <= 0f) 1f else (geom.ry / geom.rx) / aspect()

    fun setSize(rx: Float) {
        val s = squash()
        geom = MaskGeom(geom.cx, geom.cy, rx, s * rx * aspect(), geom.rot)
    }

    fun setSquash(s: Float) {
        geom = MaskGeom(geom.cx, geom.cy, geom.rx, s * geom.rx * aspect(), geom.rot)
    }

    fun setRot(deg: Float) {
        geom = MaskGeom(geom.cx, geom.cy, geom.rx, geom.ry, deg)
    }

    /** Back to a centred circle covering ~80% of the frame's short edge. */
    fun reset() {
        val r = 0.4f * minOf(frame.width(), frame.height())
        geom = if (r <= 0f) MaskGeom.DEFAULT
               else MaskGeom(0.5f, 0.5f, r / frame.width(), r / frame.height(), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        if (!showing && !editing) return
        if (frame.width() <= 0f || frame.height() <= 0f) return
        Mask.drawScrim(canvas, frame, oval(), geom.rot, stroke)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Anything not handled here falls through to the TextureView below,
        // which is where the zoom detector lives.
        if (!editing || frame.width() <= 0f) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inside(e.x, e.y)) return false
                lastX = e.x
                lastY = e.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                geom = MaskGeom(
                    geom.cx + (e.x - lastX) / frame.width(),
                    geom.cy + (e.y - lastY) / frame.height(),
                    geom.rx, geom.ry, geom.rot)
                lastX = e.x
                lastY = e.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onCommit?.invoke(geom)
                return true
            }
        }
        return false
    }

    /** Inside the tilted ellipse, in its own frame of reference. */
    private fun inside(x: Float, y: Float): Boolean {
        val o = oval()
        if (o.width() <= 0f || o.height() <= 0f) return false
        val t = Math.toRadians(-geom.rot.toDouble())
        val dx = (x - o.centerX()).toDouble()
        val dy = (y - o.centerY()).toDouble()
        val ux = (dx * cos(t) - dy * sin(t)) / (o.width() / 2f)
        val uy = (dx * sin(t) + dy * cos(t)) / (o.height() / 2f)
        return ux * ux + uy * uy <= 1.0
    }
}
