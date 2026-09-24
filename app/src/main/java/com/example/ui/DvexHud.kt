package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AiCoreState
import com.example.model.DvexUiState
import com.example.model.VoiceState
import com.example.ui.components.CameraVisionPanel
import com.example.ui.components.DvexAiCore
import com.example.ui.components.LocationPanel
import com.example.ui.components.MemoryCorePanel
import com.example.ui.components.MicrophoneButton
import com.example.ui.components.NotificationPanel
import com.example.ui.components.PowerModeButtonCluster
import com.example.ui.components.QuickAccessPanel
import com.example.ui.components.RecentActivityPanel
import com.example.ui.components.ResponsePanel
import com.example.ui.components.SystemStatusPanel
import com.example.ui.components.TimePanel
import com.example.ui.components.VoiceModule
import com.example.ui.components.WeatherPanel
import com.example.ui.theme.DvexBlack
import com.example.ui.theme.DvexBorderMuted
import com.example.ui.theme.DvexNeonRed
import com.example.ui.theme.DvexNeonRedBright
import com.example.ui.theme.DvexSurfaceCard
import com.example.ui.theme.DvexSurfaceDark
import com.example.ui.theme.DvexTextMuted
import com.example.ui.theme.DvexTextPrimary
import com.example.ui.theme.DvexTextSecondary

/**
 * ============================================================
 * D-VEX TACTICAL HUD
 * ============================================================
 *
 * Layout 1:
 *
 * ------------------------------------------------------------
 * HEADER
 * ------------------------------------------------------------
 * STATUS | WEATHER | AI VISION | D-VEX CORE | QUICK ACTIONS
 * ------------------------------------------------------------
 * VOICE | COMMAND INPUT | MICROPHONE
 * ------------------------------------------------------------
 * D-VEX RESPONSE
 * ------------------------------------------------------------
 *
 * Existing D-VEX core visuals are preserved.
 * No AI core colors/glow/animation are changed here.
 */
@Composable
fun DvexHud(
    uiState: DvexUiState,
    onUiStateChange: (DvexUiState) -> Unit,
    modifier: Modifier = Modifier,
    onSendCommand: ((String) -> Unit)? = null,
    onCenterCoreTapped: (() -> Unit)? = null,
    onMicrophoneTapped: (() -> Unit)? = null,
    onQuickActionSelected: ((String) -> Unit)? = null,
    onOpenSettingsRequested: (() -> Unit)? = null,
    isPowerMode: Boolean = false,
    onTogglePowerMode: () -> Unit = {},
    isOrbActive: Boolean = false,
    isVisionActive: Boolean = false,
    onToggleOrb: () -> Unit = {},
    onRecentApp: () -> Unit = {},
    onNotificationAlert: () -> Unit = {},
    onDeviceControl: () -> Unit = {},
    onToggleVision: () -> Unit = {}
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(DvexBlack)
    ) {

        val isWidescreen = maxWidth >= 840.dp

        if (isWidescreen) {
            WidescreenTacticalHud(
                uiState = uiState,
                onUiStateChange = onUiStateChange,
                onSendCommand = onSendCommand,
                onCenterCoreTapped = onCenterCoreTapped,
                onMicrophoneTapped = onMicrophoneTapped,
                onQuickActionSelected = onQuickActionSelected,
                onOpenSettingsRequested = onOpenSettingsRequested,
                isPowerMode = isPowerMode,
                onTogglePowerMode = onTogglePowerMode,
                isOrbActive = isOrbActive,
                isVisionActive = isVisionActive,
                onToggleOrb = onToggleOrb,
                onRecentApp = onRecentApp,
                onNotificationAlert = onNotificationAlert,
                onDeviceControl = onDeviceControl,
                onToggleVision = onToggleVision
            )
        } else {
            CompactMobileTacticalHud(
                uiState = uiState,
                onUiStateChange = onUiStateChange,
                onSendCommand = onSendCommand,
                onCenterCoreTapped = onCenterCoreTapped,
                onMicrophoneTapped = onMicrophoneTapped,
                onQuickActionSelected = onQuickActionSelected,
                onOpenSettingsRequested = onOpenSettingsRequested,
                isPowerMode = isPowerMode,
                onTogglePowerMode = onTogglePowerMode,
                isOrbActive = isOrbActive,
                isVisionActive = isVisionActive,
                onToggleOrb = onToggleOrb,
                onRecentApp = onRecentApp,
                onNotificationAlert = onNotificationAlert,
                onDeviceControl = onDeviceControl,
                onToggleVision = onToggleVision
            )
        }
    }
}


