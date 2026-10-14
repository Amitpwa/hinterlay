package com.hinterlay.app.cloud

import android.app.Application
import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.hinterlay.app.BuildConfig
import com.microsoft.clarity.Clarity
import com.microsoft.clarity.ClarityConfig

/**
 * User-behaviour analytics in one place: Google Analytics (via Firebase) for events and screens, and
 * Microsoft Clarity for session replays and heatmaps.
 *
 * Privacy rules enforced here:
 *  - everything follows the "Share anonymous usage data" switch in More;
 *  - Clarity is PAUSED on every screen that shows a user's photo (editor, crop, camera, previews), so
 *    photos are never recorded or uploaded;
 *  - no names, emails or typed text are attached to any event.
 */
object Insights {
    private var clarityStarted = false
    private var photoScreen = false

    /** Screens that may show a user's photo. Clarity must not run on them. */
    private val photoScreens = setOf("editor", "camera", "quick_camera")

    fun init(app: Application) {
        if (!Cloud.analyticsEnabled) return
        startClarity(app)
    }

    private fun startClarity(app: Application) {
        if (clarityStarted || BuildConfig.CLARITY_PROJECT_ID.isBlank()) return
        runCatching {
            Clarity.initialize(app, ClarityConfig(BuildConfig.CLARITY_PROJECT_ID))
            clarityStarted = true
            Clarity.pause() // start paused; screen() resumes it on safe screens
        }
    }

    /** Called when the user turns usage data on or off. */
    fun setEnabled(context: Context, on: Boolean) {
        if (on) (context.applicationContext as? Application)?.let { startClarity(it) }
        if (clarityStarted) runCatching { if (on && !photoScreen) Clarity.resume() else Clarity.pause() }
    }

    /** Tells both tools which screen the user is on. Safe to call on every change. */
    fun screen(name: String) {
        photoScreen = name in photoScreens
        if (clarityStarted) runCatching {
            if (photoScreen || !Cloud.analyticsEnabled) Clarity.pause() else Clarity.resume()
            Clarity.setCurrentScreenName(name)
        }
        if (Cloud.available && Cloud.analyticsEnabled) {
            val ctx = FirebaseApp.getInstance().applicationContext
            val b = Bundle().apply {
                putString(FirebaseAnalytics.Param.SCREEN_NAME, name)
                putString(FirebaseAnalytics.Param.SCREEN_CLASS, "MainActivity")
            }
            FirebaseAnalytics.getInstance(ctx).logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, b)
        }
    }

    /** Mirrors a product event into Clarity so it can be filtered in session replays. */
    fun event(name: String) {
        if (clarityStarted && !photoScreen && Cloud.analyticsEnabled) runCatching { Clarity.sendCustomEvent(name) }
    }
}
