package com.example.slopradar

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var overlayGranted by mutableStateOf(false)
    private var isServiceRunning by mutableStateOf(false)
    private var sensitivityThreshold by mutableStateOf(0.85f)
    private var hfApiToken by mutableStateOf("")

    // Activity launcher for capturing MediaProjection permission dialog result
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            isServiceRunning = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Load settings from SharedPreferences
        val prefs = getSharedPreferences("slop_radar_prefs", Context.MODE_PRIVATE)
        sensitivityThreshold = prefs.getFloat("sensitivity_threshold", 0.85f)
        hfApiToken = prefs.getString("hf_api_token", "") ?: ""

        setContent {
            SlopRadarTheme {
                MainScreen(
                    overlayGranted = overlayGranted,
                    isServiceRunning = isServiceRunning,
                    sensitivityThreshold = sensitivityThreshold,
                    hfApiToken = hfApiToken,
                    onSensitivityChanged = { value ->
                        sensitivityThreshold = value
                        getSharedPreferences("slop_radar_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putFloat("sensitivity_threshold", value)
                            .apply()
                    },
                    onHfTokenChanged = { token ->
                        hfApiToken = token
                        getSharedPreferences("slop_radar_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("hf_api_token", token)
                            .apply()
                    },
                    onGrantOverlayClick = { requestOverlayPermission() },
                    onToggleServiceClick = { toggleRadarService() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Sync permission, service, and settings states reactively
        overlayGranted = Settings.canDrawOverlays(this)
        isServiceRunning = ScreenCaptureService.isRunning
        
        val prefs = getSharedPreferences("slop_radar_prefs", Context.MODE_PRIVATE)
        sensitivityThreshold = prefs.getFloat("sensitivity_threshold", 0.85f)
        hfApiToken = prefs.getString("hf_api_token", "") ?: ""
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun toggleRadarService() {
        if (isServiceRunning) {
            val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP
            }
            startService(stopIntent)
            isServiceRunning = false
        } else {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }
}

@Composable
fun SlopRadarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFE53935),
            background = Color(0xFF0F0F13),
            surface = Color(0xFF1E1E24)
        ),
        content = content
    )
}

@Composable
fun MainScreen(
    overlayGranted: Boolean,
    isServiceRunning: Boolean,
    sensitivityThreshold: Float,
    hfApiToken: String,
    onSensitivityChanged: (Float) -> Unit,
    onHfTokenChanged: (String) -> Unit,
    onGrantOverlayClick: () -> Unit,
    onToggleServiceClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F0F13), Color(0xFF1F1214))
                )
            )
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // App Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = "SLOP RADAR",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp,
                    color = Color.White,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Real-time AI Content Shield",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    letterSpacing = 1.sp
                )
            }

            // Status Card
            StatusCard(isServiceRunning = isServiceRunning)

            // Sensitivity Tuning Slider
            SensitivityTuningCard(
                threshold = sensitivityThreshold,
                onThresholdChanged = onSensitivityChanged
            )

            // Hugging Face Cloud Configuration
            CloudSettingsCard(
                token = hfApiToken,
                onTokenChanged = onHfTokenChanged
            )

            // Telemetry indicators
            MetricsCard()

            // Control Actions & Permissions
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PermissionStatusIndicator(
                    granted = overlayGranted,
                    onGrantClick = onGrantOverlayClick
                )

                Spacer(modifier = Modifier.height(12.dp))

                val buttonColor = if (isServiceRunning) Color(0xFFD32F2F) else Color(0xFF2979FF)
                Button(
                    onClick = onToggleServiceClick,
                    enabled = overlayGranted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(
                            width = 1.dp,
                            color = if (overlayGranted) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                            shape = RoundedCornerShape(18.dp)
                        ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonColor,
                        disabledContainerColor = Color(0xFF26262B)
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        text = if (isServiceRunning) "DEACTIVATE RADAR" else "ACTIVATE RADAR SHIELD",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = if (overlayGranted) Color.White else Color.Gray
                    )
                }

                if (!overlayGranted) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "System Overlay permission is required to display notifications.",
                        fontSize = 11.sp,
                        color = Color(0xFFE53935).copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun StatusCard(isServiceRunning: Boolean) {
    val statusColor = if (isServiceRunning) Color(0xFF4CAF50) else Color(0xFFE53935)
    val statusText = if (isServiceRunning) "SHIELD ACTIVE" else "SHIELD INACTIVE"
    val glowColor = statusColor.copy(alpha = 0.15f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF16161A))
            .border(
                width = 2.dp,
                brush = Brush.radialGradient(
                    colors = listOf(statusColor.copy(alpha = 0.4f), Color.Transparent),
                    radius = 350f
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(glowColor)
                    .border(2.dp, statusColor, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isServiceRunning) "📡" else "🔒",
                    fontSize = 24.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = statusText,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = if (isServiceRunning) "Scanning screen frames at 1 FPS" else "Standby (Awaiting Activation)",
                fontSize = 11.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun SensitivityTuningCard(
    threshold: Float,
    onThresholdChanged: (Float) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF26262B), RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16161A)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Detection Sensitivity",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "${(threshold * 100).roundToInt()}%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2979FF)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Minimum confidence threshold to flag AI content",
                fontSize = 11.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = threshold,
                onValueChange = onThresholdChanged,
                valueRange = 0.50f..0.99f,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color(0xFF2979FF),
                    inactiveTrackColor = Color(0xFF26262B),
                    thumbColor = Color.White
                ),
                modifier = Modifier.height(24.dp)
            )
        }
    }
}

@Composable
fun CloudSettingsCard(
    token: String,
    onTokenChanged: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF26262B), RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16161A)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Text(
                text = "Hugging Face Cloud Config",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Add a free HF User Token to enable cloud classification",
                fontSize = 11.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChanged,
                label = { Text("Hugging Face API Token", fontSize = 11.sp) },
                singleLine = true,
                placeholder = { Text("hf_...", color = Color.DarkGray) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF2979FF),
                    unfocusedBorderColor = Color(0xFF26262B),
                    focusedLabelColor = Color(0xFF2979FF),
                    unfocusedLabelColor = Color.Gray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        }
    }
}

@Composable
fun MetricsCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF121215))
            .border(1.dp, Color(0xFF1F1F24), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "1 FPS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(text = "Scan Freq", fontSize = 9.sp, color = Color.Gray)
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(20.dp)
                .background(Color(0xFF1F1F24))
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Background", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(text = "Thread Mode", fontSize = 9.sp, color = Color.Gray)
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(20.dp)
                .background(Color(0xFF1F1F24))
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Hybrid API", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(text = "Detection Mode", fontSize = 9.sp, color = Color.Gray)
        }
    }
}

@Composable
fun PermissionStatusIndicator(
    granted: Boolean,
    onGrantClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1A1A1E))
            .border(1.dp, Color(0xFF26262B), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "Draw Over Other Apps",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = if (granted) "Status: Enabled" else "Status: Disabled",
                fontSize = 11.sp,
                color = if (granted) Color(0xFF4CAF50) else Color(0xFFE53935)
            )
        }

        if (!granted) {
            Button(
                onClick = onGrantClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("ENABLE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Text("✅", fontSize = 16.sp)
        }
    }
}
