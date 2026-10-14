package com.hinterlay.app.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await

/** Face masks on a still photo: finds the faces once, then draws a mask on each. All of it runs on the phone. */
object FaceFx {

    /** Finds every face in [photo], with the outline points the masks need. Returns an empty list if none. */
    suspend fun detect(photo: Bitmap): List<FacePose> {
        // Faces in a photo are found at a reduced size (faster, and plenty accurate), then scaled back up.
        val k = minOf(1f, 1280f / maxOf(photo.width, photo.height))
        val small = if (k < 1f) Bitmap.createScaledBitmap(photo, (photo.width * k).toInt().coerceAtLeast(1), (photo.height * k).toInt().coerceAtLeast(1), true) else photo
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setMinFaceSize(0.08f)
                .build()
        )
        return try {
            val faces = detector.process(InputImage.fromBitmap(small, 0)).await()
            val poses = faces.mapIndexed { i, f -> FacePose.from(f, i) }
            MeshFx.attach(small, poses)
            if (k < 1f) poses.map { it.scaled(1f / k) } else poses
        } catch (e: Exception) {
            emptyList()
        } finally {
            detector.close()
            if (small !== photo) small.recycle()
        }
    }

    /** A copy of [photo] with [mask] drawn on every face in [poses]. */
    fun apply(photo: Bitmap, poses: List<FacePose>, mask: FaceMask): Bitmap {
        val out = photo.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        poses.forEach { Masks3D.draw(c, it, mask, t = SETTLED_TIME, appear = 1f) }
        return out
    }

    /** The point in the masks' idle animation used for saved photos (ears mid-swing, not at an extreme). */
    const val SETTLED_TIME = 0.6f
}
