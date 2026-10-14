package com.hinterlay.app.camera

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Everything the masks need to know about one face, in image pixels.
 *
 * The head has its own coordinate system: the origin is a little below the eyes, x runs along the line between the eyes,
 * y runs "down the face" and one unit is the true distance between the eyes. Masks are described in that system, so they
 * turn, tilt and shrink with the head instead of being stuck to the face box.
 */
class FacePose(
    val id: Int,
    val center: PointF,
    /** Eye distance in the image (it shrinks when the head turns). */
    val u: Float,
    val roll: Float,
    val yaw: Float,
    val pitch: Float,
    val eyeLy: Float,
    val noseLy: Float,
    val mouthLy: Float,
    /** 0 = mouth closed, 1 = wide open. */
    val mouthOpen: Float,
    val oval: List<PointF>,
    val groups: List<List<PointF>>,
    val box: RectF,
) {
    /** The 468-point 3D mesh (x, y, z per point, image pixels) when the mesh model found this face; null otherwise. */
    var mesh: FloatArray? = null

    /** The true eye distance: [u] grows back as the head turns away from the front. */
    val unit: Float get() = u / max(0.6f, cos(Math.toRadians(yaw.toDouble())).toFloat())

    fun lerpTo(next: FacePose, k: Float): FacePose {
        fun l(a: Float, b: Float) = a + (b - a) * k
        fun lp(a: PointF, b: PointF) = PointF(l(a.x, b.x), l(a.y, b.y))
        fun lg(a: List<PointF>, b: List<PointF>) = if (a.size == b.size) a.indices.map { lp(a[it], b[it]) } else b
        val out = FacePose(
            next.id, lp(center, next.center), l(u, next.u), roll + angleDiff(roll, next.roll) * k, l(yaw, next.yaw), l(pitch, next.pitch),
            l(eyeLy, next.eyeLy), l(noseLy, next.noseLy), l(mouthLy, next.mouthLy), l(mouthOpen, next.mouthOpen),
            lg(oval, next.oval), if (groups.size == next.groups.size) groups.indices.map { lg(groups[it], next.groups[it]) } else next.groups,
            RectF(l(box.left, next.box.left), l(box.top, next.box.top), l(box.right, next.box.right), l(box.bottom, next.box.bottom)),
        )
        val a = mesh; val b = next.mesh
        out.mesh = if (a != null && b != null && a.size == b.size) FloatArray(a.size) { a[it] + (b[it] - a[it]) * k } else b
        return out
    }

    /** The same pose in a photo [k] times as big (for faces found on a reduced copy). */
    fun scaled(k: Float): FacePose {
        fun sp(p: PointF) = PointF(p.x * k, p.y * k)
        return FacePose(
            id, sp(center), u * k, roll, yaw, pitch, eyeLy, noseLy, mouthLy, mouthOpen,
            oval.map(::sp), groups.map { g -> g.map(::sp) }, RectF(box.left * k, box.top * k, box.right * k, box.bottom * k),
        ).also { o -> o.mesh = mesh?.let { m -> FloatArray(m.size) { m[it] * k } } }
    }

    private fun angleDiff(a: Float, b: Float): Float { var d = (b - a) % 360f; if (d > 180f) d -= 360f; if (d < -180f) d += 360f; return d }

    companion object {
        private val contourTypes = intArrayOf(
            FaceContour.LEFT_EYEBROW_TOP, FaceContour.LEFT_EYEBROW_BOTTOM, FaceContour.RIGHT_EYEBROW_TOP, FaceContour.RIGHT_EYEBROW_BOTTOM,
            FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE, FaceContour.UPPER_LIP_TOP, FaceContour.UPPER_LIP_BOTTOM,
            FaceContour.LOWER_LIP_TOP, FaceContour.LOWER_LIP_BOTTOM, FaceContour.NOSE_BRIDGE, FaceContour.NOSE_BOTTOM,
            FaceContour.LEFT_CHEEK, FaceContour.RIGHT_CHEEK,
        )

        fun from(face: Face, index: Int): FacePose {
            val box = RectF(face.boundingBox)
            fun lm(t: Int) = face.getLandmark(t)?.position?.let { PointF(it.x, it.y) }
            val a0 = lm(FaceLandmark.LEFT_EYE) ?: PointF(box.left + box.width() * 0.32f, box.top + box.height() * 0.40f)
            val b0 = lm(FaceLandmark.RIGHT_EYE) ?: PointF(box.left + box.width() * 0.68f, box.top + box.height() * 0.40f)
            val (a, b) = if (a0.x <= b0.x) a0 to b0 else b0 to a0
            val d = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1f)
            val ax = (b.x - a.x) / d
            val ay = (b.y - a.y) / d
            val dx = -ay
            val dy = ax
            val mid = PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)
            val nose = lm(FaceLandmark.NOSE_BASE) ?: PointF(mid.x + dx * d * 0.95f, mid.y + dy * d * 0.95f)
            // How far the nose sits to the side of the eye midpoint tells how far the head is turned.
            val along = ((nose.x - mid.x) * ax + (nose.y - mid.y) * ay) / d
            val yaw = Math.toDegrees(atan(2.0 * along)).toFloat().coerceIn(-55f, 55f)
            val unit = d / max(0.6f, cos(Math.toRadians(yaw.toDouble())).toFloat())
            val roll = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
            val center = PointF(mid.x + dx * 0.35f * unit, mid.y + dy * 0.35f * unit)
            fun ly(p: PointF) = ((p.x - center.x) * dx + (p.y - center.y) * dy) / unit

            val ml = lm(FaceLandmark.MOUTH_LEFT)
            val mr = lm(FaceLandmark.MOUTH_RIGHT)
            val mouth = if (ml != null && mr != null) PointF((ml.x + mr.x) / 2f, (ml.y + mr.y) / 2f) else PointF(mid.x + dx * unit * 1.5f, mid.y + dy * unit * 1.5f)

            val groups = contourTypes.map { t -> face.getContour(t)?.points?.map { PointF(it.x, it.y) } ?: emptyList() }
            val oval = face.getContour(FaceContour.FACE)?.points?.map { PointF(it.x, it.y) } ?: emptyList()
            val upperBottom = groups[7]
            val lowerTop = groups[8]
            val open = if (upperBottom.isNotEmpty() && lowerTop.isNotEmpty()) {
                val gap = lowerTop.map { ly(it) }.average() - upperBottom.map { ly(it) }.average()
                ((gap - 0.04) / 0.36).toFloat().coerceIn(0f, 1f)
            } else 0f

            return FacePose(
                face.trackingId ?: index, center, d, roll, yaw, face.headEulerAngleX.coerceIn(-30f, 30f),
                -0.35f, ly(nose), ly(mouth), open, oval, groups, box,
            )
        }
    }
}

