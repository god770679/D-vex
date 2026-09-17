package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.remote.PendingConfirmation
import com.example.ui.theme.DvexBlack
import com.example.ui.theme.DvexNeonRed
import com.example.ui.theme.DvexTextMuted
import com.example.ui.theme.DvexTextPrimary
import com.example.ui.theme.DvexWarning

@Composable
fun SafetyConfirmationDialog(
  pending: PendingConfirmation,
  onConfirm: () -> Unit,
  onCancel: () -> Unit
) {
  Dialog(onDismissRequest = onCancel) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .clip(CutCornerShape(10.dp))
        .background(DvexBlack)
        .border(1.5.dp, DvexWarning, CutCornerShape(10.dp))
        .padding(18.dp)
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Warning",
            tint = DvexWarning,
            modifier = Modifier.padding(end = 8.dp)
          )
          Text(
            text = "TACTICAL SAFETY CONFIRMATION",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = DvexWarning
          )
        }

        Text(
          text = pending.prompt,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 13.sp,
          color = DvexTextPrimary
        )

        Text(
          text = pending.details,
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
          color = DvexTextMuted
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End
        ) {
          OutlinedButton(
            onClick = onCancel,
            shape = CutCornerShape(4.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
          ) {
            Text("CANCEL", fontFamily = FontFamily.Monospace)
          }

          Spacer(modifier = Modifier.width(10.dp))

          ElevatedButton(
            onClick = onConfirm,
            shape = CutCornerShape(4.dp),
            colors = ButtonDefaults.elevatedButtonColors(
              containerColor = DvexNeonRed,
              contentColor = Color.White
            )
          ) {
            Text("CONFIRM ACTION", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}
