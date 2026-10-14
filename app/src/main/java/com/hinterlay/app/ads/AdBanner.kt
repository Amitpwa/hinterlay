package com.hinterlay.app.ads

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/** A standard banner placed beside content (never on top of it). */
@Composable
fun AdBanner(modifier: Modifier = Modifier, size: AdSize = AdSize.MEDIUM_RECTANGLE) {
    val allowed by AdsManager.canRequestAds.collectAsState()
    val adFree by com.hinterlay.app.data.AdFree.sessionActive.collectAsState()
    if (!allowed || adFree) return
    val context = LocalContext.current
    val adView = remember {
        AdView(context).apply {
            setAdSize(size)
            adUnitId = AdConfig.BANNER_ID
            loadAd(AdRequest.Builder().build())
        }
    }
    DisposableEffect(adView) { onDispose { adView.destroy() } }
    AndroidView(factory = { adView }, modifier = modifier.size(size.width.dp, size.height.dp))
}
