package com.hinterlay.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * Local-only "ad-free session" credits. One credit = the next app launch has no ads.
 * Everything lives in SharedPreferences on this device.
 */
object AdFree {
    private const val PREFS = "hinterlay_rewards"
    private const val KEY_CREDITS = "ad_free_credits"
    private const val KEY_LIFETIME = "lifetime_ad_free"

    private lateinit var appContext: Context
    private var started = false

    private val _sessionActive = MutableStateFlow(false)

    /** True when this app session is ad-free. Checked by every ad entry point. */
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private val _credits = MutableStateFlow(0)

    /** Ad-free sessions banked for future launches. */
    val credits: StateFlow<Int> = _credits.asStateFlow()

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Call once per process start: spends one credit (if any) to make this session ad-free. */
    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        _lifetime.value = prefs().getBoolean(KEY_LIFETIME, false)
        var credits = prefs().getInt(KEY_CREDITS, 0)
        if (_lifetime.value) {
            _sessionActive.value = true // supporters never spend a credit
        } else if (credits > 0) {
            credits--
            prefs().edit().putInt(KEY_CREDITS, credits).apply()
            _sessionActive.value = true
        }
        _credits.value = credits
    }

    private val _lifetime = MutableStateFlow(false)

    /** True once the user bought the lifetime supporter pack: no ads, ever. */
    val lifetime: StateFlow<Boolean> = _lifetime.asStateFlow()

    /** Called by billing when the lifetime purchase is confirmed (or restored). */
    fun setLifetime(value: Boolean) {
        prefs().edit().putBoolean(KEY_LIFETIME, value).apply()
        _lifetime.value = value
        if (value) _sessionActive.value = true
    }

    fun grant(count: Int = 1) {
        val total = prefs().getInt(KEY_CREDITS, 0) + count
        prefs().edit().putInt(KEY_CREDITS, total).apply()
        _credits.value = total
    }
}

/**
 * Invite flow. The reward rules are isolated behind [ReferralBackend] so a verified
 * implementation (server confirms the friend made their first photo) can replace
 * [LocalReferralBackend] without touching any UI.
 */
interface ReferralBackend {
    /** Called right after the user opened the share sheet with their invite. */
    fun onInviteShared(code: String)
}

/**
 * On-device stand-in: there is no server, so it cannot know whether the friend installed the
 * app. It grants 1 credit per share, at most once every 24h, which limits abuse but does not
 * prevent it.
 */
object LocalReferralBackend : ReferralBackend {
    private const val KEY_LAST_GRANT = "last_share_grant"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    lateinit var context: Context

    override fun onInviteShared(code: String) {
        val prefs = context.getSharedPreferences("hinterlay_rewards", Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_GRANT, 0L)
        val now = System.currentTimeMillis()
        if (now - last >= DAY_MS) {
            prefs.edit().putLong(KEY_LAST_GRANT, now).apply()
            AdFree.grant(1)
        }
    }
}

object Referral {
    var backend: ReferralBackend = LocalReferralBackend

    fun init(context: Context) {
        LocalReferralBackend.context = context.applicationContext
    }

    fun inviteCode(context: Context): String {
        val prefs = context.getSharedPreferences("hinterlay_rewards", Context.MODE_PRIVATE)
        return prefs.getString("invite_code", null) ?: UUID.randomUUID().toString()
            .replace("-", "").take(8).uppercase().also { prefs.edit().putString("invite_code", it).apply() }
    }

    /** Returns the chooser for sharing [image] with an invite message, and records the share. */
    fun shareIntent(context: Context, image: File): Intent {
        val code = inviteCode(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", image)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_TEXT,
                "Made with Hinterlay - put text behind anything. Get it and use my invite code $code",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        backend.onInviteShared(code)
        com.hinterlay.app.cloud.Track.event("share_tapped")
        return Intent.createChooser(send, "Share & get an ad-free session")
    }
}
