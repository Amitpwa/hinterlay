package com.hinterlay.app.ads

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform

/**
 * Google User Messaging Platform (a Google-certified CMP). Required by Google's EU User
 * Consent Policy for EEA/UK/Switzerland users and supports US state privacy regulations.
 * Configure the messages in AdMob > Privacy & messaging.
 */
class ConsentManager(context: Context) {

    private val info: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)

    val canRequestAds: Boolean get() = info.canRequestAds()

    val isPrivacyOptionsRequired: Boolean
        get() = info.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun gather(activity: Activity, onDone: (FormError?) -> Unit) {
        val params = ConsentRequestParameters.Builder()
            // To test the EEA form on a device, add:
            // .setConsentDebugSettings(ConsentDebugSettings.Builder(activity)
            //     .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
            //     .addTestDeviceHashedId("YOUR_HASHED_ID_FROM_LOGCAT").build())
            .build()
        info.requestConsentInfoUpdate(
            activity,
            params,
            { UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error -> onDone(error) } },
            { error -> onDone(error) },
        )
    }

    fun showPrivacyOptions(activity: Activity, onDone: (FormError?) -> Unit = {}) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error -> onDone(error) }
    }
}