/** Keeps each face steady between camera frames (the raw detections jitter) and remembers when each face first appeared. */
class FaceSmoother {
    private val last = HashMap<Int, FacePose>()
    private val seen = HashMap<Int, Long>()

    /** Smoothed poses for the new detections; faces that vanished are forgotten. */
    fun update(faces: List<Face>, nowNanos: Long): List<FacePose> {
        val out = faces.mapIndexed { i, f ->
            val raw = FacePose.from(f, i)
            val prev = last[raw.id]
            val p = if (prev == null) raw else prev.lerpTo(raw, 0.55f)
            last[raw.id] = p
            if (raw.id !in seen) seen[raw.id] = nowNanos
            p
        }
        val ids = out.map { it.id }.toSet()
        last.keys.retainAll(ids); seen.keys.retainAll(ids)
        return out
    }

    fun firstSeen(id: Int): Long = seen[id] ?: 0L
    fun reset() { last.clear(); seen.clear() }
}

/** The scan: a mesh of dots and lines over the face outline, revealed from the forehead down by a sweeping line. */
object FaceMesh {
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG)

    /** [progress] 0..1 over the whole scan. Draws nothing at 0 and fades itself out near 1. */
    fun draw(c: Canvas, p: FacePose, progress: Float) {
        if (progress <= 0f || progress >= 1f) return
        val fade = if (progress > 0.78f) 1f - (progress - 0.78f) / 0.22f else 1f
        val reveal = (progress / 0.78f).coerceIn(0f, 1f)
        val top = p.box.top - p.unit * 0.3f
        val bottom = p.box.bottom + p.unit * 0.15f
        val scanY = top + (bottom - top) * reveal
        val w = p.unit * 0.012f + 1.2f

        val mesh = p.mesh
        val tris = MeshFx.triangles
        if (mesh != null && tris.isNotEmpty()) {
            // The real mesh: every triangle whose corners the sweep has passed.
            val n = tris.size / 3
            line.strokeWidth = w * 0.7f
            line.color = Color.argb((120 * fade).toInt(), 255, 255, 255)
            val seen = BooleanArray(mesh.size / 3)
            for (t in 0 until n) {
                val a = tris[t * 3]; val b = tris[t * 3 + 1]; val cc = tris[t * 3 + 2]
                if (mesh[a * 3 + 1] > scanY || mesh[b * 3 + 1] > scanY || mesh[cc * 3 + 1] > scanY) continue
                c.drawLine(mesh[a * 3], mesh[a * 3 + 1], mesh[b * 3], mesh[b * 3 + 1], line)
                c.drawLine(mesh[b * 3], mesh[b * 3 + 1], mesh[cc * 3], mesh[cc * 3 + 1], line)
                c.drawLine(mesh[cc * 3], mesh[cc * 3 + 1], mesh[a * 3], mesh[a * 3 + 1], line)
                seen[a] = true; seen[b] = true; seen[cc] = true
            }
            val rr0 = p.unit * 0.014f + 1.2f
            for (i in seen.indices) if (seen[i]) {
                dot.color = Color.argb((255 * fade).toInt(), 255, 255, 255); c.drawCircle(mesh[i * 3], mesh[i * 3 + 1], rr0, dot)
            }
            val l0 = p.box.left - p.unit * 0.5f; val r0 = p.box.right + p.unit * 0.5f
            line.strokeWidth = w * 1.6f; line.color = Color.argb((255 * fade).toInt(), 255, 255, 255)
            c.drawLine(l0, scanY, r0, scanY, line)
            return
        }

        // All the points of the outline and the features, with the ones the sweep has passed marked as revealed.
        val feature = p.groups.flatten()
        val all = (p.oval + feature)
        fun on(pt: PointF) = pt.y <= scanY

        // Lines along every outline and feature.
        line.strokeWidth = w
        line.color = Color.argb((190 * fade).toInt(), 255, 255, 255)
        fun chain(pts: List<PointF>, close: Boolean) {
            for (i in 0 until pts.size - 1) if (on(pts[i]) && on(pts[i + 1])) c.drawLine(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y, line)
            if (close && pts.size > 2 && on(pts.first()) && on(pts.last())) c.drawLine(pts.last().x, pts.last().y, pts.first().x, pts.first().y, line)
        }
        chain(p.oval, true)
        p.groups.forEachIndexed { i, g -> chain(g, i == 4 || i == 5) }

        // Spokes from the outline to the nearest feature points, which is what makes it look like a mesh.
        line.strokeWidth = w * 0.6f
        line.color = Color.argb((95 * fade).toInt(), 255, 255, 255)
        if (feature.isNotEmpty()) {
            for (i in p.oval.indices step 2) {
                val o = p.oval[i]
                if (!on(o)) continue
                val near = feature.filter { on(it) }.sortedBy { hypot(it.x - o.x, it.y - o.y) }.take(2)
                near.forEach { c.drawLine(o.x, o.y, it.x, it.y, line) }
            }
            // A few cross links between neighbouring features for density.
            val step = max(1, feature.size / 40)
            for (i in 0 until feature.size - step step step) {
                val a = feature[i]; val b = feature[i + step]
                if (on(a) && on(b) && hypot(a.x - b.x, a.y - b.y) < p.unit * 0.9f) c.drawLine(a.x, a.y, b.x, b.y, line)
            }
        }

        // Dots with a soft halo.
        val r = p.unit * 0.022f + 1.5f
        for (pt in all) {
            if (!on(pt)) continue
            dot.color = Color.argb((70 * fade).toInt(), 255, 255, 255); c.drawCircle(pt.x, pt.y, r * 2.4f, dot)
            dot.color = Color.argb((255 * fade).toInt(), 255, 255, 255); c.drawCircle(pt.x, pt.y, r, dot)
        }

        // The sweeping line, with a glow trailing above it.
        val l = p.box.left - p.unit * 0.5f
        val rr = p.box.right + p.unit * 0.5f
        val glowH = p.unit * 0.6f
        sweep.shader = LinearGradient(0f, scanY - glowH, 0f, scanY, Color.argb(0, 255, 255, 255), Color.argb((120 * fade).toInt(), 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRect(l, scanY - glowH, rr, scanY, sweep)
        line.strokeWidth = w * 1.6f
        line.color = Color.argb((255 * fade).toInt(), 255, 255, 255)
        c.drawLine(l, scanY, rr, scanY, line)
    }
}

/** Places flat, shaded parts in the head's own 3D space and draws them with the head's turn, tilt and roll. */
class Rig(val p: FacePose, val pop: Float, val t: Float) {
    private val yawR = Math.toRadians(p.yaw.toDouble()).toFloat()
    private val pitchR = Math.toRadians(p.pitch.toDouble()).toFloat()
    private val rollR = Math.toRadians(p.roll.toDouble()).toFloat()
    private val cy = cos(yawR); private val sy = sin(yawR)
    private val cp = cos(pitchR); private val sp = sin(pitchR)
    private val cr = cos(rollR); private val sr = sin(rollR)
    val unit = p.unit

    /** Head space (x right, y down, z towards the viewer, in eye distances) to image pixels. */
    fun project(x: Float, y: Float, z: Float): PointF {
        val x1 = x * cy + z * sy
        val z1 = -x * sy + z * cy
        val y1 = y * cp - z1 * sp
        val z2 = y * sp + z1 * cp
        val persp = 6f / (6f - z2).coerceAtLeast(2f)
        val px = x1 * persp * pop
        val py = y1 * persp * pop
        val rx = px * cr - py * sr
        val ry = px * sr + py * cr
        return PointF(p.center.x + rx * unit, p.center.y + ry * unit)
    }

    private val m = Matrix()

    /**
     * A flat piece [hw] wide and [hh] tall (each half-size, in eye distances) centred at (x, y, z), turned [inPlane] degrees
     * in its own plane. [draw] paints it on a 200-unit-wide drawing box of height [bh].
     */
    fun part(
        c: Canvas, x: Float, y: Float, z: Float, hw: Float, hh: Float, inPlane: Float = 0f, alpha: Float = 1f,
        occlude: Boolean = false, draw: (Canvas, Float, Float) -> Unit,
    ) {
        // A part on the far side of a turned head is hidden where the face is in front of it.
        val clip = occlude && far(x) && ovalPath != null
        if (clip) { c.save(); c.clipOutPath(ovalPath!!) }
        try { partInner(c, x, y, z, hw, hh, inPlane, alpha, draw) } finally { if (clip) c.restore() }
    }

    private val ovalPath: Path? by lazy {
        if (p.oval.size < 6) null else Path().apply { p.oval.forEachIndexed { i, pt -> if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y) }; close() }
    }

    /** True for a position on the side of the head that is turned away from the viewer. */
    fun far(x: Float) = abs(p.yaw) > 10f && x * p.yaw > 0f

    private fun partInner(c: Canvas, x: Float, y: Float, z: Float, hw: Float, hh: Float, inPlane: Float, alpha: Float, draw: (Canvas, Float, Float) -> Unit) {
        val bw = 200f
        val bh = 200f * hh / hw
        val ip = Math.toRadians(inPlane.toDouble()).toFloat()
        val ci = cos(ip); val si = sin(ip)
        fun corner(px: Float, py: Float): PointF = project(x + px * ci - py * si, y + px * si + py * ci, z)
        val tl = corner(-hw, -hh); val tr = corner(hw, -hh); val bl = corner(-hw, hh)
        // Three corners fix an affine map, which keeps the result the same on every canvas type.
        m.setPolyToPoly(floatArrayOf(0f, 0f, bw, 0f, 0f, bh), 0, floatArrayOf(tl.x, tl.y, tr.x, tr.y, bl.x, bl.y), 0, 3)
        c.save()
        c.concat(m)
        if (alpha < 1f) c.saveLayerAlpha(0f, 0f, bw, bh, (alpha * 255).toInt())
        draw(c, bw, bh)
        if (alpha < 1f) c.restore()
        c.restore()
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    fun line3(c: Canvas, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, width: Float, color: Int) {
        val a = project(x1, y1, z1); val b = project(x2, y2, z2)
        linePaint.strokeWidth = width * unit; linePaint.color = color
        c.drawLine(a.x, a.y, b.x, b.y, linePaint)
    }
}

/** Drawing helpers for the shaded look of the parts. */
private object Ink {
    fun fill(shader: Shader? = null, color: Int = 0) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.shader = shader; if (shader == null) this.color = color }
    fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = color; strokeWidth = w }
    fun radial(cx: Float, cy: Float, r: Float, inner: Int, outer: Int): Shader = RadialGradient(cx, cy, r.coerceAtLeast(1f), inner, outer, Shader.TileMode.CLAMP)
    fun linear(x0: Float, y0: Float, x1: Float, y1: Float, a: Int, b: Int): Shader = LinearGradient(x0, y0, x1, y1, a, b, Shader.TileMode.CLAMP)
    fun shine(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, alpha: Int = 200, rot: Float = -25f) {
        c.save(); c.rotate(rot, cx, cy)
        c.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), fill(color = Color.argb(alpha, 255, 255, 255)))
        c.restore()
    }
    fun heart(w: Float, h: Float) = Path().apply {
        moveTo(w * 0.5f, h * 0.92f)
        cubicTo(w * 0.05f, h * 0.6f, w * 0.0f, h * 0.2f, w * 0.27f, h * 0.12f)
        cubicTo(w * 0.42f, h * 0.08f, w * 0.5f, h * 0.2f, w * 0.5f, h * 0.28f)
        cubicTo(w * 0.5f, h * 0.2f, w * 0.58f, h * 0.08f, w * 0.73f, h * 0.12f)
        cubicTo(w * 1.0f, h * 0.2f, w * 0.95f, h * 0.6f, w * 0.5f, h * 0.92f)
        close()
    }
}

