package com.hinterlay.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/** Everything the "stamp" capsules print on a photo. Location is used on the phone only to name the place. */
object Place {
    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun dayName(): String = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
    fun dateText(): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date())
    fun timeText(): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

    /** City or area name of the current position, or null when unknown. Needs the location permission. */
    @Suppress("MissingPermission")
    suspend fun name(context: Context): String? = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        var loc: Location? = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (loc == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && providers.isNotEmpty()) {
            loc = withTimeoutOrNull(6000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = android.os.CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(providers.first(), signal, context.mainExecutor) { cont.resume(it) }
                }
            }
        }
        val l = loc ?: return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            val a = Geocoder(context, Locale.getDefault()).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()
            a?.locality ?: a?.subAdminArea ?: a?.adminArea ?: a?.countryName
        }.getOrNull()
    }
}
