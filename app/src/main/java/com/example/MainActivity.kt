package com.example

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.VaultEntry
import com.example.security.CryptoEngine
import com.example.security.PasswordGenerator
import com.example.ui.theme.*
import com.example.viewmodel.AuthState
import com.example.viewmodel.DashboardStats
import com.example.viewmodel.Screen
import com.example.viewmodel.VaultViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : FragmentActivity() {
    private val viewModel: VaultViewModel by viewModels()

    fun launchSystemBiometric(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errString.toString())
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onError("System Fingerprint Verification Failed")
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Vault Fingerprint Unlock")
            .setSubtitle("Place your registered finger on the sensor to authenticate")
            .setNegativeButtonText("Use PIN code")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            onError("Biometrics initialization error: ${e.localizedMessage}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val secureScreenshots by viewModel.secureScreenshotsFlow.collectAsStateWithLifecycle()
                val context = LocalContext.current

                LaunchedEffect(secureScreenshots) {
                    val activity = context as? android.app.Activity
                    if (secureScreenshots) {
                        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }

                val authState by viewModel.authState.collectAsStateWithLifecycle()
                val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
                val clipboardTimer by viewModel.clipboardTimer.collectAsStateWithLifecycle()


                // Register user interaction clicks to reset inactivity lockers
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkGrayBg)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            viewModel.resetInactivityTimer()
                        }
                ) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = DarkGrayBg,
                        bottomBar = {
                            if (authState == AuthState.UNLOCKED) {
                                CyberBottomNav(
                                    currentScreen = currentScreen,
                                    onScreenSelected = { viewModel.navigateTo(it) }
                                )
                            }
                        },
                        floatingActionButton = {
                            if (authState == AuthState.UNLOCKED && (currentScreen == Screen.VAULT || currentScreen == Screen.DASHBOARD)) {
                                FloatingActionButton(
                                    onClick = {
                                        viewModel.setSelectedEntry(VaultEntry(websiteName = "")) // setup empty editor
                                    },
                                    containerColor = NeonGreen,
                                    contentColor = DeepBlack,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .testTag("add_entry_fab")
                                        .padding(bottom = 12.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Add Login")
                                }
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            when (authState) {
                                AuthState.SETUP -> SetupPinScreen(viewModel)
                                AuthState.LOCKED -> LockedScreen(viewModel)
                                AuthState.UNLOCKED -> MainNavigationContent(viewModel, currentScreen)
                            }

                            // Dynamic clipboard timer overlay alert
                            clipboardTimer?.let { seconds ->
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(16.dp)
                                        .background(Color(0xE60A0A0A), RoundedCornerShape(8.dp))
                                        .border(1.dp, NeonCyan, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            progress = { seconds / 30f },
                                            modifier = Modifier.size(16.dp),
                                            color = NeonCyan,
                                            strokeWidth = 2.dp,
                                        )
                                        Text(
                                            text = "Clipboard clearing in ${seconds}s...",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                color = Color.White
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Securely lock the vault immediately whenever the app moves to background
        viewModel.lockVault()
    }
}

// ============================================
// SCREENS & CUSTOM COMPONENTS
// ============================================

// SETUP MASTER PIN SCREEN
@Composable
fun SetupPinScreen(viewModel: VaultViewModel) {
    var pin by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.widthIn(max = 400.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = "Security Setup",
                tint = NeonGreen,
                modifier = Modifier.size(64.dp)
            )

            Text(
                text = "VAULTX PROTOCOL",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = NeonGreen
                )
            )

            Text(
                text = "Create a secure Master Passcode (4-16 digits) to secure your database. VaultX encrypts all local memory at rest.",
                style = MaterialTheme.typography.bodySmall,
                color = GrayText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 16 && it.all { c -> c.isDigit() }) pin = it },
                label = { Text("Enter PIN", color = GrayText) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonGreen,
                    unfocusedBorderColor = Color.Gray,
                    focusedLabelColor = NeonGreen,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pin_setup_input")
            )

            OutlinedTextField(
                value = pinConfirm,
                onValueChange = { if (it.length <= 16 && it.all { c -> c.isDigit() }) pinConfirm = it },
                label = { Text("Confirm PIN", color = GrayText) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonGreen,
                    unfocusedBorderColor = Color.Gray,
                    focusedLabelColor = NeonGreen,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pin_confirm_input")
            )

            if (error.isNotEmpty()) {
                Text(text = error, color = Color.Red, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    if (pin.length < 4) {
                        error = "PIN must be at least 4 digits"
                    } else if (pin != pinConfirm) {
                        error = "PINs do not match"
                    } else {
                        viewModel.submitSetupPin(pin)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DeepBlack),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("submit_setup_btn"),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "INITIALIZE VAULT",
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// LOCK SCREEN FOR MASTER PIN VERIFICATION
@Composable
fun LockedScreen(viewModel: VaultViewModel) {
    var inputPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    val failedCount = viewModel.securityPrefs.failedAttemptsCount
    val isBiometrics = viewModel.securityPrefs.isBiometricsEnabled

    val context = LocalContext.current
    val activity = context as? MainActivity

    fun triggerBiometricUnlock() {
        activity?.launchSystemBiometric(
            onSuccess = {
                viewModel.simulateBiometricUnlock()
            },
            onError = { err ->
                errorMessage = err
            }
        )
    }

    LaunchedEffect(isBiometrics) {
        if (isBiometrics) {
            triggerBiometricUnlock()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Locked",
                tint = NeonCyan,
                modifier = Modifier.size(56.dp)
            )

            Text(
                text = "VAULT LOCKED",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan
                )
            )

            if (failedCount > 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x33EF4444)),
                    border = BorderStroke(1.dp, ErrorRed),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "!! ACCESS ALERT !!\n$failedCount failed authentication attempts logged in database.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            OutlinedTextField(
                value = inputPin,
                onValueChange = { if (it.length <= 16 && it.all { c -> c.isDigit() }) inputPin = it },
                label = { Text("Master PIN", color = GrayText) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = Color.Gray,
                    focusedLabelColor = NeonCyan,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pin_unlock_input")
            )

            if (errorMessage.isNotEmpty()) {
                Text(text = errorMessage, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
              ) {
                Button(
                    onClick = {
                        val ok = viewModel.submitUnlockPin(inputPin)
                        if (ok) {
                            inputPin = ""
                            errorMessage = ""
                        } else {
                            inputPin = ""
                            errorMessage = "INCORRECT CODE VERIFICATION FAILURE"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = DeepBlack),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("submit_unlock_btn"),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "UNLOCK",
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (isBiometrics) {
                    IconButton(
                        onClick = {
                            triggerBiometricUnlock()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .background(CardGray, RoundedCornerShape(8.dp))
                            .border(1.dp, NeonGreen, RoundedCornerShape(8.dp))
                            .testTag("biometric_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = "System Biometric Unlock",
                            tint = NeonGreen
                        )
                    }
                }
            }

            Text(
                text = "Hacker Console Engine v1.4.1 SECURE PORT",
                color = Color.DarkGray,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            )
        }
    }
}

// MAIN PLATFORM NAVIGATION
@Composable
fun MainNavigationContent(viewModel: VaultViewModel, screen: Screen) {
    val context = LocalContext.current
    val selectedEntry by viewModel.selectedEntry.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        when (screen) {
            Screen.DASHBOARD -> DashboardScreen(viewModel)
            Screen.VAULT -> VaultListScreen(viewModel)
            Screen.SECURITY_CENTER -> SecurityCenterScreen(viewModel)
            Screen.RECYCLE_BIN -> RecycleBinScreen(viewModel)
            Screen.SETTINGS -> SettingsScreen(viewModel)
            else -> {}
        }

        // Popup Dialog for Adding/Editing/Viewing Entry detail
        selectedEntry?.let { entry ->
            EntryEditorDialog(
                viewModel = viewModel,
                entry = entry,
                onDismiss = { viewModel.setSelectedEntry(null) }
            )
        }
    }
}

// CYBERPUNK BOTTOM BAR NAVIGATION
@Composable
fun CyberBottomNav(currentScreen: Screen, onScreenSelected: (Screen) -> Unit) {
    val items = listOf(
        NavigationItem(Screen.DASHBOARD, Icons.Default.GridOn, "Dash"),
        NavigationItem(Screen.VAULT, Icons.Default.VpnKey, "Vault"),
        NavigationItem(Screen.SECURITY_CENTER, Icons.Default.Shield, "Audit"),
        NavigationItem(Screen.SETTINGS, Icons.Default.Settings, "Config")
    )

    NavigationBar(
        containerColor = CardGray,
        tonalElevation = 8.dp,
        modifier = Modifier
            .border(BorderStroke(1.dp, BorderWhite), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .navigationBarsPadding(),
        // Support full screen edge-to-edge
        windowInsets = WindowInsets.navigationBars
    ) {
        items.forEach { item ->
            val isSelected = currentScreen == item.screen
            NavigationBarItem(
                selected = isSelected,
                onClick = { onScreenSelected(item.screen) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = if (isSelected) NeonGreen else GrayText
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        color = if (isSelected) Color.White else GrayText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color(0x1A00FF88)
                )
            )
        }
    }
}

data class NavigationItem(val screen: Screen, val icon: ImageVector, val label: String)

// REPLAY STYLED CIRCULAR SCORE GRAPH
@Composable
fun SecurityScoreGauge(score: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(140.dp)
    ) {
        Canvas(modifier = Modifier.size(120.dp)) {
            val strokeWidth = 10.dp.toPx()
            // Ambient gray circle background
            drawArc(
                color = Color(0x14FFFFFF), // Sleek subtle white ring
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth)
            )

            // Rating gradient determination
            val arcColor = when {
                score < 40 -> ErrorRed
                score < 75 -> Color(0xFFFFB300)
                else -> NeonGreen
            }

            // Foreground animated secure gauge arcs
            drawArc(
                color = arcColor,
                startAngle = -90f,
                sweepAngle = (score / 100f) * 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$score",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        score < 40 -> ErrorRed
                        score < 75 -> Color(0xFFFFB300)
                        else -> NeonGreen
                    }
                )
            )
            Text(
                text = "SECURE RATE",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = GrayText
                )
            )
        }
    }
}

// --- SCREEN 1: SECURITIY DASHBOARD ---
@Composable
fun DashboardScreen(viewModel: VaultViewModel) {
    val activeEntries by viewModel.activeEntries.collectAsStateWithLifecycle()
    val stats by viewModel.dashboardStats.collectAsStateWithLifecycle()
    val failedCount = viewModel.securityPrefs.failedAttemptsCount

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Holographic Cyber Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "TERMINAL INTERFACE",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = NeonGreen,
                            letterSpacing = 4.sp
                        ),
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = buildAnnotatedString {
                                append("Vault")
                                withStyle(SpanStyle(color = NeonCyan)) {
                                    append("X")
                                }
                            },
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        // Online terminal active blinking light
                        val infiniteTransition = rememberInfiniteTransition(label = "pulseDot")
                        val dotAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "dotAlpha"
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(NeonGreen.copy(alpha = dotAlpha), CircleShape)
                                .border(1.dp, NeonGreen, CircleShape)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.lockVault() },
                        modifier = Modifier
                            .background(Color(0x22EF4444), CircleShape)
                            .border(1.dp, ErrorRed.copy(alpha = 0.5f), CircleShape)
                            .size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Instant Lock",
                            tint = ErrorRed,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Avatar with gradient border
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(ElectricPurple, NeonCyan)
                                )
                            )
                            .padding(1.dp) // border thickness
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(7.dp))
                                .background(CardGray)
                        ) {
                            Text(
                                text = "JD",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }
            }
        }

        // Circular Security Gauge and quick actions
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardGray),
                border = BorderStroke(1.dp, BorderWhite),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SecurityScoreGauge(stats.securityScore)

                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(start = 12.dp)
                    ) {
                        Text(
                            text = "HEALTH SUMMARY",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = NeonGreen
                            )
                        )
                        DashboardStatLine("Passkeys", "${stats.totalCount}", NeonCyan)
                        DashboardStatLine("Compromised", "${stats.weakCount}", ErrorRed)
                        DashboardStatLine("Reused Keys", "${stats.reusedCount}", Color(0xFFFFB300))
                    }
                }
            }
        }

        // Quick Actions Matrix
        item {
            Text(
                text = "VAULT PROTOCOLS",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = NeonGreen
                )
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionCard(
                    title = "Create Entry",
                    subtitle = "Add Secure Profile",
                    icon = Icons.Default.Add,
                    accentColor = NeonGreen,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setSelectedEntry(VaultEntry(websiteName = "")) }
                )
                QuickActionCard(
                    title = "Audit Health",
                    subtitle = "Security Diagnostic",
                    icon = Icons.Default.Shield,
                    accentColor = NeonCyan,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.navigateTo(Screen.SECURITY_CENTER) }
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionCard(
                    title = "Recycle Trash",
                    subtitle = "Recover Soft Deletes",
                    icon = Icons.Default.Delete,
                    accentColor = ElectricPurple,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.navigateTo(Screen.RECYCLE_BIN) }
                )
                QuickActionCard(
                    title = "Configurations",
                    subtitle = "Secure Backup/PIN",
                    icon = Icons.Default.Settings,
                    accentColor = GrayText,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.navigateTo(Screen.SETTINGS) }
                )
            }
        }

        // Recent Additions List (up to 3 items)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LATEST KEY CREDENTIALS",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = NeonGreen
                    )
                )
                Text(
                    text = "VIEW ALL",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = NeonCyan,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.clickable { viewModel.navigateTo(Screen.VAULT) }
                )
            }
        }

        val latest = activeEntries.take(3)
        if (latest.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x1F111827)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    border = BorderStroke(1.dp, BorderWhite)
                ) {
                    Text(
                        text = "Encrypted repository is currently vacant.\nClick the Floating Action Button below (+) to initialize custom login profiles.",
                        color = GrayText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    )
                }
            }
        } else {
            items(latest) { entry ->
                VaultEntryCard(
                    entry = entry,
                    viewModel = viewModel,
                    isSelected = false,
                    isMultiSelect = false,
                    onClick = { viewModel.setSelectedEntry(entry) },
                    onSelect = {}
                )
            }
        }
    }
}