/* ============================================================
 * WIDESCREEN HUD
 * ============================================================ */

@Composable
private fun WidescreenTacticalHud(
    uiState: DvexUiState,
    onUiStateChange: (DvexUiState) -> Unit,
    onSendCommand: ((String) -> Unit)?,
    onCenterCoreTapped: (() -> Unit)?,
    onMicrophoneTapped: (() -> Unit)?,
    onQuickActionSelected: ((String) -> Unit)?,
    onOpenSettingsRequested: (() -> Unit)?,
    isPowerMode: Boolean,
    onTogglePowerMode: () -> Unit,
    isOrbActive: Boolean,
    isVisionActive: Boolean,
    onToggleOrb: () -> Unit,
    onRecentApp: () -> Unit,
    onNotificationAlert: () -> Unit,
    onDeviceControl: () -> Unit,
    onToggleVision: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {

        /* ====================================================
         * HEADER
         * ==================================================== */

        DvexTopBar(
            uiState = uiState,
            isCompact = false,
            isPowerMode = isPowerMode,
            onTogglePowerMode = onTogglePowerMode
        )


        /* ====================================================
         * MAIN HORIZONTAL HUD
         * ==================================================== */

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(
                    horizontal = 8.dp,
                    vertical = 6.dp
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {


            /* =================================================
             * LEFT SIDE
             * WEATHER + VISION
             * ================================================= */

            Column(
                modifier = Modifier
                    .weight(0.95f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                /* WEATHER */

                HudSectionCard(
                    title = "WEATHER",
                    modifier = Modifier.weight(0.8f)
                ) {

                    WeatherPanel(
                        weather = uiState.weather,
                        modifier = Modifier.fillMaxWidth()
                    )
                }


                /* AI VISION */

                HudSectionCard(
                    title = "AI VISION",
                    modifier = Modifier.weight(1.2f)
                ) {

                    CameraVisionPanel(
                        isVisionActive = isVisionActive,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(
                        modifier = Modifier.height(6.dp)
                    )

                    Text(
                        text =
                            if (isVisionActive)
                                "VISION ACTIVE"
                            else
                                "VISION STANDBY",
                        modifier = Modifier.fillMaxWidth(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color =
                            if (isVisionActive)
                                DvexNeonRedBright
                            else
                                DvexTextMuted
                    )
                }
            }


            /* =================================================
             * CENTER
             * SYSTEM + D-VEX CORE
             * ================================================= */

            Column(
                modifier = Modifier
                    .weight(1.45f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                /* SYSTEM STATUS */

                HudSectionCard(
                    title = "SYSTEM ONLINE",
                    modifier = Modifier.fillMaxWidth()
                ) {

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(6.dp)
                    ) {

                        SystemStatusPanel(
                            vitals = uiState.systemStatus,
                            modifier = Modifier.weight(1f)
                        )

                        TimePanel(
                            modifier = Modifier.weight(1f)
                        )
                    }
                }


                /* D-VEX CORE */

                HudSectionCard(
                    title = "D-VEX AI CORE",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {

                        /*
                         * DO NOT MODIFY:
                         *
                         * DvexAiCore already contains the
                         * reactor ring, colors, glow,
                         * animations and core visual design.
                         */

                        DvexAiCore(
                            aiState = uiState.aiState,

                            onStateChangeRequest = { newState ->

                                if (onCenterCoreTapped != null) {

                                    onCenterCoreTapped()

                                } else {

                                    val newVoiceState =
                                        when (newState) {

                                            AiCoreState.LISTENING ->
                                                VoiceState.LISTENING

                                            AiCoreState.PROCESSING ->
                                                VoiceState.PROCESSING

                                            AiCoreState.RESPONDING ->
                                                VoiceState.SPEAKING

                                            else ->
                                                VoiceState.IDLE
                                        }

                                    onUiStateChange(
                                        uiState.copy(
                                            aiState = newState,
                                            voiceState = newVoiceState
                                        )
                                    )
                                }
                            }
                        )
                    }
                }
            }


            /* =================================================
             * RIGHT SIDE
             * QUICK ACTIONS + ACTIVITY
             * ================================================= */

            Column(
                modifier = Modifier
                    .weight(0.95f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                /* QUICK ACTIONS */

                HudSectionCard(
                    title = "QUICK ACTIONS",
                    modifier = Modifier.weight(1f)
                ) {

                    if (isPowerMode) {

                        PowerModeButtonCluster(
                            isPowerMode = true,
                            isOrbActive = isOrbActive,
                            isVisionActive = isVisionActive,
                            onToggleOrb = onToggleOrb,
                            onRecentApp = onRecentApp,
                            onNotificationAlert =
                                onNotificationAlert,
                            onDeviceControl =
                                onDeviceControl,
                            onToggleVision =
                                onToggleVision
                        )

                        Spacer(
                            modifier = Modifier.height(6.dp)
                        )
                    }

                    QuickAccessPanel(
                        onActionSelected = { action ->

                            if (
                                action == "Settings" ||
                                action == "More"
                            ) {
                                onOpenSettingsRequested?.invoke()
                            }

                            onQuickActionSelected?.invoke(action)
                                ?: onUiStateChange(
                                    uiState.copy(
                                        responseText =
                                            "Quick access: $action triggered.",
                                        aiState =
                                            AiCoreState.PROCESSING
                                    )
                                )
                        }
                    )
                }


                /* ACTIVITY */

                HudSectionCard(
                    title = "ACTIVITY",
                    modifier = Modifier.weight(1f)
                ) {

                    MemoryCorePanel(
                        percentage = uiState.memoryPercentage,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(
                        modifier = Modifier.height(5.dp)
                    )

                    RecentActivityPanel(
                        activities =
                            uiState.recentActivities,
                        modifier =
                            Modifier.fillMaxWidth()
                    )

                    Spacer(
                        modifier = Modifier.height(5.dp)
                    )

                    NotificationPanel(
                        notifications =
                            uiState.notifications,
                        modifier =
                            Modifier.fillMaxWidth()
                    )
                }
            }
        }


        /* ====================================================
         * BOTTOM
         * VOICE + COMMAND + RESPONSE
         * ==================================================== */

        DesktopBottomControls(
            uiState = uiState,
            onUiStateChange = onUiStateChange,
            onSendCommand = onSendCommand,
            onMicrophoneTapped = onMicrophoneTapped
        )
    }
}


/* ============================================================
 * MOBILE / COMPACT HUD
 *
 * Still horizontal Layout 1.
 * Designed for landscape / compact HUD usage.
 * ============================================================ */

@Composable
private fun CompactMobileTacticalHud(
    uiState: DvexUiState,
    onUiStateChange: (DvexUiState) -> Unit,
    onSendCommand: ((String) -> Unit)?,
    onCenterCoreTapped: (() -> Unit)?,
    onMicrophoneTapped: (() -> Unit)?,
    onQuickActionSelected: ((String) -> Unit)?,
    onOpenSettingsRequested: (() -> Unit)?,
    isPowerMode: Boolean,
    onTogglePowerMode: () -> Unit,
    isOrbActive: Boolean,
    isVisionActive: Boolean,
    onToggleOrb: () -> Unit,
    onRecentApp: () -> Unit,
    onNotificationAlert: () -> Unit,
    onDeviceControl: () -> Unit,
    onToggleVision: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {

        /* HEADER */

        DvexTopBar(
            uiState = uiState,
            isCompact = true,
            isPowerMode = isPowerMode,
            onTogglePowerMode = onTogglePowerMode
        )


        /* ====================================================
         * HORIZONTAL BODY
         * ==================================================== */

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement =
                Arrangement.spacedBy(6.dp)
        ) {


            /* LEFT */

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement =
                    Arrangement.spacedBy(6.dp)
            ) {

                HudSectionCard(
                    title = "WEATHER",
                    modifier = Modifier.weight(0.8f)
                ) {

                    WeatherPanel(
                        weather = uiState.weather,
                        modifier = Modifier.fillMaxWidth()
                    )
                }


                HudSectionCard(
                    title = "AI VISION",
                    modifier = Modifier.weight(1.2f)
                ) {

                    CameraVisionPanel(
                        isVisionActive = isVisionActive,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }


            /* CENTER */

            Column(
                modifier = Modifier
                    .weight(1.35f)
                    .fillMaxHeight(),
                verticalArrangement =
                    Arrangement.spacedBy(6.dp)
            ) {

                HudSectionCard(
                    title = "SYSTEM ONLINE",
                    modifier = Modifier.fillMaxWidth()
                ) {

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(4.dp)
                    ) {

                        SystemStatusPanel(
                            vitals = uiState.systemStatus,
                            modifier = Modifier.weight(1f)
                        )

                        TimePanel(
                            modifier = Modifier.weight(1f)
                        )
                    }
                }


                HudSectionCard(
                    title = "D-VEX AI CORE",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment =
                            Alignment.Center
                    ) {

                        DvexAiCore(
                            aiState = uiState.aiState,

                            onStateChangeRequest = { newState ->

                                if (onCenterCoreTapped != null) {

                                    onCenterCoreTapped()

                                } else {

                                    val newVoiceState =
                                        when (newState) {

                                            AiCoreState.LISTENING ->
                                                VoiceState.LISTENING

                                            AiCoreState.PROCESSING ->
                                                VoiceState.PROCESSING

                                            AiCoreState.RESPONDING ->
                                                VoiceState.SPEAKING

                                            else ->
                                                VoiceState.IDLE
                                        }

                                    onUiStateChange(
                                        uiState.copy(
                                            aiState = newState,
                                            voiceState = newVoiceState
                                        )
                                    )
                                }
                            }
                        )
                    }
                }
            }


            /* RIGHT */

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement =
                    Arrangement.spacedBy(6.dp)
            ) {

                HudSectionCard(
                    title = "QUICK ACTIONS",
                    modifier = Modifier.weight(1f)
                ) {

                    if (isPowerMode) {

                        PowerModeButtonCluster(
                            isPowerMode = true,
                            isOrbActive = isOrbActive,
                            isVisionActive = isVisionActive,
                            onToggleOrb = onToggleOrb,
                            onRecentApp = onRecentApp,
                            onNotificationAlert =
                                onNotificationAlert,
                            onDeviceControl =
                                onDeviceControl,
                            onToggleVision =
                                onToggleVision
                        )

                        Spacer(
                            modifier = Modifier.height(4.dp)
                        )
                    }

                    QuickAccessPanel(
                        onActionSelected = { action ->

                            if (
                                action == "Settings" ||
                                action == "More"
                            ) {
                                onOpenSettingsRequested?.invoke()
                            }

                            onQuickActionSelected?.invoke(action)
                        }
                    )
                }


                HudSectionCard(
                    title = "ACTIVITY",
                    modifier = Modifier.weight(1f)
                ) {

                    RecentActivityPanel(
                        activities =
                            uiState.recentActivities,
                        modifier =
                            Modifier.fillMaxWidth()
                    )

                    Spacer(
                        modifier = Modifier.height(4.dp)
                    )

                    NotificationPanel(
                        notifications =
                            uiState.notifications,
                        modifier =
                            Modifier.fillMaxWidth()
                    )
                }
            }
        }


        /* BOTTOM */

        MobileBottomControls(
            uiState = uiState,
            onUiStateChange = onUiStateChange,
            onSendCommand = onSendCommand,
            onMicrophoneTapped = onMicrophoneTapped
        )
    }
}


/* ============================================================
 * COMMON SECTION CARD
 * ============================================================ */

@Composable
private fun HudSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {

    Column(
        modifier = modifier
            .background(
                color = DvexSurfaceCard,
                shape = CutCornerShape(6.dp)
            )
            .border(
                width = 1.dp,
                color = DvexBorderMuted,
                shape = CutCornerShape(6.dp)
            )
            .padding(10.dp),

        verticalArrangement =
            Arrangement.spacedBy(6.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(16.dp)
                    .background(
                        DvexNeonRed,
                        CutCornerShape(1.dp)
                    )
            )

            Spacer(
                modifier = Modifier.width(7.dp)
            )

            Text(
                text = title,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = DvexTextSecondary
            )
        }

        content()
    }
}


/* ============================================================
 * DESKTOP BOTTOM CONTROLS
 * ============================================================ */

@Composable
private fun DesktopBottomControls(
    uiState: DvexUiState,
    onUiStateChange: (DvexUiState) -> Unit,
    onSendCommand: ((String) -> Unit)?,
    onMicrophoneTapped: (() -> Unit)?
) {

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DvexSurfaceDark)
            .border(
                width = 1.dp,
                color = DvexBorderMuted,
                shape = CutCornerShape(
                    topStart = 8.dp,
                    topEnd = 8.dp
                )
            )
            .padding(
                horizontal = 12.dp,
                vertical = 8.dp
            )
    ) {

        /* RESPONSE */

        ResponsePanel(
            responseText = uiState.responseText,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(
            modifier = Modifier.height(6.dp)
        )


        /* COMMAND */

        TacticalCommandInput(
            onSendCommand = { command ->
                onSendCommand?.invoke(command)
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(
            modifier = Modifier.height(6.dp)
        )


        /* VOICE + MIC */

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(10.dp),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            VoiceModule(
                voiceState = uiState.voiceState,
                modifier = Modifier.weight(1f)
            )

            MicrophoneButton(
                isListening =
                    uiState.voiceState ==
                        VoiceState.LISTENING,

                onClick = {

                    handleMicrophoneTap(
                        uiState = uiState,
                        onUiStateChange =
                            onUiStateChange,
                        onMicrophoneTapped =
                            onMicrophoneTapped
                    )
                }
            )
        }
    }
}


/* ============================================================
 * MOBILE BOTTOM CONTROLS
 * ============================================================ */

@Composable
private fun MobileBottomControls(
    uiState: DvexUiState,
    onUiStateChange: (DvexUiState) -> Unit,
    onSendCommand: ((String) -> Unit)?,
    onMicrophoneTapped: (() -> Unit)?
) {

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DvexSurfaceDark)
            .border(
                width = 1.dp,
                color = DvexBorderMuted,
                shape = CutCornerShape(
                    topStart = 8.dp,
                    topEnd = 8.dp
                )
            )
            .padding(
                horizontal = 10.dp,
                vertical = 8.dp
            )
    ) {

        ResponsePanel(
            responseText = uiState.responseText,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(
            modifier = Modifier.height(6.dp)
        )

        TacticalCommandInput(
            onSendCommand = { command ->
                onSendCommand?.invoke(command)
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(
            modifier = Modifier.height(6.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(10.dp),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            VoiceModule(
                voiceState = uiState.voiceState,
                modifier = Modifier.weight(1f)
            )

            MicrophoneButton(
                isListening =
                    uiState.voiceState ==
                        VoiceState.LISTENING,

                onClick = {

                    handleMicrophoneTap(
                        uiState = uiState,
                        onUiStateChange =
                            onUiStateChange,
                        onMicrophoneTapped =
                            onMicrophoneTapped
                    )
                }
            )
        }
    }
}


/* ============================================================
 * MICROPHONE HANDLER
 * ============================================================ */

private fun handleMicrophoneTap(
    uiState: DvexUiState,
    onUiStateChange: (DvexUiState) -> Unit,
    onMicrophoneTapped: (() -> Unit)?
) {

    if (onMicrophoneTapped != null) {
        onMicrophoneTapped()
        return
    }

    val nextVoiceState =
        if (
            uiState.voiceState ==
                VoiceState.LISTENING
        ) {
            VoiceState.IDLE
        } else {
            VoiceState.LISTENING
        }

    val nextAiState =
        if (
            nextVoiceState ==
                VoiceState.LISTENING
        ) {
            AiCoreState.LISTENING
        } else {
            AiCoreState.IDLE
        }

    onUiStateChange(
        uiState.copy(
            voiceState = nextVoiceState,
            aiState = nextAiState,
            responseText =
                if (
                    nextVoiceState ==
                        VoiceState.LISTENING
                ) {
                    "Listening for command..."
                } else {
                    "Standing by."
                }
        )
    )
}


/* ============================================================
 * TACTICAL COMMAND INPUT
 * ============================================================ */

@Composable
fun TacticalCommandInput(
    onSendCommand: (String) -> Unit,
    modifier: Modifier = Modifier
) {

    var commandText by remember {
        mutableStateOf("")
    }

    val focusManager =
        LocalFocusManager.current

    val keyboardController =
        LocalSoftwareKeyboardController.current

    Row(
        modifier = modifier
            .background(
                DvexSurfaceDark,
                CutCornerShape(4.dp)
            )
            .border(
                width = 1.dp,
                color = DvexBorderMuted,
                shape = CutCornerShape(4.dp)
            )
            .padding(
                horizontal = 8.dp,
                vertical = 4.dp
            ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        BasicTextField(
            value = commandText,

            onValueChange = {
                commandText = it
            },

            modifier = Modifier
                .weight(1f)
                .padding(
                    horizontal = 6.dp,
                    vertical = 6.dp
                ),

            textStyle = TextStyle(
                fontFamily =
                    FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight =
                    FontWeight.Medium,
                color =
                    DvexTextPrimary
            ),

            cursorBrush =
                SolidColor(DvexNeonRedBright),

            singleLine = true,

            keyboardOptions =
                KeyboardOptions(
                    imeAction =
                        ImeAction.Send,
                    keyboardType =
                        KeyboardType.Text
                ),

            keyboardActions =
                KeyboardActions(
                    onSend = {

                        if (
                            commandText.isNotBlank()
                        ) {

                            val command =
                                commandText.trim()

                            commandText = ""

                            keyboardController
                                ?.hide()

                            focusManager
                                .clearFocus()

                            onSendCommand(command)
                        }
                    }
                ),

            decorationBox = {
                innerTextField ->

                Box(
                    contentAlignment =
                        Alignment.CenterStart
                ) {

                    if (
                        commandText.isEmpty()
                    ) {

                        Text(
                            text =
                                "Enter tactical command...",
                            fontFamily =
                                FontFamily.Monospace,
                            fontSize = 11.sp,
                            color =
                                DvexTextMuted
                        )
                    }

                    innerTextField()
                }
            }
        )


        Spacer(
            modifier = Modifier.width(6.dp)
        )


        val isSendEnabled =
            commandText.isNotBlank()


        Box(
            modifier = Modifier
                .clip(
                    CutCornerShape(3.dp)
                )
                .background(
                    if (isSendEnabled)
                        DvexNeonRed
                    else
                        DvexSurfaceCard
                )
                .border(
                    width = 1.dp,
                    color =
                        if (isSendEnabled)
                            DvexNeonRedBright
                        else
                            DvexBorderMuted,
                    shape =
                        CutCornerShape(3.dp)
                )
                .clickable(
                    enabled = isSendEnabled
                ) {

                    if (
                        commandText.isNotBlank()
                    ) {

                        val command =
                            commandText.trim()

                        commandText = ""

                        keyboardController
                            ?.hide()

                        focusManager
                            .clearFocus()

                        onSendCommand(command)
                    }
                }
                .padding(
                    horizontal = 10.dp,
                    vertical = 6.dp
                ),

            contentAlignment =
                Alignment.Center
        ) {

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Icon(
                    imageVector =
                        Icons.AutoMirrored.Filled.Send,

                    contentDescription =
                        "Send tactical command",

                    tint =
                        if (isSendEnabled)
                            Color.White
                        else
                            DvexTextMuted,

                    modifier =
                        Modifier.size(13.dp)
                )

                Spacer(
                    modifier = Modifier.width(4.dp)
                )

                Text(
                    text = "SEND",
                    fontFamily =
                        FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight =
                        FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color =
                        if (isSendEnabled)
                            Color.White
                        else
                            DvexTextMuted
                )
            }
        }
    }
}