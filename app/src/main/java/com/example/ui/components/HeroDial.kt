package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.VpnState
import com.example.model.VpnStatus
import com.example.ui.theme.*

@Composable
fun HeroDial(
    vpnState: VpnState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "hero_anim")

    // Rotation for connecting spinner
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotate"
    )

    // Pulsing outer halo scale
    val haloScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "halo"
    )

    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "halo_alpha"
    )

    val ringColor by animateColorAsState(
        targetValue = when (vpnState.status) {
            VpnStatus.CONNECTED -> NeonEmerald
            VpnStatus.CONFIG_VALIDATION,
            VpnStatus.CORE_INITIALIZING,
            VpnStatus.CORE_RUNNING,
            VpnStatus.TUN_ESTABLISHING,
            VpnStatus.CONNECTIVITY_VERIFYING -> NeonAmber
            VpnStatus.DISCONNECTING, VpnStatus.ERROR -> NeonRed
            VpnStatus.DISCONNECTED -> CyanNeon
        },
        label = "ring_color"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(190.dp)
                .padding(8.dp)
        ) {
            // Animated halo ring when active
            if (vpnState.isConnected || vpnState.isTransitioning) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .size(190.dp * haloScale)
                ) {
                    drawCircle(
                        color = ringColor.copy(alpha = haloAlpha),
                        radius = size.minDimension / 2f,
                        style = Stroke(width = 4.dp.toPx())
                    )
                }
            }

            // Outer decorative circular track
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(
                            ringColor.copy(alpha = 0.2f),
                            ringColor,
                            ringColor.copy(alpha = 0.2f)
                        )
                    ),
                    style = Stroke(width = 3.dp.toPx())
                )
            }

            // Main Dial Button
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                CyberCardElevated,
                                CyberDarkBg
                            )
                        )
                    )
                    .border(2.dp, ringColor, CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = ringColor),
                        onClick = onToggle
                    )
                    .testTag("vpn_toggle_button"),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    when (vpnState.status) {
                        VpnStatus.CONNECTED -> {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Connected",
                                tint = NeonEmerald,
                                modifier = Modifier.size(42.dp)
                            )
                        }
                        VpnStatus.CONFIG_VALIDATION,
                        VpnStatus.CORE_INITIALIZING,
                        VpnStatus.CORE_RUNNING,
                        VpnStatus.TUN_ESTABLISHING,
                        VpnStatus.CONNECTIVITY_VERIFYING -> {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "Connecting",
                                tint = NeonAmber,
                                modifier = Modifier
                                    .size(42.dp)
                                    .rotate(rotation)
                            )
                        }
                        VpnStatus.DISCONNECTING, VpnStatus.ERROR -> {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = if (vpnState.status == VpnStatus.ERROR) "Error" else "Stopping",
                                tint = NeonRed,
                                modifier = Modifier.size(42.dp)
                            )
                        }
                        VpnStatus.DISCONNECTED -> {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = "Disconnected",
                                tint = CyanNeon,
                                modifier = Modifier.size(42.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = when (vpnState.status) {
                            VpnStatus.CONNECTED -> "CONNECTED"
                            VpnStatus.CONFIG_VALIDATION -> "VALIDATING..."
                            VpnStatus.CORE_INITIALIZING -> "INIT CORE..."
                            VpnStatus.CORE_RUNNING -> "CORE READY"
                            VpnStatus.TUN_ESTABLISHING -> "TUN SETUP..."
                            VpnStatus.CONNECTIVITY_VERIFYING -> "VERIFYING..."
                            VpnStatus.DISCONNECTING -> "DISCONNECTING"
                            VpnStatus.ERROR -> "ERROR"
                            VpnStatus.DISCONNECTED -> "START VPN"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = ringColor
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Duration or Instruction
        if (vpnState.isConnected) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(NeonEmerald, CircleShape)
                )
                Text(
                    text = vpnState.formattedDuration,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "• IP: ${vpnState.currentVirtualIp}",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        } else {
            Text(
                text = "Tap to launch obfuscated tunnel",
                fontSize = 12.sp,
                color = TextMuted
            )
        }
    }
}