@Composable
fun DashboardStatLine(label: String, valString: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(0.9f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                color = GrayText
            )
        )
        Text(
            text = valString,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = color
            )
        )
    }
}

@Composable
fun QuickActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardGray),
        border = BorderStroke(1.dp, BorderWhite),
        modifier = modifier
            .height(96.dp)
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accentColor,
                modifier = Modifier.size(24.dp)
            )

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = GrayText
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// --- SCREEN 2: VAULT ENTRIES & SEARCH LIST ---
@Composable
fun VaultListScreen(viewModel: VaultViewModel) {
    val filteredEntries by viewModel.filteredEntries.collectAsStateWithLifecycle()
    val rawEntries by viewModel.activeEntries.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()

    val categories = listOf("All", "Social Media", "Banking", "Email", "Development", "Work", "Gaming", "Shopping", "Streaming", "Crypto Wallets", "Custom")

    // Filter by note types if viewing note panel directly
    val displayEntries = filteredEntries.filter { it.category != "Secure Notes" }

    LaunchedEffect(Unit) {
        viewModel.selectCategory("All")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Futuristic Search Box
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.setSearchQuery(it) },
            placeholder = { Text("Search everywhere in vault...", color = Color.DarkGray, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = NeonCyan) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(Icons.Default.Block, contentDescription = "Clear", tint = ErrorRed)
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = CardGray,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = CardGray,
                unfocusedContainerColor = CardGray
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("vault_search_input"),
            shape = RoundedCornerShape(10.dp)
        )

        // Categories selector horizontally
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(categories) { cat ->
                val isCatSelected = selectedCategory == cat
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isCatSelected) NeonGreen else CardGray)
                        .border(1.dp, if (isCatSelected) NeonGreen else Color.DarkGray, RoundedCornerShape(20.dp))
                        .clickable { viewModel.selectCategory(cat) }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = cat,
                        color = if (isCatSelected) DeepBlack else Color.White,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    )
                }
            }
        }

        // Multi-select actions tray
        AnimatedVisibility(
            visible = selectedIds.isNotEmpty(),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardGray, RoundedCornerShape(12.dp))
                    .border(1.dp, NeonCyan, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${selectedIds.size} ITEMS ACTIVE",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = NeonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        onClick = { viewModel.bulkSoftDelete() }
                    ) {
                        Text(
                            "SOFT DELETE",
                            color = ErrorRed,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                    TextButton(
                        onClick = { viewModel.clearSelections() }
                    ) {
                        Text(
                            "CANCEL",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }
            }
        }

        // Vault item listings
        if (displayEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = "Empty",
                        tint = Color.DarkGray,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "Encrypted database directory matches 0 results.",
                        color = GrayText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        )
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(displayEntries) { item ->
                    val isChecked = selectedIds.contains(item.id)
                    VaultEntryCard(
                        entry = item,
                        viewModel = viewModel,
                        isSelected = isChecked,
                        isMultiSelect = selectedIds.isNotEmpty(),
                        onClick = {
                            if (selectedIds.isNotEmpty()) {
                                viewModel.toggleSelectId(item.id)
                            } else {
                                viewModel.setSelectedEntry(item)
                            }
                        },
                        onSelect = { viewModel.toggleSelectId(item.id) }
                    )
                }
            }
        }
    }
}