/** The mask drawings. Each uses the head's own space (see [FacePose]); z grows towards the viewer. */
object Masks3D {

    /**
     * Draws [mask] on [p]. [t] is time in seconds (for the gentle movement), [appear] 0..1 pops the mask in with a little
     * overshoot, like a sticker landing on the face.
     */
    fun draw(c: Canvas, p: FacePose, mask: FaceMask, t: Float, appear: Float) {
        if (mask == FaceMask.NONE || appear <= 0f) return
        val a = appear.coerceIn(0f, 1f)
        // ease-out-back: lands slightly big, then settles.
        val k = 1.70158f
        val e = 1f + (k + 1f) * (a - 1f) * (a - 1f) * (a - 1f) + k * (a - 1f) * (a - 1f)
        val rig = Rig(p, (0.55f + 0.45f * e).coerceAtLeast(0.01f), t)
        val alpha = (a * 1.6f).coerceIn(0f, 1f)
        val layer = if (alpha < 1f) c.saveLayerAlpha(null, (alpha * 255).toInt()) else -1
        when (mask) {
            FaceMask.DOG -> puppy(c, rig)
            FaceMask.CAT -> kitty(c, rig)
            FaceMask.SHADES -> shades(c, rig)
            FaceMask.HEARTS -> hearts(c, rig)
            FaceMask.CROWN -> crown(c, rig)
            FaceMask.CLOWN -> clown(c, rig)
            FaceMask.ALIEN -> alien(c, rig)
            FaceMask.BUNNY -> bunny(c, rig)
            FaceMask.FOX -> fox(c, rig)
            else -> Unit
        }
        if (layer >= 0) c.restoreToCount(layer)
    }

