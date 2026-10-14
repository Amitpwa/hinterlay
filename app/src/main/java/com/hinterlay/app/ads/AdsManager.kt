package com.hinterlay.app.ads

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.hinterlay.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

object AdConfig {
    val BANNER_ID: String = BuildConfig.AD_BANNER_ID
    val INTERSTITIAL_ID: String = BuildConfig.AD_INTERSTITIAL_ID
    val REWARDED_ID: String = BuildConfig.AD_REWARDED_ID

    /**
     * Minimum gap between "start editing" interstitials. Google discourages showing
     * interstitials after every user action; a cap keeps the app policy-friendly.
     */
    const val INTERSTITIAL_MIN_INTERVAL_MS = 90_000L
}

/**
 * Loads and shows full-screen ads. Rules this class follows:
 *  - Nothing is requested before consent allows it (UMP canRequestAds).
 *  - Ads are only shown at natural breaks the user triggered (picking a photo, tapping Export).
 *  - If an ad is not available, the user is never blocked: the action simply continues.
 */
object AdsManager {
    private const val TAG = "AdsManager"

    private val initStarted = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var appContext: Context

    private val _canRequestAds = MutableStateFlow(false)
    val canRequestAds: StateFlow<Boolean> = _canRequestAds.asStateFlow()

    private var interstitial: InterstitialAd? = null
    private val _interstitialReady = MutableStateFlow(false)
    val interstitialReady: StateFlow<Boolean> = _interstitialReady.asStateFlow()
    private var interstitialLoading = false
    private var interstitialRetries = 0
    private var lastInterstitialShownAt = 0L

    private var rewarded: RewardedAd? = null
    private var rewardedLoading = false
    private var rewardedRetries = 0

    fun initializeIfAllowed(context: Context, consent: ConsentManager) {
        if (!consent.canRequestAds) return
        if (!initStarted.compareAndSet(false, true)) return
        appContext = context.applicationContext
        scope.launch {
            // Owner's own phones: real ad units, but they receive "Test Ad" creatives, so tapping can never count as invalid traffic.
            val testIds = com.hinterlay.app.BuildConfig.ADMOB_TEST_DEVICE_IDS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (testIds.isNotEmpty()) {
                MobileAds.setRequestConfiguration(
                    com.google.android.gms.ads.RequestConfiguration.Builder().setTestDeviceIds(testIds).build()
                )
            }
            withContext(Dispatchers.IO) { MobileAds.initialize(appContext) }
            _canRequestAds.value = true
            loadInterstitial()
            loadRewarded()
        }
    }

    private fun backoff(retries: Int): Long = min(60_000L, 5_000L shl min(retries, 4))

    private fun loadInterstitial() {
        if (!_canRequestAds.value || interstitial != null || interstitialLoading) return
        interstitialLoading = true
        InterstitialAd.load(appContext, AdConfig.INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialLoading = false
                    interstitialRetries = 0
                    interstitial = ad
                    _interstitialReady.value = true
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialLoading = false
                    Log.w(TAG, "Interstitial failed: ${error.message}")
                    scope.launch { delay(backoff(interstitialRetries++)); loadInterstitial() }
                }
            })
    }

    private fun loadRewarded() {
        if (!_canRequestAds.value || rewarded != null || rewardedLoading) return
        rewardedLoading = true
        RewardedAd.load(appContext, AdConfig.REWARDED_ID, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedLoading = false
                    rewardedRetries = 0
                    rewarded = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedLoading = false
                    Log.w(TAG, "Rewarded failed: ${error.message}")
                    scope.launch { delay(backoff(rewardedRetries++)); loadRewarded() }
                }
            })
    }

    /** Interstitial shown when an editing session starts. Returns false if nothing was shown. */
    fun showStartInterstitial(activity: Activity, onClosed: () -> Unit = {}): Boolean {
        if (com.hinterlay.app.data.AdFree.sessionActive.value) return false
        val ad = interstitial ?: return false
        val now = SystemClock.elapsedRealtime()
        if (lastInterstitialShownAt != 0L && now - lastInterstitialShownAt < AdConfig.INTERSTITIAL_MIN_INTERVAL_MS) {
            return false
        }
        interstitial = null
        _interstitialReady.value = false
        lastInterstitialShownAt = now
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                loadInterstitial(); onClosed()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                loadInterstitial(); onClosed()
            }
        }
        ad.show(activity)
        return true
    }

    /**
     * Rewarded ad that unlocks an export. [onResult] gets true when the user earned the
     * reward (or the ad failed to show, so we don't punish the user), false when they
     * closed it early. Returns false immediately if no ad was ready - continue without one.
     */
    fun showRewarded(activity: Activity, onResult: (earned: Boolean) -> Unit): Boolean {
        if (com.hinterlay.app.data.AdFree.sessionActive.value) return false
        val ad = rewarded ?: run { loadRewarded(); return false }
        rewarded = null
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                loadRewarded(); onResult(earned)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                loadRewarded(); onResult(true)
            }
        }
        ad.show(activity) { earned = true }
        return true
    }
}
