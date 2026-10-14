package com.hinterlay.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.composed
import com.hinterlay.app.ui.components.halftone
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hinterlay.app.ads.AdsManager
import com.hinterlay.app.ads.ConsentManager
import com.hinterlay.app.billing.BillingManager
import com.hinterlay.app.cloud.Cloud
import com.hinterlay.app.ui.findActivity
import com.hinterlay.app.cloud.Insights
import com.hinterlay.app.cloud.ContentRepo
import com.hinterlay.app.cloud.Notifier
import com.hinterlay.app.cloud.Preset
import com.hinterlay.app.data.HistoryItem
import com.hinterlay.app.data.HistoryStore
import com.hinterlay.app.editor.EditorViewModel
import com.hinterlay.app.ui.editor.EditorScreen
import com.hinterlay.app.ui.home.CreateTab
import com.hinterlay.app.ui.screens.CreationsScreen
import com.hinterlay.app.ui.screens.InspireScreen
import com.hinterlay.app.ui.screens.MoreScreen
import com.hinterlay.app.ui.screens.OnboardingScreen
import com.hinterlay.app.ui.screens.PrivacyScreen
import com.hinterlay.app.ui.screens.StickersScreen
import com.hinterlay.app.ui.screens.SupportScreen
import com.hinterlay.app.ui.screens.rememberCreations
import com.hinterlay.app.ui.theme.HinterlayTheme

class MainActivity : ComponentActivity() {

    private lateinit var consent: ConsentManager
    private val pendingAction = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // Required look on Android 15+ (target SDK 35+).
        super.onCreate(savedInstanceState)
        com.hinterlay.app.render.Compositor.init(applicationContext)
        com.hinterlay.app.fx.Fx.init(applicationContext)
        com.hinterlay.app.data.AdFree.init(applicationContext)
        com.hinterlay.app.data.Referral.init(applicationContext)
        BillingManager.start(applicationContext)
        Cloud.init(applicationContext)
        Insights.init(application)
        ContentRepo.start()
        Notifier.ensureChannel(applicationContext)

        consent = ConsentManager(applicationContext)
        // Ask for consent first (EEA/UK etc.); only then initialise and request ads.
        consent.gather(this) { AdsManager.initializeIfAllowed(applicationContext, consent) }
        // Consent from a previous session lets ads start loading right away.
        AdsManager.initializeIfAllowed(applicationContext, consent)

        val shared: Uri? = if (intent?.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        } else null
        pendingAction.value = intent?.getStringExtra(Notifier.EXTRA_ACTION)

        setContent {
            HinterlayTheme {
                HinterlayRoot(
                    initialUri = shared,
                    consent = consent,
                    pendingAction = pendingAction.value,
                    onActionHandled = { pendingAction.value = null },
                )
            }
        }
    }

    // A notification tapped while the app is already open.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingAction.value = intent.getStringExtra(Notifier.EXTRA_ACTION)
    }
}

private fun Modifier.com_halftone(): Modifier = composed {
    this.halftone(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), spacing = 12.dp, maxRadius = 1.7.dp, fromTop = false)
}

