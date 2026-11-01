package com.hinterlay.app.ui.ar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hinterlay.app.ui.theme.HinterlayTheme

/**
 * DEBUG-ONLY host for [ArVideoScreen], so the AR screen can be reached before it is wired into
 * MainActivity's navigation (that routing is owned by the radial-menu work). This class lives in
 * `src/debug/` and is never part of a release build.
 *
 * Launch it from a connected device:
 * ```
 * adb shell am start -n com.hinterlay.app.debug/com.hinterlay.app.ui.ar.ArDebugActivity
 * ```
 */
class ArDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HinterlayTheme {
                ArVideoScreen(onBack = { finish() })
            }
        }
    }
}