    private fun sway(t: Float, phase: Float, deg: Float) = sin(t * 2.4f + phase) * deg

    // ---- Puppy --------------------------------------------------------------------------------------

    private fun earShape(w: Float, h: Float) = Path().apply {
        moveTo(w * 0.30f, 0f)
        cubicTo(w * 0.04f, h * 0.06f, -w * 0.02f, h * 0.55f, w * 0.12f, h * 0.86f)
        cubicTo(w * 0.26f, h * 1.02f, w * 0.80f, h * 1.02f, w * 0.90f, h * 0.80f)
        cubicTo(w * 1.02f, h * 0.50f, w * 0.96f, h * 0.10f, w * 0.72f, 0f)
        close()
    }

    private fun puppy(c: Canvas, r: Rig) {
        val p = r.p
        // Floppy ears hang from the top corners of the head and swing a little.
        for (side in intArrayOf(-1, 1)) {
            r.part(c, side * 1.0f, -1.2f, -0.62f, 0.68f, 1.05f, inPlane = side * (16f + sway(r.t, side * 1.3f, 5f)), occlude = true) { cv, w, h ->
                cv.save(); if (side > 0) cv.scale(-1f, 1f, w / 2f, 0f)
                val shape = earShape(w, h)
                cv.drawPath(shape, Ink.fill(Ink.linear(0f, 0f, w, h, Color.rgb(166, 112, 66), Color.rgb(84, 50, 24))))
                cv.save(); cv.scale(0.66f, 0.74f, w * 0.45f, h * 0.45f)
                cv.drawPath(shape, Ink.fill(color = Color.argb(110, 40, 22, 10))); cv.restore()
                Ink.shine(cv, w * 0.34f, h * 0.2f, w * 0.14f, h * 0.07f, 70, -35f)
                cv.restore()
            }
        }
        // Soft tan muzzle around the nose and mouth.
        val my = (p.noseLy + p.mouthLy) / 2f
        r.part(c, 0f, my, 0.12f, 0.82f, 0.68f, alpha = 0.9f) { cv, w, h ->
            cv.drawOval(RectF(0f, 0f, w, h), Ink.fill(Ink.radial(w / 2f, h / 2f, w * 0.5f, Color.argb(225, 244, 214, 170), Color.argb(0, 244, 214, 170))))
        }
        // Tongue, only when the mouth is open.
        if (p.mouthOpen > 0.12f) {
            val grow = p.mouthOpen
            r.part(c, 0f, p.mouthLy + 0.10f + grow * 0.33f, 0.12f, 0.27f, 0.20f + 0.34f * grow, alpha = 1f) { cv, w, h ->
                val path = Path().apply {
                    moveTo(w * 0.05f, 0f); lineTo(w * 0.95f, 0f)
                    cubicTo(w * 1.0f, h * 0.7f, w * 0.75f, h * 1.0f, w * 0.5f, h * 1.0f)
                    cubicTo(w * 0.25f, h * 1.0f, w * 0.0f, h * 0.7f, w * 0.05f, 0f); close()
                }
                cv.drawPath(path, Ink.fill(Ink.linear(0f, 0f, 0f, h, Color.rgb(255, 120, 150), Color.rgb(208, 62, 98))))
                cv.drawLine(w * 0.5f, h * 0.05f, w * 0.5f, h * 0.7f, Ink.stroke(Color.argb(120, 150, 30, 60), w * 0.045f))
                Ink.shine(cv, w * 0.34f, h * 0.22f, w * 0.12f, h * 0.07f, 110, -20f)
            }
        }
        // Glossy black nose with a highlight.
        r.part(c, 0f, p.noseLy - 0.04f, 0.42f, 0.33f, 0.24f) { cv, w, h ->
            val path = Path().apply {
                moveTo(w * 0.12f, h * 0.18f)
                cubicTo(w * 0.3f, -h * 0.05f, w * 0.7f, -h * 0.05f, w * 0.88f, h * 0.18f)
                cubicTo(w * 1.0f, h * 0.45f, w * 0.72f, h * 1.0f, w * 0.5f, h * 1.0f)
                cubicTo(w * 0.28f, h * 1.0f, w * 0.0f, h * 0.45f, w * 0.12f, h * 0.18f); close()
            }
            cv.drawPath(path, Ink.fill(Ink.radial(w * 0.4f, h * 0.3f, w * 0.7f, Color.rgb(86, 86, 96), Color.rgb(10, 10, 14))))
            Ink.shine(cv, w * 0.36f, h * 0.24f, w * 0.17f, h * 0.075f, 230, -18f)
        }
    }