private enum class Screen { Home, Stickers, Support, Privacy, Onboarding, Camera }
private enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Create("Create", Icons.Outlined.Home, Icons.Filled.Home),
    Inspire("Inspire", Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
    Library("Library", Icons.Outlined.PhotoLibrary, Icons.Filled.PhotoLibrary),
    More("More", Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
}

@Composable
private fun HinterlayRoot(initialUri: Uri?, consent: ConsentManager, pendingAction: String?, onActionHandled: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("hinterlay_ui", Context.MODE_PRIVATE)
    var screen by rememberSaveable {
        mutableStateOf(if (prefs.getBoolean("onboarded", false)) Screen.Home else Screen.Onboarding)
    }
    var tab by rememberSaveable { mutableStateOf(Tab.Create) }
    var editingUri by rememberSaveable { mutableStateOf(initialUri) }
    val editor: EditorViewModel = viewModel()
    val creations = rememberCreations(reloadKey = editingUri == null) // refresh after returning from the editor
    val fb = com.hinterlay.app.fx.rememberFeedback()

    // Nothing is collected and nothing opens until the privacy policy is accepted (asked again after a version bump).
    var privacyOk by rememberSaveable {
        mutableStateOf(prefs.getInt("privacy_version", 0) >= com.hinterlay.app.ui.screens.PRIVACY_POLICY_VERSION)
    }
    if (!privacyOk) {
        com.hinterlay.app.ui.screens.PrivacyConsentScreen(
            onAccept = { shareUsage ->
                prefs.edit().putInt("privacy_version", com.hinterlay.app.ui.screens.PRIVACY_POLICY_VERSION).apply()
                Cloud.privacyAccepted = true
                Cloud.analyticsEnabled = shareUsage
                Insights.setEnabled(context, shareUsage)
                privacyOk = true
            },
            onDecline = { context.findActivity()?.finishAffinity() },
        )
        return
    }

    // "Pick photo" from a shortcut, widget or Assistant: open the system photo picker right away.
    var pickRequest by rememberSaveable { mutableStateOf(false) }
    val quickPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { picked -> if (picked != null) editingUri = picked }
    LaunchedEffect(pickRequest) {
        if (pickRequest) {
            pickRequest = false
            quickPicker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    // Where a live card or a tapped notification should take the user.
    val route: (String) -> Unit = { action ->
        when {
            action == "camera" -> screen = Screen.Camera
            action == "pick" -> { screen = Screen.Home; tab = Tab.Create; pickRequest = true }
            action == "create" -> { screen = Screen.Home; tab = Tab.Create }
            action == "inspire" -> { screen = Screen.Home; tab = Tab.Inspire }
            action == "wallpaper" -> { screen = Screen.Home; tab = Tab.Library }
            action == "stickers" -> screen = Screen.Stickers
            action == "support" -> screen = Screen.Support
            action.startsWith("url:") -> runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.removePrefix("url:"))))
            }
        }
    }
    LaunchedEffect(pendingAction) {
        if (pendingAction != null) {
            editingUri = null
            route(pendingAction)
            onActionHandled()
        }
    }

    val screenName = when {
        editingUri != null -> "editor"
        screen == Screen.Camera -> "camera"
        screen == Screen.Home -> tab.name.lowercase()
        else -> screen.name.lowercase()
    }
    LaunchedEffect(screenName) { Insights.screen(screenName) }

    val uri = editingUri
    if (uri != null) {
        EditorScreen(uri = uri, vm = editor, onExit = {
            editor.reset()
            editingUri = null
        })
        return
    }

    val openCreation: (HistoryItem) -> Unit = { item ->
        editor.pendingRestore = HistoryStore.layers(item)
        editor.pendingForeground = item.foreground
        editor.pendingSubjectMask = item.subjectMask
        editor.pendingAdjust = HistoryStore.adjust(item)
        editor.pendingFx = HistoryStore.fx(item)
        editor.pendingBlend = HistoryStore.blendFile(item)
        editingUri = Uri.fromFile(item.source)
    }

    if (screen != Screen.Home) BackHandler { screen = Screen.Home }
    androidx.compose.animation.AnimatedContent(
        targetState = screen,
        transitionSpec = {
            (androidx.compose.animation.fadeIn(com.hinterlay.app.ui.theme.Motion.enter()) +
                androidx.compose.animation.scaleIn(com.hinterlay.app.ui.theme.Motion.enter(), initialScale = 0.97f)) togetherWith
                androidx.compose.animation.fadeOut(com.hinterlay.app.ui.theme.Motion.exit())
        },
        label = "screen",
    ) { currentScreen ->
    when (currentScreen) {
        Screen.Onboarding -> OnboardingScreen(onDone = {
            prefs.edit().putBoolean("onboarded", true).apply()
            com.hinterlay.app.cloud.Track.event("onboarding_complete")
            screen = Screen.Home
        })
        Screen.Camera -> com.hinterlay.app.camera.CameraScreen(
            onClose = { screen = Screen.Home },
            onPhoto = { uri -> screen = Screen.Home; editingUri = uri },
        )
        Screen.Privacy -> PrivacyScreen(onBack = { screen = Screen.Home })
        Screen.Support -> SupportScreen(onBack = { screen = Screen.Home })
        Screen.Stickers -> StickersScreen(
            onBack = { screen = Screen.Home },
            onOpenCreations = { screen = Screen.Home; tab = Tab.Library },
        )
        Screen.Home -> {
            // Back from another tab returns to Create first, like most apps.
            if (tab != Tab.Create) BackHandler { tab = Tab.Create }
            Scaffold(
                bottomBar = {
                    com.hinterlay.app.ui.home.FloatingNavBar(
                        items = Tab.entries.map { com.hinterlay.app.ui.home.NavItem(it.label, it.icon, it.selectedIcon) },
                        selected = tab.ordinal,
                        onSelect = { i -> if (tab.ordinal != i) fb.select(); tab = Tab.entries[i] },
                        onCamera = { fb.toggle(); screen = Screen.Camera },
                    )
                },
                containerColor = MaterialTheme.colorScheme.background,
            ) { padding ->
                Box(
                    Modifier.padding(bottom = padding.calculateBottomPadding() - 8.dp)
                        .background(MaterialTheme.colorScheme.background)
                        // Faint dot grid behind every tab; the screens above are transparent so it shows through.
                        .com_halftone(),
                ) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { it / 24 }) togetherWith
                                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(110))
                        },
                        label = "tab",
                    ) { currentTab ->
                    when (currentTab) {
                        Tab.Create -> CreateTab(
                            creations = creations,
                            onPhotoPicked = { editingUri = it },
                            onOpenCreation = openCreation,
                            onSeeAll = { tab = Tab.Library },
                            onCardAction = route,
                        )
                        Tab.Inspire -> InspireScreen(onUseIdea = { preset: Preset, picked: Uri ->
                            editor.pendingPreset = preset
                            editingUri = picked
                        })
                        Tab.Library -> CreationsScreen(
                            creations, onBack = null, onOpen = openCreation,
                            onOpenStickers = { screen = Screen.Stickers },
                            onPick = { editingUri = it },
                        )
                        Tab.More -> MoreScreen(
                            consent = consent,
                            onStickers = { screen = Screen.Stickers },
                            onSupport = { screen = Screen.Support },
                            onHowItWorks = { screen = Screen.Onboarding },
                            onPrivacy = { screen = Screen.Privacy },
                        )
                    }
                    }
                }
            }
        }
    }
    }
}