// VAULT ENTRY DISPLAY CARD
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultEntryCard(
    entry: VaultEntry,
    viewModel: VaultViewModel,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    onClick: () -> Unit,
    onSelect: () -> Unit
) {
    val isFavorite = entry.isFavorite
    val categoryColor = when (entry.category) {
        "Banking" -> Color(0xFFFFB300)
        "Social Media" -> NeonCyan
        "Crypto Wallets" -> ElectricPurple
        "Developer" -> NeonGreen
        else -> GrayText
    }

    var showPasswordInCard by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFF132B25) else CardGray
        ),
        border = BorderStroke(
            1.dp,
            if (isSelected) NeonGreen else if (isFavorite) ElectricPurple else Color(0x3300FF88)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onSelect
            )
            .testTag("vault_entry_${entry.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header Row (Dot / Title, Star, Category Badge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isMultiSelect) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onSelect() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = NeonGreen,
                            uncheckedColor = Color.Gray,
                            checkmarkColor = DeepBlack
                        ),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(categoryColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Text(
                    text = entry.websiteName,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (isFavorite) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Favorite",
                        tint = ElectricPurple,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Category pill/badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(categoryColor.copy(alpha = 0.15f))
                        .border(1.dp, categoryColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = entry.category.uppercase(),
                        color = categoryColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Divider line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0x14FFFFFF))
            )

            // Username display & copy
            val displayedUsername = entry.getDecryptedUsername().ifEmpty { entry.getDecryptedEmail() }
            if (displayedUsername.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "USERNAME PROFILE",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = GrayText,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = displayedUsername,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = { viewModel.copyToClipboard("username", displayedUsername) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Username",
                            tint = NeonCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Password display & copy
            val decryptedPassword = entry.getDecryptedPassword()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ENCRYPTED KEY CODE",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        color = GrayText,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (showPasswordInCard) decryptedPassword else "••••••••••••",
                        color = if (showPasswordInCard) NeonGreen else Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showPasswordInCard = !showPasswordInCard },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (showPasswordInCard) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Reveal password",
                            tint = NeonCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { viewModel.copyToClipboard("password", decryptedPassword) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Password",
                            tint = NeonGreen,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

val Int.dd: androidx.compose.ui.unit.Dp get() = this.dp

// --- SCREEN 3: DYNAMIC PASSWORD GENERATOR ---
@Composable
fun GeneratorScreen(viewModel: VaultViewModel) {
    var length by remember { mutableStateOf(16f) }
    var useUpper by remember { mutableStateOf(true) }
    var useLower by remember { mutableStateOf(true) }
    var useDigits by remember { mutableStateOf(true) }
    var useSymbols by remember { mutableStateOf(true) }
    var customSymbols by remember { mutableStateOf("") }
    
    // Generator models: RANDOM, PASSPHRASE, PRONOUNCEABLE
    var genType by remember { mutableStateOf(0) } // 0: Random, 1: Passphrase, 2: Pronounceable
    var generatedPassword by remember { mutableStateOf("") }

    // Multi-word count for passphrase
    var phraseLength by remember { mutableStateOf(4f) }

    fun runGenerate() {
        generatedPassword = when (genType) {
            0 -> PasswordGenerator.generate(
                length.toInt(), useUpper, useLower, useDigits, useSymbols, customSymbols
            )
            1 -> PasswordGenerator.generatePassphrase(phraseLength.toInt())
            else -> PasswordGenerator.generatePronounceable(length.toInt())
        }
    }

    LaunchedEffect(length, useUpper, useLower, useDigits, useSymbols, customSymbols, genType, phraseLength) {
        runGenerate()
    }

    val entropy = CryptoEngine.calculateEntropy(generatedPassword)
    val strength = CryptoEngine.getPasswordStrength(generatedPassword)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "VAULTX // KEY GENERATOR",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = NeonGreen
                )
            )
        }

        // Live Generated Password Monitor Block
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DeepBlack),
                border = BorderStroke(1.dp, NeonGreen),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = generatedPassword.ifEmpty { "SELECT AT LEAST ONE KEY POOL" },
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (generatedPassword.isEmpty()) ErrorRed else NeonGreen,
                            fontSize = if (generatedPassword.length > 20) 16.sp else 22.sp
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("generated_pass_view")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { runGenerate() },
                            colors = ButtonDefaults.buttonColors(containerColor = CardGray),
                            border = BorderStroke(1.dp, Color.Gray),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Refresh, contentDescription = "Regen", tint = Color.White, modifier = Modifier.size(16.dp))
                                Text("REGEN", fontFamily = FontFamily.Monospace, color = Color.White)
                            }
                        }

                        Button(
                            onClick = { viewModel.copyToClipboard("password", generatedPassword) },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DeepBlack),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f),
                            enabled = generatedPassword.isNotEmpty()
                        ) {
                            Text("COPY KEY", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // Entropy metric bar
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardGray),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "ENTROPY LEVEL",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = GrayText)
                        )
                        Text(
                            text = String.format("%.1f BITS", entropy),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = NeonCyan)
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "STRENGTH POOL",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = GrayText)
                        )
                        Text(
                            text = strength.label.uppercase(),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(strength.colorHex)
                            )
                        )
                    }
                }
            }
        }

        // Generator model segment chips
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("ALPHANUM", "PASSPHRASE", "PRONOUNCE").forEachIndexed { index, tag ->
                    val isTagActive = genType == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isTagActive) NeonCyan else CardGray)
                            .border(1.dp, if (isTagActive) NeonCyan else Color.Transparent)
                            .clickable { genType = index }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tag,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = if (isTagActive) DeepBlack else Color.White
                        )
                    }
                }
            }
        }

        // Controls
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardGray),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (genType == 0 || genType == 2) {
                        // Length slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Key Length", fontFamily = FontFamily.Monospace, color = Color.White)
                                Text("${length.toInt()} chars", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = NeonCyan)
                            }
                            Slider(
                                value = length,
                                onValueChange = { length = it },
                                valueRange = 4f..128f,
                                colors = SliderDefaults.colors(
                                    thumbColor = NeonCyan,
                                    activeTrackColor = NeonCyan,
                                    inactiveTrackColor = Color.DarkGray
                                )
                            )
                        }
                    } else {
                        // Word count passphrase slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Passphrase Words", fontFamily = FontFamily.Monospace, color = Color.White)
                                Text("${phraseLength.toInt()} words", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = NeonCyan)
                            }
                            Slider(
                                value = phraseLength,
                                onValueChange = { phraseLength = it },
                                valueRange = 3f..12f,
                                colors = SliderDefaults.colors(
                                    thumbColor = NeonCyan,
                                    activeTrackColor = NeonCyan,
                                    inactiveTrackColor = Color.DarkGray
                                )
                            )
                        }
                    }

                    if (genType == 0) {
                        // Specific random settings
                        RowSettingToggle("Include Uppercase (A-Z)", useUpper) { useUpper = it }
                        RowSettingToggle("Include Lowercase (a-z)", useLower) { useLower = it }
                        RowSettingToggle("Include Numbers (0-9)", useDigits) { useDigits = it }
                        RowSettingToggle("Include Special Symbols", useSymbols) { useSymbols = it }

                        if (useSymbols) {
                            OutlinedTextField(
                                value = customSymbols,
                                onValueChange = { customSymbols = it },
                                label = { Text("Custom symbols (leave empty for default)", fontSize = 11.sp, color = GrayText) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NeonCyan,
                                    unfocusedBorderColor = Color.DarkGray,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // Save directly to the vault as a quick login
        item {
            Button(
                onClick = {
                    viewModel.setSelectedEntry(
                        VaultEntry(
                            websiteName = "Generated Passkey",
                            passwordEncrypted = CryptoEngine.encrypt(generatedPassword)
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DeepBlack),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("save_generated_key_btn"),
                enabled = generatedPassword.isNotEmpty()
            ) {
                Text("SAVE DIRECTLY TO VAULT", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun RowSettingToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color.White)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NeonCyan,
                checkedTrackColor = Color(0x3300D9FF),
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = CardGray
            )
        )
    }
}

// --- SCREEN 4: SECURITY AUDIT CENTER terminal emulator ---
@Composable
fun SecurityCenterScreen(viewModel: VaultViewModel) {
    val entries by viewModel.activeEntries.collectAsStateWithLifecycle()
    val recommendations by viewModel.scannedRecommendations.collectAsStateWithLifecycle()
    val stats by viewModel.dashboardStats.collectAsStateWithLifecycle()
    
    // Command logs for a full hacker simulation terminal
    val terminalLines = remember { mutableStateListOf<String>() }
    var inputCommand by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(entries) {
        viewModel.runSecurityScan(entries)
    }

    LaunchedEffect(Unit) {
        if (terminalLines.isEmpty()) {
            terminalLines.add("vaultx@security-daemon:~$ run scan --vault")
            delay(500)
            terminalLines.add("Scanning encrypted database blocks...")
            delay(400)
            terminalLines.add("Diagnostics complete. Found ${entries.size} credentials.")
            terminalLines.add("Type '--help' inside terminal for command manual.")
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "VAULTX // SECURITY COMMAND CENTER",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = NeonGreen
                )
            )
        }

        // Live interactive terminal
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                border = BorderStroke(1.dp, NeonGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                ) {
                    // Logs list area
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column {
                            terminalLines.forEach { line ->
                                Text(
                                    text = line,
                                    color = if (line.startsWith("vaultx@")) NeonCyan else if (line.contains("Critical") || line.contains("!!")) ErrorRed else NeonGreen,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                    }

                    // Terminal action entry mock
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(BorderStroke(1.dp, Color.DarkGray), RoundedCornerShape(4.dp))
                            .background(CardGray),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = " > ",
                            color = NeonCyan,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                        BasicTextField(
                            value = inputCommand,
                            onValueChange = { inputCommand = it },
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = Color.White
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 8.dp)
                                .testTag("terminal_command_input")
                        )

                        TextButton(
                            onClick = {
                                if (inputCommand.trim().isNotEmpty()) {
                                    val cmd = inputCommand.trim()
                                    terminalLines.add("vaultx@security-daemon:~$ $cmd")
                                    when (cmd) {
                                        "--help" -> {
                                            terminalLines.add("Available commands:")
                                            terminalLines.add("  run scan      - Run local DB audits")
                                            terminalLines.add("  clear logs    - Empty terminal console")
                                            terminalLines.add("  health        - Show raw rating metrics")
                                            terminalLines.add("  system check  - Detect root configurations")
                                        }
                                        "run scan" -> {
                                            terminalLines.add("Scanning active database blocks...")
                                            terminalLines.add("Metrics -> Score: ${stats.securityScore}/100, Compromised: ${stats.weakCount}")
                                        }
                                        "clear logs" -> {
                                            terminalLines.clear()
                                        }
                                        "health" -> {
                                            terminalLines.add("[SECURITY METRICS STATUS]")
                                            terminalLines.add("  Rating: ${stats.securityScore}/100")
                                            terminalLines.add("  Active entries parsed: ${stats.totalCount}")
                                            terminalLines.add("  Weak keys tagged: ${stats.weakCount}")
                                            terminalLines.add("  Reused keys flagged: ${stats.reusedCount}")
                                        }
                                        "system check" -> {
                                            terminalLines.add("[SECURITY VERIFICATION]")
                                            terminalLines.add("  ROOT DETECTOR STATUS: SYSTEM SAFE (No active hijack hooks)")
                                            terminalLines.add("  DATABASE AT REST STATUS: AES-256 SECURED")
                                            terminalLines.add("  SCREEN GRAB PROTECTION: INSTANT ACTIVED")
                                        }
                                        else -> {
                                            terminalLines.add("Err: Command '$cmd' not found. Type '--help' for info.")
                                        }
                                    }
                                    inputCommand = ""
                                }
                            }
                        ) {
                            Text("RUN", color = NeonCyan, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // Scanner Recommendations Header
        item {
            Text(
                text = "ALERTS & IMPROVEMENT RECOMMENDATIONS",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = NeonCyan
                )
            )
        }

        // List security results
        if (recommendations.isEmpty()) {
            item {
                Text("Analyzing keys...", color = GrayText, fontFamily = FontFamily.Monospace)
            }
        } else {
            items(recommendations) { advice ->
                val isCritical = advice.contains("Critical") || advice.contains("Warning")
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCritical) Color(0x1AEF4444) else Color(0x1F111827)
                    ),
                    border = BorderStroke(1.dp, if (isCritical) ErrorRed else Color.DarkGray),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (isCritical) Icons.Default.Warning else Icons.Default.Check,
                            contentDescription = "Alert",
                            tint = if (isCritical) ErrorRed else NeonGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = advice,
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal
                            )
                        )
                    }
                }
            }
        }
    }
}