    // ---- Kitty --------------------------------------------------------------------------------------

    private fun kitty(c: Canvas, r: Rig) {
        val p = r.p
        for (side in intArrayOf(-1, 1)) {
            r.part(c, side * 0.8f, -1.55f, -0.55f, 0.56f, 0.72f, inPlane = side * (14f + sway(r.t, side * 0.8f, 2.5f)), occlude = true) { cv, w, h ->
                val outer = Path().apply { moveTo(w * 0.05f, h * 0.98f); lineTo(w * 0.5f, 0f); lineTo(w * 0.95f, h * 0.98f); close() }
                cv.drawPath(outer, Ink.fill(Ink.linear(0f, 0f, w, h, Color.rgb(246, 246, 248), Color.rgb(176, 176, 188))))
                val inner = Path().apply { moveTo(w * 0.24f, h * 0.9f); lineTo(w * 0.5f, h * 0.3f); lineTo(w * 0.76f, h * 0.9f); close() }
                cv.drawPath(inner, Ink.fill(Ink.linear(0f, h * 0.3f, 0f, h, Color.rgb(255, 170, 190), Color.rgb(226, 110, 140))))
                cv.drawPath(outer, Ink.stroke(Color.argb(60, 60, 60, 80), w * 0.02f))
            }
        }
        // Blush on the cheeks.
        for (side in intArrayOf(-1, 1)) {
            r.part(c, side * 0.82f, p.noseLy - 0.06f, 0.05f, 0.34f, 0.24f, alpha = 0.9f) { cv, w, h ->
                cv.drawOval(RectF(0f, 0f, w, h), Ink.fill(Ink.radial(w / 2f, h / 2f, w * 0.5f, Color.argb(120, 255, 110, 140), Color.argb(0, 255, 110, 140))))
            }
        }
        // Whiskers fan out from the muzzle.
        val wy = (p.noseLy + p.mouthLy) / 2f
        val ink = Color.argb(215, 245, 245, 250)
        val shadow = Color.argb(80, 20, 20, 30)
        for (side in intArrayOf(-1, 1)) for ((i, dy) in floatArrayOf(-0.12f, 0f, 0.12f).withIndex()) {
            val tip = 1.55f + 0.1f * i
            r.line3(c, side * 0.32f, wy + dy * 0.4f, 0.25f, side * tip, wy + dy * 1.9f - 0.04f, 0.0f, 0.02f, shadow)
            r.line3(c, side * 0.32f, wy + dy * 0.4f - 0.01f, 0.25f, side * tip, wy + dy * 1.9f - 0.05f, 0.0f, 0.012f, ink)
        }
        // Small pink nose.
        r.part(c, 0f, p.noseLy - 0.05f, 0.38f, 0.17f, 0.12f) { cv, w, h ->
            val path = Path().apply { moveTo(w * 0.08f, h * 0.1f); lineTo(w * 0.92f, h * 0.1f); lineTo(w * 0.5f, h * 0.95f); close() }
            cv.drawPath(path, Ink.fill(Ink.radial(w * 0.4f, h * 0.3f, w * 0.7f, Color.rgb(255, 176, 196), Color.rgb(220, 98, 130))))
            Ink.shine(cv, w * 0.38f, h * 0.25f, w * 0.14f, h * 0.07f, 200, -10f)
        }
    }

    // ---- Shades -------------------------------------------------------------------------------------

