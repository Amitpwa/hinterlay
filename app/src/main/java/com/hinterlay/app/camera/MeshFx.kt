package com.hinterlay.app.camera

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import kotlinx.coroutines.tasks.await

/** ML Kit's 468-point 3D face mesh (on the phone). It refines the pose and gives the scan a real triangulated mesh. */
object MeshFx {
    /** Triangles as point indices (3 per triangle), the same for every face. Filled by the first detection. */
    @Volatile var triangles: IntArray = IntArray(0)
        private set

    private const val LEFT_EDGE = 234
    private const val RIGHT_EDGE = 454

    /** Adds the mesh to each pose it matches (by overlap of boxes). Poses and [photo] share the same pixel scale. */
    suspend fun attach(photo: Bitmap, poses: List<FacePose>) {
        if (poses.isEmpty()) return
        val detector = FaceMeshDetection.getClient(FaceMeshDetectorOptions.Builder().setUseCase(FaceMeshDetectorOptions.FACE_MESH).build())
        try {
            val meshes = detector.process(InputImage.fromBitmap(photo, 0)).await()
            for (m in meshes) {
                val pts = m.allPoints
                if (pts.size < 468) continue
                if (triangles.isEmpty()) {
                    val t = m.allTriangles
                    triangles = IntArray(t.size * 3) { t[it / 3].allPoints[it % 3].index }
                }
                val flat = FloatArray(pts.size * 3)
                pts.forEach { flat[it.index * 3] = it.position.x; flat[it.index * 3 + 1] = it.position.y; flat[it.index * 3 + 2] = it.position.z }
                val box = RectF(m.boundingBox)
                val match = poses.maxByOrNull { overlap(it.box, box) } ?: continue
                if (overlap(match.box, box) < 0.3f) continue
                match.mesh = flat
                Log.d("MeshFx", "face ${match.id}: kitYaw=${match.yaw} meshYaw=${meshYaw(flat)} tris=${triangles.size / 3}")
            }
        } catch (e: Exception) {
            Log.w("MeshFx", "mesh failed: ${e.message}")
        } finally {
            detector.close()
        }
    }

    /** Head turn from the depth difference between the two cheek edges, in degrees. */
    fun meshYaw(m: FloatArray): Float {
        val dx = m[RIGHT_EDGE * 3] - m[LEFT_EDGE * 3]
        val dz = m[RIGHT_EDGE * 3 + 2] - m[LEFT_EDGE * 3 + 2]
        return Math.toDegrees(Math.atan2(dz.toDouble(), dx.toDouble())).toFloat()
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val l = maxOf(a.left, b.left); val r = minOf(a.right, b.right); val t = maxOf(a.top, b.top); val bo = minOf(a.bottom, b.bottom)
        if (r <= l || bo <= t) return 0f
        val inter = (r - l) * (bo - t)
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }
}