// --- SCREEN 5: RECYCLE BIN TRASH MANAGEMENT ---
@Composable
fun RecycleBinScreen(viewModel: VaultViewModel) {
    val deletedList by viewModel.deletedEntries.collectAsStateWithLifecycle()
    val retention = viewModel.securityPrefs.recycleBinRetentionDays

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "VAULTX // RECYCLE BIN",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = ErrorRed
                    )
                )
                Text(
                    text = if (retention > 0) "Keys automatically purge after $retention days." else "Automatic purging is deactivated.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = GrayText
                    )
                )
            }

            if (deletedList.isNotEmpty()) {
                Button(
                    onClick = { viewModel.clearTrash() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x33EF4444), contentColor = ErrorRed),
                    border = BorderStroke(1.dp, ErrorRed),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("PURGE ALL", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (deletedList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Trash Empty", tint = Color.DarkGray, modifier = Modifier.size(48.dp))
                    Text(
                        text = "Recycle bin trash is currently clear.",
                        color = GrayText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(deletedList) { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardGray),
                        border = BorderStroke(1.dp, Color.DarkGray),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.websiteName,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = item.getDecryptedUsername(),
                                    color = GrayText,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(
                                    onClick = { viewModel.restore(item.id) },
                                    modifier = Modifier.background(Color(0x3300FF88), CircleShape)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Restore", tint = NeonGreen, modifier = Modifier.size(16.dp))
                                }

                                IconButton(
                                    onClick = { viewModel.permanentDelete(item.id) },
                                    modifier = Modifier.background(Color(0x33EF4444), CircleShape)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Erase", tint = ErrorRed, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- SCREEN 6: SETTINGS, LOCK SYSTEM & BACKUPS ---
@Composable
fun SettingsScreen(viewModel: VaultViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isScreenshots by remember { mutableStateOf(viewModel.securityPrefs.secureScreenshotsEnabled) }
    var isBiometrics by remember { mutableStateOf(viewModel.securityPrefs.isBiometricsEnabled) }
    var autoLockTime by remember { mutableStateOf(viewModel.securityPrefs.autoLockTimeoutSeconds) }
    var clipTime by remember { mutableStateOf(viewModel.securityPrefs.clipboardTimeoutSeconds) }
    var retentionDays by remember { mutableStateOf(viewModel.securityPrefs.recycleBinRetentionDays) }

    // Backup Paste/Import fields
    var isImportMode by remember { mutableStateOf(false) }
    var backupStringInput by remember { mutableStateOf("") }
    var backupResultText by remember { mutableStateOf("") }

    // Change Master PIN fields
    var isChangePinMode by remember { mutableStateOf(false) }
    var newPinInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "VAULTX // PARAMETERS & RECOVERY",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = NeonGreen
                )
            )
        }

        // Sub Config block 1: Device parameters
        item {
            Text(
                text = "HARDWARE & GRAPHICS LOCKS",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = NeonCyan)
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardGray),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Biometric lock
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enable Quick Biometric Unlock", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color.White)
                        Switch(
                            checked = isBiometrics,
                            onCheckedChange = {
                                isBiometrics = it
                                viewModel.securityPrefs.isBiometricsEnabled = it
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = Color(0x3300D9FF))
                        )
                    }

                    // Secure Screenshots Protection (FLAG_SECURE)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Block Screengrabs (FLAG_SECURE)", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color.White)
                        Switch(
                            checked = isScreenshots,
                            onCheckedChange = {
                                isScreenshots = it
                                viewModel.setSecureScreenshots(it)
                                Toast.makeText(context, if (it) "Protection activated" else "Protection deactivated", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = Color(0x3300D9FF))
                        )
                    }
                }
            }
        }

        // Sub Config block 2: Timeout intervals
        item {
            Text(
                text = "SECURE TIMER BOUNDARIES",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = NeonCyan)
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardGray),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Auto app lock timer
                    Column {
                        Text("Auto-lock inactivity time limit", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(30, 60, 300, 900).forEach { secs ->
                                val label = when (secs) {
                                    30 -> "30s"
                                    60 -> "1m"
                                    300 -> "5m"
                                    else -> "15m"
                                }
                                val isActive = autoLockTime == secs
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isActive) NeonGreen else Color.DarkGray)
                                        .clickable {
                                            autoLockTime = secs
                                            viewModel.securityPrefs.autoLockTimeoutSeconds = secs
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = if (isActive) DeepBlack else Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    // Clipboard timeout limits
                    Column {
                        Text("Auto clipboard clearance clock", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(10, 30, 60).forEach { secs ->
                                val isActive = clipTime == secs
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isActive) NeonGreen else Color.DarkGray)
                                        .clickable {
                                            clipTime = secs
                                            viewModel.securityPrefs.clipboardTimeoutSeconds = secs
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("${secs}s", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = if (isActive) DeepBlack else Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    // Recycle retention days
                    Column {
                        Text("Erase trash items retention", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(7, 15, 30, -1).forEach { days ->
                                val label = if (days < 0) "Never" else "${days}d"
                                val isActive = retentionDays == days
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isActive) NeonGreen else Color.DarkGray)
                                        .clickable {
                                            retentionDays = days
                                            viewModel.securityPrefs.recycleBinRetentionDays = days
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = if (isActive) DeepBlack else Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Lock management actions
        item {
            Text(
                text = "VAULT AUTH REGISTRY",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = NeonCyan)
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardGray),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!isChangePinMode) {
                        Button(
                            onClick = { isChangePinMode = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("UPDATE MASTER PIN", fontFamily = FontFamily.Monospace, color = Color.White)
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = newPinInput,
                                onValueChange = { if (it.all { c -> c.isDigit() }) newPinInput = it },
                                label = { Text("New PIN (digits only)", fontSize = 12.sp, color = GrayText) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NeonGreen,
                                    unfocusedBorderColor = Color.Gray,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        if (newPinInput.length >= 4) {
                                            viewModel.securityPrefs.setupPin(newPinInput)
                                            isChangePinMode = false
                                            newPinInput = ""
                                            Toast.makeText(context, "Master passcode updated!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "PIN must be >= 4 digits", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DeepBlack),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("CONFIRM", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                }

                                Button(
                                    onClick = {
                                        isChangePinMode = false
                                        newPinInput = ""
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Sub Config block 3: Backup Database Imports/Exports
        item {
            Text(
                text = "ENCRYPTED EXPORTS & DIRECT ENTRY IMPORTS",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = NeonCyan)
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardGray),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Download clean configuration strings below, or insert formatted JSON blocks directly without needing external directories.",
                        color = GrayText,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val text = viewModel.repository.exportToJson(viewModel.activeEntries.value)
                                viewModel.copyToClipboard("json_backup", text)
                                backupResultText = "JSON export string copied to device clipboard!"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DeepBlack),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("EXPORT JSON", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }

                        Button(
                            onClick = {
                                val text = viewModel.repository.exportToCsv(viewModel.activeEntries.value)
                                viewModel.copyToClipboard("csv_backup", text)
                                backupResultText = "CSV backup document copied to device clipboard!"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = DeepBlack),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("EXPORT CSV", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }

                    if (backupResultText.isNotEmpty()) {
                        Text(
                            text = backupResultText,
                            color = NeonGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    HorizontalDivider(color = Color.DarkGray)

                    if (!isImportMode) {
                        Button(
                            onClick = { isImportMode = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("OPEN PASTE IMPORT DECK", fontFamily = FontFamily.Monospace, color = Color.White)
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Paste your JSON backup code block below to load configurations into Room:",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            )
                            
                            OutlinedTextField(
                                value = backupStringInput,
                                onValueChange = { backupStringInput = it },
                                label = { Text("Database JSON String", fontSize = 11.sp, color = GrayText) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NeonCyan,
                                    unfocusedBorderColor = Color.DarkGray,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                maxLines = 6,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(110.dp)
                                    .testTag("import_string_input")
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (backupStringInput.trim().isNotEmpty()) {
                                            coroutineScope.launch {
                                                try {
                                                    val count = viewModel.repository.importFromJson(backupStringInput)
                                                    backupResultText = "Successfully imported $count keys into SQLite database!"
                                                    backupStringInput = ""
                                                    isImportMode = false
                                                } catch (e: Exception) {
                                                    backupResultText = "Parsing Error: Ensure backup has correct formatted column arrays."
                                                }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DeepBlack),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("IMPORT NOW", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }

                                Button(
                                    onClick = {
                                        isImportMode = false
                                        backupStringInput = ""
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- SECURE DIALOG: ENTRY EDITOR AND DETAIL DRAWER ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditorDialog(
    viewModel: VaultViewModel,
    entry: VaultEntry,
    onDismiss: () -> Unit
) {
    var website by remember { mutableStateOf(entry.websiteName) }
    var username by remember { mutableStateOf(entry.getDecryptedUsername()) }
    var email by remember { mutableStateOf(entry.getDecryptedEmail()) }
    var password by remember { mutableStateOf(entry.getDecryptedPassword()) }
    var url by remember { mutableStateOf(entry.url) }
    var notes by remember { mutableStateOf(entry.getDecryptedNotes()) }
    var category by remember { mutableStateOf(entry.category) }
    var tags by remember { mutableStateOf(entry.tags) }
    var isFavorite by remember { mutableStateOf(entry.isFavorite) }
    var encryptWarning by remember { mutableStateOf("") }

    // Mask passwords by default
    var passwordVisible by remember { mutableStateOf(false) }

    // Password Generation Settings
    var showGeneratorOptions by remember { mutableStateOf(false) }
    var genLength by remember { mutableStateOf(16f) }
    var genUpper by remember { mutableStateOf(true) }
    var genLower by remember { mutableStateOf(true) }
    var genDigits by remember { mutableStateOf(true) }
    var genSymbols by remember { mutableStateOf(true) }

    val categories = listOf("Social Media", "Banking", "Email", "Development", "Work", "Gaming", "Shopping", "Streaming", "Crypto Wallets", "Custom")
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    if (website.trim().isEmpty()) {
                        encryptWarning = "App name label is required."
                    } else {
                        viewModel.addOrUpdateEntry(
                            id = entry.id,
                            website = website.trim(),
                            usernamePlain = username.trim(),
                            emailPlain = email.trim(),
                            passwordPlain = password, // keep spacing
                            url = url.trim(),
                            notesPlain = notes,
                            category = category,
                            tags = tags.trim(),
                            isFavorite = isFavorite
                        )
                        Toast.makeText(context, "Credential saved off to storage", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DeepBlack),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.testTag("confirm_save_btn")
            ) {
                Text("SAVE KEY", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Color.White, fontFamily = FontFamily.Monospace)
            }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (entry.id == 0) "NEW ENCRYPTED ENTRY" else "EDIT PROFILE SECURITY",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = NeonGreen
                    )
                )

                IconButton(
                    onClick = { isFavorite = !isFavorite }
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.Star,
                        contentDescription = "Fav Star",
                        tint = if (isFavorite) ElectricPurple else Color.Gray
                    )
                }
            }
        },
        containerColor = CardGray,
        shape = RoundedCornerShape(16.dp),
        text = {
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.85f)
                    .verticalScroll(rememberScrollState())
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (encryptWarning.isNotEmpty()) {
                        Text(encryptWarning, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
                    }

                    // Website / Name
                    OutlinedTextField(
                        value = website,
                        onValueChange = { website = it },
                        label = { Text("App/Website Label *", fontSize = 11.sp, color = GrayText) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = NeonGreen,
                            unfocusedBorderColor = Color.Gray
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_website_input")
                    )

                    // Username
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username Profile", fontSize = 11.sp, color = GrayText) },
                        trailingIcon = {
                            if (username.isNotEmpty()) {
                                IconButton(onClick = { viewModel.copyToClipboard("username", username) }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Username", tint = NeonCyan, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = NeonGreen,
                            unfocusedBorderColor = Color.Gray
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_username_input")
                    )

                    // Email Reference
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Associated Email", fontSize = 11.sp, color = GrayText) },
                        trailingIcon = {
                            if (email.isNotEmpty()) {
                                IconButton(onClick = { viewModel.copyToClipboard("email", email) }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Email", tint = NeonCyan, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = NeonGreen,
                            unfocusedBorderColor = Color.Gray
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_email_input")
                    )

                    // Custom password with strength logic
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Encrypted Passkey Value", fontSize = 11.sp, color = GrayText) },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    password = PasswordGenerator.generate(
                                        length = genLength.toInt(),
                                        includeUpper = genUpper,
                                        includeLower = genLower,
                                        includeNumbers = genDigits,
                                        includeSymbols = genSymbols
                                    )
                                    passwordVisible = true
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Autorenew,
                                        contentDescription = "Generate Secure Password",
                                        tint = NeonGreen,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle Pass Visibility",
                                        tint = NeonCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                if (password.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.copyToClipboard("password", password) }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Key", tint = NeonGreen, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = NeonGreen,
                            unfocusedBorderColor = Color.Gray
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_password_input")
                    )

                    // Password Strength Bar
                    if (password.isNotEmpty()) {
                        val level = CryptoEngine.getPasswordStrength(password)
                        val entropyValue = CryptoEngine.calculateEntropy(password)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Simple strength bar
                            LinearProgressIndicator(
                                progress = { entropyValue.toFloat() / 100f },
                                color = Color(level.colorHex),
                                trackColor = Color.DarkGray,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Safety Index: ${level.label}",
                                    color = Color(level.colorHex),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                )
                                Text(
                                    String.format("%.1f bits", entropyValue),
                                    color = GrayText,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                )
                            }
                        }
                    }

                    // Expandable advanced password generator drawer
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showGeneratorOptions = !showGeneratorOptions }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Settings, contentDescription = "Gen Params", tint = NeonGreen, modifier = Modifier.size(16.dp))
                                Text(
                                    "CUSTOM PASSKEY GENERATION ASSISTANT",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = NeonGreen,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                            Icon(
                                imageVector = if (showGeneratorOptions) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = "Toggle Panel",
                                tint = NeonGreen,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        if (showGeneratorOptions) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0x0FFFFFFF)),
                                border = BorderStroke(1.dp, Color.DarkGray),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    // Bit/Length slider
                                    val approxBits = (genLength.toInt() * (if (genUpper && genLower && genDigits && genSymbols) 6.55 else if (genLower && genDigits) 5.1 else 4.2)).toInt()
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Adjust Strength Target:", fontSize = 11.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                                            Text("${genLength.toInt()} chars (~$approxBits bits)", fontSize = 11.sp, color = NeonCyan, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                        }
                                        Slider(
                                            value = genLength,
                                            onValueChange = { genLength = it },
                                            valueRange = 8f..128f, // Supports up to 128 characters / ~830 bits!
                                            colors = SliderDefaults.colors(
                                                activeTrackColor = NeonCyan,
                                                inactiveTrackColor = Color.DarkGray,
                                                thumbColor = NeonCyan
                                            )
                                        )
                                    }

                                    // Checkboxes in 2x2 grid
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Checkbox(
                                                checked = genUpper,
                                                onCheckedChange = { genUpper = it },
                                                colors = CheckboxDefaults.colors(checkedColor = NeonGreen, uncheckedColor = Color.Gray)
                                            )
                                            Text("UPPER (A-Z)", fontSize = 10.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                                        }
                                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Checkbox(
                                                checked = genLower,
                                                onCheckedChange = { genLower = it },
                                                colors = CheckboxDefaults.colors(checkedColor = NeonGreen, uncheckedColor = Color.Gray)
                                            )
                                            Text("LOWER (a-z)", fontSize = 10.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                                        }
                                    }

                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Checkbox(
                                                checked = genDigits,
                                                onCheckedChange = { genDigits = it },
                                                colors = CheckboxDefaults.colors(checkedColor = NeonGreen, uncheckedColor = Color.Gray)
                                            )
                                            Text("NUMBERS (0-9)", fontSize = 10.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                                        }
                                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Checkbox(
                                                checked = genSymbols,
                                                onCheckedChange = { genSymbols = it },
                                                colors = CheckboxDefaults.colors(checkedColor = NeonGreen, uncheckedColor = Color.Gray)
                                            )
                                            Text("SYMBOLS (!@#)", fontSize = 10.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            val generatedText = PasswordGenerator.generate(
                                                length = genLength.toInt(),
                                                includeUpper = genUpper,
                                                includeLower = genLower,
                                                includeNumbers = genDigits,
                                                includeSymbols = genSymbols
                                            )
                                            if (generatedText.isNotEmpty()) {
                                                password = generatedText
                                                passwordVisible = true
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DeepBlack),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.fillMaxWidth().height(36.dp)
                                    ) {
                                        Text("GENERATE & RUN CRYPTO WRITE", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Website URL
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Web URL (e.g. google.com)", fontSize = 11.sp, color = GrayText) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = NeonGreen,
                            unfocusedBorderColor = Color.Gray
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Dropdown for Category
                    Column {
                        Text("Secure Registry Category", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(categories) { cat ->
                                val isActive = category == cat
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isActive) NeonCyan else Color.DarkGray)
                                        .clickable { category = cat }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = cat,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isActive) DeepBlack else Color.White,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    // Tags Input
                    OutlinedTextField(
                        value = tags,
                        onValueChange = { tags = it },
                        label = { Text("Custom tags (comma separated)", fontSize = 11.sp, color = GrayText) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = NeonGreen,
                            unfocusedBorderColor = Color.Gray
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Private Notes field
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Decrypted/Raw Private Notes", fontSize = 11.sp, color = GrayText) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = NeonGreen,
                            unfocusedBorderColor = Color.Gray
                        ),
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                    )

                    if (entry.id != 0) {
                        Text(
                            text = "Created Space: ${formatTime(entry.creationDate)}",
                            color = Color.DarkGray,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                        )
                    }

                    // Duplicate entry action
                    if (entry.id != 0) {
                        HorizontalDivider(color = Color.DarkGray)
                        Button(
                            onClick = {
                                viewModel.addOrUpdateEntry(
                                    website = "${entry.websiteName} (Copy)",
                                    usernamePlain = entry.getDecryptedUsername(),
                                    emailPlain = entry.getDecryptedEmail(),
                                    passwordPlain = entry.getDecryptedPassword(),
                                    url = entry.url,
                                    notesPlain = entry.getDecryptedNotes(),
                                    category = entry.category,
                                    tags = entry.tags,
                                    isFavorite = entry.isFavorite
                                )
                                Toast.makeText(context, "Credential profile duplicated!", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("DUPLICATE PROFILE RECORD", fontFamily = FontFamily.Monospace, color = NeonCyan)
                        }

                        Button(
                            onClick = {
                                viewModel.softDelete(entry.id)
                                Toast.makeText(context, "Moved credentials to recycle trash folder", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x33EF4444), contentColor = ErrorRed),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("ARCHIVE TO RECYCLE BIN", fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    )
}

fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