    private fun shades(c: Canvas, r: Rig) {
        val p = r.p
        val y = p.eyeLy + 0.02f
        // Arms run back from the lenses to the ears.
        for (side in intArrayOf(-1, 1)) {
            r.line3(c, side * 1.02f, y, 0.32f, side * 1.12f, y + 0.02f, -0.9f, 0.075f, Color.rgb(18, 18, 22))
            r.line3(c, side * 1.02f, y - 0.01f, 0.32f, side * 1.12f, y + 0.0f, -0.9f, 0.02f, Color.argb(120, 255, 255, 255))
        }
        // Bridge.
        r.line3(c, -0.12f, y - 0.06f, 0.32f, 0.12f, y - 0.06f, 0.32f, 0.07f, Color.rgb(20, 20, 24))
        // Lenses.
        for (side in intArrayOf(-1, 1)) {
            r.part(c, side * 0.5f, y, 0.3f, 0.44f, 0.34f) { cv, w, h ->
                val rect = RectF(w * 0.03f, h * 0.05f, w * 0.97f, h * 0.95f)
                val rr = h * 0.42f
                cv.drawRoundRect(rect, rr, rr, Ink.fill(Ink.linear(0f, 0f, w, h, Color.rgb(54, 56, 70), Color.rgb(4, 4, 8))))
                cv.drawRoundRect(rect, rr, rr, Ink.stroke(Color.rgb(12, 12, 16), w * 0.05f))
                // reflection streaks
                val streak = Path().apply { moveTo(w * 0.18f, h * 0.18f); lineTo(w * 0.42f, h * 0.18f); lineTo(w * 0.24f, h * 0.62f); lineTo(w * 0.08f, h * 0.62f); close() }
                cv.drawPath(streak, Ink.fill(color = Color.argb(70, 255, 255, 255)))
                val thin = Path().apply { moveTo(w * 0.48f, h * 0.18f); lineTo(w * 0.56f, h * 0.18f); lineTo(w * 0.4f, h * 0.55f); lineTo(w * 0.34f, h * 0.55f); close() }
                cv.drawPath(thin, Ink.fill(color = Color.argb(45, 255, 255, 255)))
            }
        }
    }

    // ---- Hearts -------------------------------------------------------------------------------------

    private fun hearts(c: Canvas, r: Rig) {
        val p = r.p
        val beat = 1f + 0.13f * max(0f, sin(r.t * 6.5f)) // a quick pulse, like a heartbeat
        for (side in intArrayOf(-1, 1)) {
            r.part(c, side * 0.5f, p.eyeLy + 0.02f, 0.30f, 0.46f * beat, 0.46f * beat, inPlane = side * 4f) { cv, w, h ->
                val path = Ink.heart(w, h)
                cv.drawPath(path, Ink.fill(Ink.radial(w * 0.38f, h * 0.3f, w * 0.75f, Color.rgb(255, 120, 150), Color.rgb(200, 14, 56))))
                cv.drawPath(path, Ink.stroke(Color.argb(90, 120, 0, 30), w * 0.025f))
                Ink.shine(cv, w * 0.3f, h * 0.27f, w * 0.12f, h * 0.06f, 230, -40f)
            }
        }
        // A few small hearts drifting up from the head.
        for (i in 0 until 3) {
            val ph = (r.t * 0.55f + i / 3f) % 1f
            val x = (i - 1) * 0.75f + sin(r.t * 2f + i) * 0.12f
            val y = -1.2f - ph * 1.3f
            r.part(c, x, y, 0.1f, 0.2f, 0.2f, alpha = (1f - ph).coerceIn(0f, 1f) * 0.9f) { cv, w, h ->
                cv.drawPath(Ink.heart(w, h), Ink.fill(Ink.radial(w * 0.4f, h * 0.3f, w * 0.7f, Color.rgb(255, 150, 175), Color.rgb(222, 40, 80))))
            }
        }
    }

    // ---- Crown --------------------------------------------------------------------------------------

    private fun crown(c: Canvas, r: Rig) {
        r.part(c, 0f, -1.62f, -0.45f, 0.92f, 0.52f, inPlane = sway(r.t, 0f, 1.5f)) { cv, w, h ->
            val path = Path().apply {
                moveTo(w * 0.04f, h * 0.95f); lineTo(w * 0.0f, h * 0.18f); lineTo(w * 0.25f, h * 0.5f)
                lineTo(w * 0.5f, 0f); lineTo(w * 0.75f, h * 0.5f); lineTo(w * 1.0f, h * 0.18f); lineTo(w * 0.96f, h * 0.95f); close()
            }
            cv.drawPath(path, Ink.fill(Ink.linear(0f, 0f, w, h, Color.rgb(255, 232, 130), Color.rgb(190, 130, 20))))
            cv.drawPath(path, Ink.stroke(Color.rgb(150, 98, 10), w * 0.014f))
            // band
            cv.drawRect(w * 0.04f, h * 0.72f, w * 0.96f, h * 0.95f, Ink.fill(Ink.linear(0f, h * 0.72f, 0f, h * 0.95f, Color.rgb(255, 214, 90), Color.rgb(176, 112, 12))))
            // gems
            val gems = listOf(0.5f to Color.rgb(230, 30, 60), 0.2f to Color.rgb(40, 120, 230), 0.8f to Color.rgb(40, 190, 110))
            for ((gx, col) in gems) {
                val cx = w * gx; val cy = h * 0.83f; val rad = h * 0.075f
                cv.drawCircle(cx, cy, rad, Ink.fill(Ink.radial(cx - rad * 0.3f, cy - rad * 0.3f, rad * 1.4f, Color.WHITE, col)))
            }
            // tip balls
            for (tx in floatArrayOf(0.0f, 0.5f, 1.0f)) {
                val cx = w * tx.coerceIn(0.03f, 0.97f); val cy = if (tx == 0.5f) h * 0.02f else h * 0.17f; val rad = h * 0.065f
                cv.drawCircle(cx, cy, rad, Ink.fill(Ink.radial(cx - rad * 0.3f, cy - rad * 0.3f, rad * 1.5f, Color.rgb(255, 250, 220), Color.rgb(214, 150, 30))))
            }
            Ink.shine(cv, w * 0.3f, h * 0.55f, w * 0.14f, h * 0.05f, 110, -50f)
        }
    }

    // ---- Clown --------------------------------------------------------------------------------------

