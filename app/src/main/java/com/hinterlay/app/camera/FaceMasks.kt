package com.hinterlay.app.camera

import android.graphics.Canvas
import com.google.mlkit.vision.face.Face

/**
 * The face masks. They are drawn by [Masks3D] in the head's own 3D space from the face outline, so they turn, tilt
 * and follow the mouth. Use [FaceSmoother] for live video, and the scan animation in [FaceMesh] when a face is first found.
 */
enum class FaceMask(val label: String, val emoji: String) {
    NONE("None", "🚫"), DOG("Puppy", "🐶"), CAT("Kitty", "🐱"), BUNNY("Bunny", "🐰"), FOX("Fox", "🦊"),
    SHADES("Shades", "😎"), HEARTS("Hearts", "😍"), CROWN("Royal", "👑"), CLOWN("Clown", "🤡"), ALIEN("Alien", "👽");
}

object FaceMasks {
    /** Draws [mask] on one detected [face], settled (fully landed, mid-animation time). Used for saved photos. */
    fun draw(c: Canvas, face: Face, mask: FaceMask) {
        if (mask == FaceMask.NONE) return
        Masks3D.draw(c, FacePose.from(face, 0), mask, t = 0.6f, appear = 1f)
    }
}
