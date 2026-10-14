package com.hinterlay.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Removes a small blemish or object: the painted area is filled from a nearby patch that matches what surrounds
 * it, shifted in colour to blend, with a soft edge. Works best on small areas of a fairly even background (skin,
 * sky, walls, grass). Large objects on busy backgrounds will show repeated texture.
 */
object Healer {

    /** Three-pass box blur of a float field (close to Gaussian). Edges repeat. */
    private fun blurF(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        var a = src
        repeat(3) {
            val tmp = FloatArray(w * h)
            val inv = 1f / (2 * radius + 1)
            for (y in 0 until h) {
                var s = 0f
                for (k in -radius..radius) s += a[y * w + k.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[y * w + x] = s * inv
                    s += a[y * w + min(w - 1, x + radius + 1)] - a[y * w + max(0, x - radius)]
                }
            }
            val out = FloatArray(w * h)
            for (x in 0 until w) {
                var s = 0f
                for (k in -radius..radius) s += tmp[k.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    out[y * w + x] = s * inv
                    s += tmp[min(h - 1, y + radius + 1) * w + x] - tmp[max(0, y - radius) * w + x]
                }
            }
            a = out
        }
        return a
    }

    /** Returns a new bitmap; [src] is untouched. [pts] is x0, y0, x1, y1... as 0..1 of the photo, [radiusRel] a fraction of the longer side. */
    fun heal(src: Bitmap, pts: List<Float>, radiusRel: Float): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        if (pts.size < 2) return out
        val r = max(3f, radiusRel * max(w, h))

        // Box around the stroke, with room for the soft edge.
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = 0f; var maxY = 0f
        for (i in pts.indices step 2) {
            minX = min(minX, pts[i] * w); maxX = max(maxX, pts[i] * w)
            minY = min(minY, pts[i + 1] * h); maxY = max(maxY, pts[i + 1] * h)
        }
        val grow = (r * 2.2f).toInt() + 2
        val bx0 = max(0, minX.toInt() - grow); val bx1 = min(w - 1, maxX.toInt() + grow)
        val by0 = max(0, minY.toInt() - grow); val by1 = min(h - 1, maxY.toInt() + grow)
        val bw = bx1 - bx0 + 1
        val bh = by1 - by0 + 1
        if (bw < 4 || bh < 4) return out