    private fun clown(c: Canvas, r: Rig) {
        val p = r.p
        val rainbow = intArrayOf(Color.rgb(255, 120, 30), Color.rgb(250, 200, 30), Color.rgb(70, 190, 90), Color.rgb(60, 140, 240))
        for (side in intArrayOf(-1, 1)) for (i in 0 until 3) {
            val y = -1.05f + i * 0.5f
            r.part(c, side * (1.15f + 0.08f * (i % 2)), y, -0.6f, 0.36f, 0.36f, occlude = true) { cv, w, h ->
                val col = rainbow[(i + (if (side > 0) 1 else 0)) % rainbow.size]
                cv.drawCircle(w / 2f, h / 2f, w * 0.5f, Ink.fill(Ink.radial(w * 0.36f, h * 0.32f, w * 0.7f, lighten(col), col)))
            }
        }
        // Rosy cheeks.
        for (side in intArrayOf(-1, 1)) {
            r.part(c, side * 0.78f, p.noseLy - 0.02f, 0.05f, 0.34f, 0.26f, alpha = 0.85f) { cv, w, h ->
                cv.drawOval(RectF(0f, 0f, w, h), Ink.fill(Ink.radial(w / 2f, h / 2f, w * 0.5f, Color.argb(150, 255, 70, 110), Color.argb(0, 255, 70, 110))))
            }
        }
        // Painted stars over the eyes.
        for (side in intArrayOf(-1, 1)) {
            r.part(c, side * 0.5f, p.eyeLy - 0.42f, 0.08f, 0.2f, 0.2f, inPlane = side * 12f) { cv, w, h ->
                val star = Path()
                for (i in 0 until 10) {
                    val ang = -PI / 2 + i * PI / 5
                    val rad = if (i % 2 == 0) w * 0.5f else w * 0.22f
                    val x = (w / 2 + cos(ang) * rad).toFloat(); val y = (h / 2 + sin(ang) * rad).toFloat()
                    if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
                }
                star.close()
                cv.drawPath(star, Ink.fill(Ink.linear(0f, 0f, w, h, Color.rgb(90, 160, 255), Color.rgb(40, 80, 200))))
            }
        }
        // Red ball nose with a shine.
        r.part(c, 0f, p.noseLy - 0.08f, 0.45f, 0.30f, 0.30f) { cv, w, h ->
            cv.drawCircle(w / 2f, h / 2f, w * 0.5f, Ink.fill(Ink.radial(w * 0.36f, h * 0.3f, w * 0.75f, Color.rgb(255, 120, 120), Color.rgb(176, 8, 24))))
            Ink.shine(cv, w * 0.34f, h * 0.26f, w * 0.15f, h * 0.08f, 235, -35f)
        }
    }

    private fun lighten(col: Int): Int = Color.rgb(min(255, Color.red(col) + 90), min(255, Color.green(col) + 90), min(255, Color.blue(col) + 90))

    // ---- Alien --------------------------------------------------------------------------------------

