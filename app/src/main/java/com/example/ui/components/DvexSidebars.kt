package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.NavItem
import com.example.ui.theme.DvexBorderMuted
import com.example.ui.theme.DvexBorderRed
import com.example.ui.theme.DvexNeonRed
import com.example.ui.theme.DvexNeonRedBright
import com.example.ui.theme.DvexNeonRedDim
import com.example.ui.theme.DvexNeonRedGlow
import com.example.ui.theme.DvexNeonRedSubtle
import com.example.ui.theme.DvexSurfaceCard
import com.example.ui.theme.DvexSurfaceDark
import com.example.ui.theme.DvexTextMuted
import com.example.ui.theme.DvexTextPrimary
import com.example.ui.theme.DvexTextSecondary

private data class SidebarItemDef(
  val item: NavItem,
  val icon: ImageVector
)

@Composable
fun DvexLeftSidebar(
  selectedItem: NavItem,
  onItemSelected: (NavItem) -> Unit,
  modifier: Modifier = Modifier
) {
  val items = listOf(
    SidebarItemDef(NavItem.HOME, Icons.Filled.Home),
    SidebarItemDef(NavItem.CALL, Icons.Filled.Call),
    SidebarItemDef(NavItem.MSG, Icons.Filled.Sms),
    SidebarItemDef(NavItem.EMAIL, Icons.Filled.Email),
    SidebarItemDef(NavItem.MAPS, Icons.Filled.Map),
    SidebarItemDef(NavItem.APPS, Icons.Filled.Apps),
    SidebarItemDef(NavItem.SETTINGS, Icons.Filled.Settings)
  )

  Column(
    modifier = modifier
      .width(68.dp)
      .fillMaxHeight()
      .background(DvexSurfaceDark)
      .border(1.dp, DvexBorderMuted, CutCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
      .padding(vertical = 10.dp, horizontal = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.SpaceBetween
  ) {
    items.forEach { def ->
      SidebarButton(
        label = def.item.title,
        icon = def.icon,
        isActive = selectedItem == def.item,
        onClick = { onItemSelected(def.item) }
      )
    }
  }
}

@Composable
fun DvexRightSidebar(
  selectedItem: NavItem,
  onItemSelected: (NavItem) -> Unit,
  modifier: Modifier = Modifier
) {
  val items = listOf(
    SidebarItemDef(NavItem.AI, Icons.Filled.Psychology),
    SidebarItemDef(NavItem.TOOLS, Icons.Filled.Build),
    SidebarItemDef(NavItem.MEMORY, Icons.Filled.Memory),
    SidebarItemDef(NavItem.SECURITY, Icons.Filled.Security),
    SidebarItemDef(NavItem.POWER, Icons.Filled.PowerSettingsNew)
  )

  Column(
    modifier = modifier
      .width(68.dp)
      .fillMaxHeight()
      .background(DvexSurfaceDark)
      .border(1.dp, DvexBorderMuted, CutCornerShape(topStart = 8.dp, bottomStart = 8.dp))
      .padding(vertical = 12.dp, horizontal = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.SpaceBetween
  ) {
    items.forEach { def ->
      SidebarButton(
        label = def.item.title,
        icon = def.icon,
        isActive = selectedItem == def.item,
        onClick = { onItemSelected(def.item) }
      )
    }
  }
}

@Composable
private fun SidebarButton(
  label: String,
  icon: ImageVector,
  isActive: Boolean,
  onClick: () -> Unit
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()

  val borderColor by animateColorAsState(
    targetValue = when {
      isPressed -> DvexNeonRedBright
      isActive -> DvexNeonRed
      else -> Color.Transparent
    },
    label = "sidebarBorder"
  )

  val bgColor by animateColorAsState(
    targetValue = when {
      isPressed -> DvexNeonRedSubtle.copy(alpha = 0.5f)
      isActive -> DvexNeonRedSubtle.copy(alpha = 0.35f)
      else -> Color.Transparent
    },
    label = "sidebarBg"
  )

  val shape = CutCornerShape(4.dp)

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .defaultMinSize(minHeight = 48.dp)
      .clip(shape)
      .background(bgColor)
      .border(1.dp, borderColor, shape)
      .clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick
      )
      .padding(vertical = 6.dp),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // Small active glowing pip
      if (isActive) {
        Box(
          modifier = Modifier
            .size(12.dp, 2.dp)
            .background(DvexNeonRed)
        )
        Spacer(modifier = Modifier.height(2.dp))
      }
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = if (isActive || isPressed) DvexNeonRedBright else DvexTextSecondary,
        modifier = Modifier.size(18.dp)
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = label,
        fontFamily = FontFamily.Monospace,
        fontSize = 8.sp,
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
        color = if (isActive || isPressed) DvexNeonRedBright else DvexTextMuted,
        letterSpacing = 0.5.sp
      )
    }
  }
}