        // The painted area as a mask over the box.
        val maskBmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            color = android.graphics.Color.BLACK; strokeWidth = 2f * r
        }
        val path = Path()
        path.moveTo(pts[0] * w - bx0, pts[1] * h - by0)
        if (pts.size == 2) path.lineTo(pts[0] * w - bx0 + 0.01f, pts[1] * h - by0)
        else for (i in 2 until pts.size step 2) path.lineTo(pts[i] * w - bx0, pts[i + 1] * h - by0)
        Canvas(maskBmp).drawPath(path, paint)
        val rb = maskBmp.rowBytes
        val bytes = ByteArray(rb * bh)
        maskBmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(bytes))
        maskBmp.recycle()
        val core = FloatArray(bw * bh) { (bytes[(it / bw) * rb + it % bw].toInt() and 255) / 255f }

        // Soft edge, and the ring of pixels just outside the painted area (what the fill has to match).
        fun gray(f: FloatArray) = IntArray(f.size) { val a = (f[it] * 255f).toInt(); (255 shl 24) or (a shl 16) or (a shl 8) or a }
        val feather = PhotoFx.blur(gray(core), bw, bh, max(2, (r * 0.55f).toInt()))
        val wide = PhotoFx.blur(gray(core), bw, bh, max(2, (r * 0.7f).toInt()))
        val ring = ArrayList<Int>()
        for (j in core.indices) if (core[j] < 0.05f && ((wide[j] shr 16) and 255) > 6) ring.add(j)
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        fun at(x: Int, y: Int) = px[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        fun ch(c: Int, s: Int) = (c shr s) and 255

        // Choose the shift whose neighbourhood looks most like the ring around the painted area.
        val coreSample = ArrayList<Int>()
        // Painted area and its soft edge: all of it must have a real, in-photo source (no stretched border pixels).
        for (j in core.indices step 3) if (core[j] > 0.5f || ((feather[j] shr 16) and 255) > 24) coreSample.add(j)
        val ringSample = ring.filterIndexed { i, _ -> i % max(1, ring.size / 400) == 0 }
        var best: IntArray? = null
        var bestCost = Double.MAX_VALUE
        for (dist in floatArrayOf(2.4f, 3.4f, 4.8f)) for (k in 0 until 16) {
            val ang = k * Math.PI / 8.0
            val ox = (cos(ang) * dist * r).toInt()
            val oy = (sin(ang) * dist * r).toInt()
            // The shifted copy of the painted area must stay inside the photo and outside the painted area itself.
            var ok = true
            for (j in coreSample) {
                val x = bx0 + j % bw + ox
                val y = by0 + j / bw + oy
                if (x < 0 || y < 0 || x >= w || y >= h) { ok = false; break }
                val lx = x - bx0; val ly = y - by0
                if (lx in 0 until bw && ly in 0 until bh && core[ly * bw + lx] > 0.05f) { ok = false; break }
            }
            if (!ok) continue
            var cost = 0.0
            var n = 0
            for (j in ringSample) {
                val x = bx0 + j % bw
                val y = by0 + j / bw
                val a = px[y * w + x]
                val b = at(x + ox, y + oy)
                val dr = ch(a, 16) - ch(b, 16); val dg = ch(a, 8) - ch(b, 8); val db = ch(a, 0) - ch(b, 0)
                cost += dr * dr + dg * dg + db * db
                n++
            }
            if (n > 0 && cost / n < bestCost) { bestCost = cost / n; best = intArrayOf(ox, oy) }
        }

        // Colour shift so the patch blends with its new surroundings. It is not one number: the difference between the
        // photo and the patch is measured all round the painted area and spread inwards, so a lighting gradient
        // (a wall lit from one side, a sky getting darker) carries through the patch instead of showing a seam.
        var meanR = 0f; var meanG = 0f; var meanB = 0f
        for (j in ringSample) {
            val a = px[(by0 + j / bw) * w + bx0 + j % bw]
            meanR += ch(a, 16); meanG += ch(a, 8); meanB += ch(a, 0)
        }
        val n = max(1, ringSample.size)
        meanR /= n; meanG /= n; meanB /= n
        val corrR = FloatArray(bw * bh); val corrG = FloatArray(bw * bh); val corrB = FloatArray(bw * bh)
        if (best != null) {
            val wt = FloatArray(bw * bh)
            val dr = FloatArray(bw * bh); val dg = FloatArray(bw * bh); val db = FloatArray(bw * bh)
            for (j in ring) {
                val x = bx0 + j % bw; val y = by0 + j / bw
                val a = px[y * w + x]
                val b = at(x + best[0], y + best[1])
                wt[j] = 1f
                dr[j] = (ch(a, 16) - ch(b, 16)).toFloat(); dg[j] = (ch(a, 8) - ch(b, 8)).toFloat(); db[j] = (ch(a, 0) - ch(b, 0)).toFloat()
            }
            val rad = max(2, (r * 1.2f).toInt())
            val den = blurF(wt, bw, bh, rad)
            val nr = blurF(dr, bw, bh, rad); val ng = blurF(dg, bw, bh, rad); val nb = blurF(db, bw, bh, rad)
            for (j in corrR.indices) {
                val d = den[j]
                if (d > 0.0005f) { corrR[j] = nr[j] / d; corrG[j] = ng[j] / d; corrB[j] = nb[j] / d }
            }
        }

        val region = IntArray(bw * bh)
        for (j in region.indices) {
            val x = bx0 + j % bw
            val y = by0 + j / bw
            // Fully opaque only well inside the painted area; fades out over about half a brush radius, so there is no outline.
            val a = (((feather[j] shr 16) and 255) / 255f * 1.7f).coerceIn(0f, 1f)
            val own = px[y * w + x]
            if (a <= 0.004f) { region[j] = own; continue }
            val fr: Float; val fg: Float; val fb: Float
            if (best != null) {
                val s = at(x + best[0], y + best[1])
                fr = ch(s, 16) + corrR[j]; fg = ch(s, 8) + corrG[j]; fb = ch(s, 0) + corrB[j]
            } else { fr = meanR; fg = meanG; fb = meanB } // no good patch: plain average of the surroundings
            val rr = (ch(own, 16) * (1f - a) + fr * a).coerceIn(0f, 255f)
            val gg = (ch(own, 8) * (1f - a) + fg * a).coerceIn(0f, 255f)
            val bb = (ch(own, 0) * (1f - a) + fb * a).coerceIn(0f, 255f)
            region[j] = (own and (0xFF shl 24)) or (rr.toInt() shl 16) or (gg.toInt() shl 8) or bb.toInt()
        }
        out.setPixels(region, 0, bw, bx0, by0, bw, bh)
        return out
    }
}