    private fun alien(c: Canvas, r: Rig) {
        val p = r.p
        // Tint the real face shape (taken from the outline), so it follows the cheeks and chin exactly.
        if (p.oval.size > 6) {
            val path = Path()
            p.oval.forEachIndexed { i, pt -> if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y) }
            path.close()
            val b = p.box
            c.drawPath(path, Ink.fill(Ink.radial(p.center.x, p.center.y, p.unit * 1.9f, Color.argb(165, 150, 255, 150), Color.argb(150, 40, 170, 90))))
            c.drawPath(path, Ink.stroke(Color.argb(130, 210, 255, 210), p.unit * 0.025f))
            @Suppress("UNUSED_VARIABLE") val unused = b
        }
        // Antennae with glowing tips.
        for (side in intArrayOf(-1, 1)) {
            val sw = sway(r.t, side * 1.1f, 0.12f) * 0.02f
            r.line3(c, side * 0.35f, -1.15f, -0.4f, side * (0.78f + sw), -2.05f, -0.4f, 0.035f, Color.rgb(60, 150, 80))
            r.part(c, side * (0.78f + sw), -2.12f, -0.4f, 0.2f, 0.2f) { cv, w, h ->
                cv.drawCircle(w / 2f, h / 2f, w * 0.5f, Ink.fill(Ink.radial(w / 2f, h / 2f, w * 0.5f, Color.rgb(240, 255, 200), Color.argb(0, 80, 255, 120))))
                cv.drawCircle(w / 2f, h / 2f, w * 0.2f, Ink.fill(color = Color.WHITE))
            }
        }
        // Big dark almond eyes with a catch-light.
        for (side in intArrayOf(-1, 1)) {
            r.part(c, side * 0.5f, p.eyeLy + 0.04f, 0.2f, 0.6f, 0.42f, inPlane = side * 18f) { cv, w, h ->
                val path = Path().apply {
                    moveTo(w * 0.02f, h * 0.35f)
                    cubicTo(w * 0.3f, -h * 0.05f, w * 0.85f, h * 0.15f, w * 0.98f, h * 0.55f)
                    cubicTo(w * 0.8f, h * 1.0f, w * 0.3f, h * 1.05f, w * 0.02f, h * 0.35f); close()
                }
                cv.drawPath(path, Ink.fill(Ink.radial(w * 0.4f, h * 0.4f, w * 0.7f, Color.rgb(34, 40, 36), Color.rgb(2, 4, 3))))
                Ink.shine(cv, w * 0.36f, h * 0.3f, w * 0.1f, h * 0.075f, 230, -30f)
                Ink.shine(cv, w * 0.6f, h * 0.62f, w * 0.045f, h * 0.035f, 140, 0f)
            }
        }
    }

    // ---- Bunny --------------------------------------------------------------------------------------

    private fun bunny(c: Canvas, r: Rig) {
        val p = r.p
        for (side in intArrayOf(-1, 1)) {
            r.part(c, side * 0.5f, -2.0f, -0.50f, 0.36f, 1.15f, inPlane = side * (6f + sway(r.t, side * 0.9f, 3f)), occlude = true) { cv, w, h ->
                val outer = Path().apply {
                    moveTo(w * 0.2f, h * 1.0f)
                    cubicTo(-w * 0.1f, h * 0.55f, w * 0.05f, 0f, w * 0.5f, 0f)
                    cubicTo(w * 0.95f, 0f, w * 1.1f, h * 0.55f, w * 0.8f, h * 1.0f); close()
                }
                cv.drawPath(outer, Ink.fill(Ink.linear(0f, 0f, w, h, Color.rgb(250, 248, 250), Color.rgb(196, 192, 204))))
                cv.save(); cv.scale(0.58f, 0.86f, w * 0.5f, h * 0.55f)
                cv.drawPath(outer, Ink.fill(Ink.linear(0f, 0f, 0f, h, Color.rgb(255, 190, 210), Color.rgb(240, 130, 164)))); cv.restore()
            }
        }
        r.part(c, 0f, p.noseLy - 0.05f, 0.4f, 0.18f, 0.13f) { cv, w, h ->
            cv.drawOval(RectF(0f, 0f, w, h), Ink.fill(Ink.radial(w * 0.4f, h * 0.3f, w * 0.7f, Color.rgb(255, 188, 206), Color.rgb(224, 112, 144))))
            Ink.shine(cv, w * 0.38f, h * 0.28f, w * 0.14f, h * 0.08f, 200, -15f)
        }
        // Front teeth peek out when the mouth is closed or slightly open.
        r.part(c, 0f, p.mouthLy + 0.1f + p.mouthOpen * 0.1f, 0.2f, 0.16f, 0.2f) { cv, w, h ->
            val teeth = RectF(w * 0.08f, 0f, w * 0.92f, h)
            cv.drawRoundRect(teeth, w * 0.12f, w * 0.12f, Ink.fill(Ink.linear(0f, 0f, 0f, h, Color.WHITE, Color.rgb(222, 222, 230))))
            cv.drawLine(w * 0.5f, 0f, w * 0.5f, h * 0.9f, Ink.stroke(Color.argb(120, 120, 120, 140), w * 0.03f))
        }
        for (side in intArrayOf(-1, 1)) for ((i, dy) in floatArrayOf(-0.08f, 0.06f).withIndex()) {
            r.line3(c, side * 0.3f, (p.noseLy + p.mouthLy) / 2f + dy, 0.2f, side * (1.3f - 0.08f * i), (p.noseLy + p.mouthLy) / 2f + dy * 2.2f, 0f, 0.012f, Color.argb(200, 245, 245, 250))
        }
    }

    // ---- Fox ----------------------------------------------------------------------------------------

    private fun fox(c: Canvas, r: Rig) {
        val p = r.p
        for (side in intArrayOf(-1, 1)) {
            r.part(c, side * 0.8f, -1.55f, -0.55f, 0.58f, 0.78f, inPlane = side * (12f + sway(r.t, side * 0.7f, 2f)), occlude = true) { cv, w, h ->
                val outer = Path().apply { moveTo(w * 0.02f, h * 0.98f); lineTo(w * 0.5f, 0f); lineTo(w * 0.98f, h * 0.98f); close() }
                cv.drawPath(outer, Ink.fill(Ink.linear(0f, 0f, w, h, Color.rgb(246, 140, 44), Color.rgb(196, 88, 14))))
                val inner = Path().apply { moveTo(w * 0.26f, h * 0.92f); lineTo(w * 0.5f, h * 0.34f); lineTo(w * 0.74f, h * 0.92f); close() }
                cv.drawPath(inner, Ink.fill(color = Color.rgb(38, 28, 24)))
                // dark tip
                val tip = Path().apply { moveTo(w * 0.36f, h * 0.3f); lineTo(w * 0.5f, 0f); lineTo(w * 0.64f, h * 0.3f); close() }
                cv.drawPath(tip, Ink.fill(color = Color.rgb(48, 30, 20)))
            }
        }
        // White cheeks and muzzle.
        for (side in intArrayOf(-1, 1)) {
            r.part(c, side * 0.5f, p.noseLy + 0.0f, 0.08f, 0.44f, 0.30f, inPlane = side * 18f, alpha = 0.9f) { cv, w, h ->
                val path = Path().apply { moveTo(w * 0.95f, h * 0.1f); lineTo(w * 0.05f, h * 0.95f); lineTo(w * 0.95f, h * 0.95f); close() }
                cv.save(); if (side > 0) cv.scale(-1f, 1f, w / 2f, 0f)
                cv.drawPath(path, Ink.fill(Ink.linear(0f, 0f, w, h, Color.argb(245, 255, 252, 246), Color.argb(200, 240, 226, 206)))); cv.restore()
            }
        }
        r.part(c, 0f, p.noseLy - 0.04f, 0.4f, 0.22f, 0.16f) { cv, w, h ->
            val path = Path().apply { moveTo(w * 0.05f, h * 0.15f); cubicTo(w * 0.3f, -h * 0.05f, w * 0.7f, -h * 0.05f, w * 0.95f, h * 0.15f); cubicTo(w * 0.9f, h * 0.6f, w * 0.65f, h, w * 0.5f, h); cubicTo(w * 0.35f, h, w * 0.1f, h * 0.6f, w * 0.05f, h * 0.15f); close() }
            cv.drawPath(path, Ink.fill(Ink.radial(w * 0.4f, h * 0.3f, w * 0.8f, Color.rgb(70, 62, 66), Color.rgb(8, 8, 10))))
            Ink.shine(cv, w * 0.36f, h * 0.26f, w * 0.15f, h * 0.07f, 220, -15f)
        }
        for (side in intArrayOf(-1, 1)) for ((i, dy) in floatArrayOf(-0.1f, 0.05f).withIndex()) {
            r.line3(c, side * 0.45f, (p.noseLy + p.mouthLy) / 2f + dy, 0.2f, side * (1.35f - 0.08f * i), (p.noseLy + p.mouthLy) / 2f + dy * 2f, 0f, 0.012f, Color.argb(190, 40, 30, 30))
        }
    }
}
