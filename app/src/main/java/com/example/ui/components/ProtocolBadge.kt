package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.TunnelProtocol

@Composable
fun ProtocolBadge(
    protocol: TunnelProtocol,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val icon: ImageVector = when (protocol) {
        TunnelProtocol.OPENVPN -> Icons.Default.Lock
        TunnelProtocol.SSH -> Icons.Default.Terminal
        TunnelProtocol.V2RAY -> Icons.Default.FilterVintage
        TunnelProtocol.VLESS -> Icons.Default.Cyclone
        TunnelProtocol.VMESS -> Icons.Default.Waves
        TunnelProtocol.SHADOWSOCKS -> Icons.Default.Shield
        TunnelProtocol.SOCKS -> Icons.AutoMirrored.Filled.AltRoute
        TunnelProtocol.HTTP -> Icons.Default.Language
        TunnelProtocol.TROJAN -> Icons.Default.Security
        TunnelProtocol.HYSTERIA1 -> Icons.Default.Bolt
        TunnelProtocol.HYSTERIA2 -> Icons.Default.FlashOn
        TunnelProtocol.WIREGUARD -> Icons.Default.VpnKey
        TunnelProtocol.SLOWDNS -> Icons.Default.Dns
    }

    val shape = RoundedCornerShape(20.dp)
    val bgModifier = if (onClick != null) modifier.clip(shape).clickable { onClick() } else modifier

    Row(
        modifier = bgModifier
            .background(
                if (isSelected) protocol.badgeColor.copy(alpha = 0.25f)
                else Color(0xFF162230)
            )
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) protocol.badgeColor else Color(0xFF26394F),
                shape = shape
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(protocol.badgeColor.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = protocol.displayName,
                tint = protocol.badgeColor,
                modifier = Modifier.size(13.dp)
            )
        }
        Text(
            text = if (protocol.phaseStatus.isPhase1Active) protocol.displayName else "${protocol.displayName} (P2)",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isSelected) protocol.badgeColor else if (!protocol.phaseStatus.isPhase1Active) Color(0xFF94A3B8) else Color(0xFFE2E8F0)
        )
    }
}
