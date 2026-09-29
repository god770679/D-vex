package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.RecentAppEntry
import com.example.permissions.DvexPermissionManager
import com.example.ui.theme.DvexBlack
import com.example.ui.theme.DvexBorderMuted
import com.example.ui.theme.DvexBorderRed
import com.example.ui.theme.DvexNeonRed
import com.example.ui.theme.DvexNeonRedBright
import com.example.ui.theme.DvexNeonRedDim
import com.example.ui.theme.DvexSurfaceCard
import com.example.ui.theme.DvexTextMuted
import com.example.ui.theme.DvexTextPrimary
import com.example.ui.theme.DvexTextSecondary

/**
 * On-screen RECENT APPS overlay (regression-audit fix: visible real data).
 *
 * Backed ONLY by real UsageStatsManager entries resolved through PackageManager —
 * the same real pipeline as the voice "recent apps" flow. Never fabricates:
 *  - Usage access not granted -> explicit permission state with a Settings deep-link.
 *  - Granted but genuinely empty -> honest empty message.
 *  - Real usage -> real app labels + last-used relative times, newest first.
 *
 * @param permissionRequired true when usage access is denied; renders the honest
 *   "usage access required" state and deep-links to Settings on tap.
 * @param entries real usage-stats entries (empty when granted-but-empty).
 * @param onDismiss closes the overlay.
 */
@Composable
fun RecentAppsOverlay(
  permissionRequired: Boolean,
  entries: List<RecentAppEntry>,
  onDismiss: () -> Unit
) {
  val context = LocalContext.current

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 18.dp)
        .background(DvexBlack.copy(alpha = 0.97f), CutCornerShape(6.dp))
        .border(1.dp, DvexBorderRed, CutCornerShape(6.dp))
        .semantics { contentDescription = "Recent apps overlay, real app usage data" }
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(12.dp)
      ) {
        // Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Filled.History,
            contentDescription = null,
            tint = DvexNeonRedBright,
            modifier = Modifier.size(16.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "RECENT APPS",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            color = DvexNeonRedBright,
            modifier = Modifier.weight(1f)
          )
          Text(
            text = when {
              permissionRequired -> "ACCESS REQUIRED"
              entries.isEmpty() -> "0 LOGS"
              else -> "${entries.size} LIVE"
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = if (permissionRequired) DvexNeonRed else DvexNeonRedDim
          )
        }

        Spacer(
          modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(top = 8.dp)
            .background(
              Brush.horizontalGradient(
                listOf(DvexBorderRed, DvexBorderMuted.copy(alpha = 0.3f), DvexBorderRed)
              )
            )
        )
        Spacer(modifier = Modifier.height(8.dp))

        when {
          permissionRequired -> {
            // Honest permission-required state; tap opens the Usage access settings.
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  context.startActivity(
                    DvexPermissionManager.createUsageAccessSettingsIntent()
                  )
                }
                .padding(vertical = 18.dp, horizontal = 6.dp)
            ) {
              Text(
                text = "USAGE ACCESS REQUIRED",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = DvexNeonRedBright
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = "D-VEX needs Usage access to list your real recently used apps. " +
                  "Tap to open Settings > Apps > Special app access > Usage access, " +
                  "then enable D-VEX and try again.",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = DvexTextMuted
              )
            }
          }

          entries.isEmpty() -> {
            // Granted-but-empty: honest message, no placeholder apps.
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 6.dp)
            ) {
              Text(
                text = "NO USAGE RECORDED",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = DvexTextMuted
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = "No recent app activity found in the usage stats window. " +
                  "Open a few apps and check again.",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = DvexTextMuted
              )
            }
          }

          else -> {
            // Real data: app label + relative last-used time, newest first.
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
            ) {
              entries.forEachIndexed { index, app ->
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    text = "%02d".format(index + 1),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = DvexNeonRedDim,
                    modifier = Modifier.width(24.dp)
                  )
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = app.appName,
                      fontFamily = FontFamily.Monospace,
                      fontSize = 11.sp,
                      fontWeight = FontWeight.Bold,
                      color = DvexTextPrimary
                    )
                    Text(
                      text = app.packageName,
                      fontFamily = FontFamily.Monospace,
                      fontSize = 7.sp,
                      color = DvexTextSecondary
                    )
                  }
                  Text(
                    text = app.lastUsedLabel,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = DvexNeonRedBright
                  )
                }
                if (index < entries.lastIndex) {
                  Box(
                    modifier = Modifier
                      .fillMaxWidth()
                      .height(0.5.dp)
                      .background(DvexBorderMuted.copy(alpha = 0.4f))
                  )
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))
        // Close control (48dp minimum target).
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(DvexSurfaceCard, CutCornerShape(4.dp))
            .border(1.dp, DvexBorderRed, CutCornerShape(4.dp))
            .clickable(onClick = onDismiss)
            .semantics { contentDescription = "Close recent apps overlay" },
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = "CLOSE",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            color = DvexTextSecondary
          )
        }
      }
    }
  }
}
