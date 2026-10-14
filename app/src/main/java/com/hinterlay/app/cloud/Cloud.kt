package com.hinterlay.app.cloud

import android.content.Context
import android.os.Build
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.hinterlay.app.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Entry point for everything backed by Firebase. The app is fully usable without it: until
 * app/google-services.json is added, [available] is false and every call here is a no-op.
 */
object Cloud {
    var available = false
        private set
    private lateinit var appContext: Context

    private fun prefs() = appContext.getSharedPreferences("hinterlay_cloud", Context.MODE_PRIVATE)

    /** Set once the user has accepted the privacy policy. Nothing is collected before that. */
    var privacyAccepted: Boolean
        get() = prefs().getBoolean("privacy_accepted", false)
        set(value) { prefs().edit().putBoolean("privacy_accepted", value).apply() }

    /** Usage statistics (More > Share anonymous usage data). On by default once the policy is accepted. */
    var analyticsEnabled: Boolean
        get() = privacyAccepted && prefs().getBoolean("analytics", true)
        set(value) {
            prefs().edit().putBoolean("analytics", value).apply()
            if (available) FirebaseAnalytics.getInstance(appContext).setAnalyticsCollectionEnabled(analyticsEnabled)
        }

    fun init(context: Context) {
        appContext = context.applicationContext
        available = FirebaseApp.getApps(appContext).isNotEmpty()
        Push.init(appContext) // settings screens read this even when Firebase isn't configured
        if (!available) return
        FirebaseAnalytics.getInstance(appContext).setAnalyticsCollectionEnabled(analyticsEnabled)
        if (analyticsEnabled) UserStats.recordOpen(appContext)
    }
}

/** One place to log a product event; it feeds Firebase Analytics and the owner dashboard counters. */
object Track {
    fun event(name: String, vararg params: Pair<String, Any>) {
        if (!Cloud.available || !Cloud.analyticsEnabled) return
        val ctx = FirebaseApp.getInstance().applicationContext
        val bundle = Bundle().apply {
            params.forEach { (k, v) ->
                when (v) {
                    is Int -> putLong(k, v.toLong())
                    is Long -> putLong(k, v)
                    is Double -> putDouble(k, v)
                    is Float -> putDouble(k, v.toDouble())
                    else -> putString(k, v.toString())
                }
            }
        }
        FirebaseAnalytics.getInstance(ctx).logEvent(name, bundle)
        UserStats.bump(name)
        Insights.event(name)
    }
}

/**
 * Anonymous, PII-free usage counters for the owner dashboard: one random install id (the
 * anonymous auth uid) and daily totals. Uninstalls are not measurable from inside the app;
 * read them from Firebase Analytics (app_remove) and Google Play Console.
 */
object UserStats {
    private val counters = mapOf(
        "export_completed" to "exports",
        "sticker_created" to "stickers",
        "wallpaper_set" to "wallpapers",
        "share_tapped" to "shares",
        "inspiration_used" to "inspirationUses",
        "purchase_completed" to "purchases",
    )

    private fun day(): String = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun withUid(block: (String) -> Unit) {
        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid
        if (uid != null) block(uid) else auth.signInAnonymously().addOnSuccessListener { r -> r.user?.uid?.let(block) }
    }

    fun recordOpen(context: Context) = withUid { uid ->
        val db = FirebaseFirestore.getInstance()
        val ref = db.collection("users").document(uid)
        val stats = db.collection("stats").document(day())
        ref.get().addOnSuccessListener { snap ->
            val common = mapOf(
                "lastSeen" to FieldValue.serverTimestamp(),
                "appVersion" to BuildConfig.VERSION_NAME,
                "sdk" to Build.VERSION.SDK_INT,
                "locale" to Locale.getDefault().toLanguageTag(),
                "opens" to FieldValue.increment(1),
            )
            if (snap.exists()) {
                ref.update(common)
            } else {
                ref.set(common + ("firstSeen" to FieldValue.serverTimestamp()))
                stats.set(mapOf("newUsers" to FieldValue.increment(1)), com.google.firebase.firestore.SetOptions.merge())
            }
            stats.set(mapOf("opens" to FieldValue.increment(1)), com.google.firebase.firestore.SetOptions.merge())
        }
    }

    fun bump(event: String) {
        val field = counters[event] ?: return
        withUid {
            FirebaseFirestore.getInstance().collection("stats").document(day())
                .set(mapOf(field to FieldValue.increment(1)), com.google.firebase.firestore.SetOptions.merge())
        }
    }
}

/** Push opt-in state and the FCM topic every subscriber joins. */
object Push {
    const val TOPIC = "all"
    private lateinit var appContext: Context
    private fun prefs() = appContext.getSharedPreferences("hinterlay_cloud", Context.MODE_PRIVATE)

    fun init(context: Context) {
        appContext = context.applicationContext
        if (enabled) subscribe(true)
    }

    /** True once the user has accepted push (and we hold a topic subscription). */
    var enabled: Boolean
        get() = prefs().getBoolean("push_enabled", false)
        set(value) {
            prefs().edit().putBoolean("push_enabled", value).putBoolean("push_asked", true).apply()
            subscribe(value)
        }

    val asked: Boolean get() = prefs().getBoolean("push_asked", false)

    fun markAsked() = prefs().edit().putBoolean("push_asked", true).apply()

    private fun subscribe(on: Boolean) {
        if (!Cloud.available) return
        val fm = FirebaseMessaging.getInstance()
        if (on) fm.subscribeToTopic(TOPIC) else fm.unsubscribeFromTopic(TOPIC)
    }
}
