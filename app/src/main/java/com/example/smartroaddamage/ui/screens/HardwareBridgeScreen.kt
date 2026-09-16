package com.example.smartroaddamage.ui.screens

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smartroaddamage.permissions.PermissionManager
import com.example.smartroaddamage.ui.components.*
import com.example.smartroaddamage.ui.theme.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

private val CarbonStealthBrush = Brush.radialGradient(
    0.0f to Color(0xFF1E1E1E),
    0.5f to Color(0xFF141414),
    1.0f to Color(0xFF080808),
    center = Offset.Infinite
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareBridgeScreen(
    onNavigateToDashboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context       = LocalContext.current
    val view          = LocalView.current
    val haptics       = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val viewModel: HardwareBridgeViewModel = viewModel(
        factory = HardwareBridgeViewModel.Factory(context)
    )

    val isDarkMode      by remember { mutableStateOf(false) }
    val connectionState by viewModel.connectionState.collectAsState()
    val foundDevices    by viewModel.foundDevices.collectAsState()
    val selectedDevice  by viewModel.selectedDevice.collectAsState()
    val hardwareError   by viewModel.hardwareError.collectAsState()

    val isScanning = connectionState == ConnectionState.SEARCHING ||
                     connectionState == ConnectionState.CONNECTING

    // ── Firebase Pilot identity ───────────────────────────────────────────────
    val currentUser   = remember { FirebaseAuth.getInstance().currentUser }
    val pilotName     = currentUser?.displayName ?: "Elite Pilot"
    val callsign      = currentUser?.let { "UNIT-${it.uid.take(5).uppercase()}" } ?: "OFFLINE"
    val avatarInitial = currentUser?.displayName?.firstOrNull()?.uppercase() ?: "EP"

    // ── BLE Permission + Bluetooth Enable chain ───────────────────────────────
    var permissionsGranted by remember {
        mutableStateOf(PermissionManager.hasRequiredPermissions(context))
    }
    var showPermissionExplanation by remember { mutableStateOf(false) }

    // API 26-30: Location required. API 31+: BLUETOOTH_SCAN + BLUETOOTH_CONNECT
    val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    val bluetoothManager = remember {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    val locationManager = remember {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    // Step 3 in chain: request Location power-on if hardware is off (crucial for API <= 30)
    val enableLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (isLocationEnabled) {
            showPermissionExplanation = false
            viewModel.onConnectClicked()
        } else {
            Toast.makeText(context, "Location must be on to find devices", Toast.LENGTH_SHORT).show()
        }
    }

    // Step 2 in chain: request BT power-on if hardware is off
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            if (!isLocationEnabled && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                enableLocationLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } else {
                showPermissionExplanation = false
                viewModel.onConnectClicked()
            }
        } else {
            Toast.makeText(context, "Bluetooth is required.", Toast.LENGTH_SHORT).show()
        }
    }

    // Step 1 in chain: request permissions → then check BT power → then Location power
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) {
            showPermissionExplanation = false
            if (bluetoothManager.adapter?.isEnabled == false) {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                val isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                                        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                if (!isLocationEnabled && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                    enableLocationLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } else {
                    viewModel.onConnectClicked()
                }
            }
        } else {
            showPermissionExplanation = true
            Toast.makeText(context, "Permissions denied by user.", Toast.LENGTH_SHORT).show()
        }
    }

    // Helper: trigger the full permission→BT→Location chain
    fun triggerScan() {
        when {
            !permissionsGranted -> permissionLauncher.launch(blePermissions)
            bluetoothManager.adapter?.isEnabled == false ->
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) ->
                enableLocationLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            else -> viewModel.onConnectClicked()
        }
    }

    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.CONNECTED) {
            kotlinx.coroutines.delay(500)
            onNavigateToDashboard()
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState   = drawerState,
        drawerContent = {
            CockpitSettingsDrawer(
                pilotName     = pilotName,
                callsign      = callsign,
                avatarInitial = avatarInitial
            )
        },
        scrimColor = Color.Black.copy(alpha = 0.5f)
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .drawBehind { drawRect(brush = CarbonStealthBrush) }
        ) {
            // ── Hamburger — TOP LEFT ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .zIndex(1f),
                contentAlignment = Alignment.TopStart
            ) {
                val hamburgerSource = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .neoBrutalist(hamburgerSource)
                        .clickable(interactionSource = hamburgerSource, indication = null) {
                            coroutineScope.launch {
                                if (drawerState.currentValue == DrawerValue.Closed)
                                    drawerState.open() else drawerState.close()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Menu, contentDescription = "Settings", tint = Color.White)
                }
            }

            // ── TOP ZONE: Title + Permission card + Radar + Device grid ───────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 80.dp, bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "HARDWARE BRIDGE",
                    fontFamily = Outfit, fontWeight = FontWeight.Bold,
                    fontSize = 28.sp, color = Color.White
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Connect to your Smart Road Monitor device",
                    fontFamily = JetBrainsMono, fontSize = 13.sp, color = Color.Gray
                )

                // ── BLE Permission Explanation Card ───────────────────────────
                AnimatedVisibility(
                    visible = showPermissionExplanation && !permissionsGranted,
                    enter   = expandVertically() + fadeIn(),
                    exit    = shrinkVertically() + fadeOut()
                ) {
                    Column {
                        Spacer(Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .drawBehind {
                                    drawRoundRect(
                                        color        = Color.Black,
                                        topLeft      = Offset(6.dp.toPx(), 6.dp.toPx()),
                                        size         = size,
                                        cornerRadius = CornerRadius(12.dp.toPx())
                                    )
                                }
                                .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                                .border(2.dp, Color(0xFFADFF2F), RoundedCornerShape(12.dp))
                                .padding(16.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                        "⚡  BLUETOOTH ACCESS REQUIRED"
                                    else
                                        "⚡  LOCATION ACCESS REQUIRED (Android ≤11)",
                                    fontFamily = Outfit, fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp, color = Color(0xFFADFF2F)
                                )
                                Text(
                                    text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                        "Android 12+ requires Bluetooth Scan & Connect permissions to discover nearby devices."
                                    else
                                        "Android 11 and below requires Location permission for Bluetooth scanning. Your GPS data is never stored.",
                                    fontFamily = JetBrainsMono, fontSize = 11.sp,
                                    color = Color(0xFF94A3B8), lineHeight = 17.sp
                                )
                                NeoButton(
                                    text     = "GRANT ACCESS",
                                    status   = NeoStatus.IDLE,
                                    onClick  = { permissionLauncher.launch(blePermissions) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // ── Radar ─────────────────────────────────────────────────────
                Box(modifier = Modifier.size(300.dp), contentAlignment = Alignment.Center) {
                    SonarRadar(
                        isActive    = isScanning,
                        foundDevice = foundDevices.isNotEmpty(),
                        modifier    = Modifier.size(280.dp)
                    )
                }

                Spacer(Modifier.height(32.dp))

                // ── CENTERED CONTROL DECK ────────────────────────────────────
                ConnectionStatusText(
                    rssi       = selectedDevice?.rssi,
                    deviceName = selectedDevice?.name,
                    isDarkMode = isDarkMode
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    TriStateConnectButton(
                        state      = connectionState,
                        onClick    = {
                            if (isScanning) {
                                viewModel.onConnectClicked()
                            } else {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                triggerScan()
                            }
                        },
                        isDarkMode = isDarkMode
                    )
                }

                Spacer(Modifier.height(32.dp))

                // ── 2-col device grid with pop animation ──────────────────────
                AnimatedVisibility(
                    visible = foundDevices.isNotEmpty(),
                    enter   = slideInVertically(
                        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow),
                        { it / 2 }
                    ) + fadeIn(tween(300)),
                    exit    = fadeOut(tween(200))
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "FOUND DEVICES",
                            fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold,
                            fontSize = 11.sp, color = Color.Gray, letterSpacing = 0.1.em
                        )
                        Spacer(Modifier.height(10.dp))
                        LazyVerticalGrid(
                            columns               = GridCells.Fixed(2),
                            contentPadding        = PaddingValues(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement   = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                        ) {
                            itemsIndexed(foundDevices, key = { _, d -> d.address }) { index, device ->
                                PopDeviceCard(
                                    device     = BleDevice(device.name ?: "Unknown", device.address, device.rssi),
                                    isSelected = selectedDevice?.address == device.address,
                                    index      = index,
                                    isDarkMode = isDarkMode,
                                    onClick    = { viewModel.onDeviceSelected(device) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Hardware Error Dialog ─────────────────────────────────────────────
        if (hardwareError != null) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { viewModel.dismissHardwareError() }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFFF43F5E).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = ":(",
                            fontSize = 54.sp,
                            fontFamily = JetBrainsMono,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF43F5E).copy(alpha = 0.9f)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = hardwareError!!,
                            fontFamily = JetBrainsMono,
                            fontSize = 14.sp,
                            color = Color.LightGray,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = { viewModel.dismissHardwareError() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF43F5E).copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("ACKNOWLEDGE", fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, color = Color(0xFFF43F5E), letterSpacing = 0.1.em)
                        }
                    }
                }
            }
        }

    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PopDeviceCard — 2-col bento with spring pop per card index
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun PopDeviceCard(
    device: BleDevice,
    isSelected: Boolean,
    index: Int,
    isDarkMode: Boolean,
    onClick: () -> Unit
) {
    val view  = LocalView.current
    val shape = RoundedCornerShape(12.dp)

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index * 60L)
        visible = true
    }
    LaunchedEffect(isSelected) {
        if (isSelected) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressOffset by animateDpAsState(
        if (isPressed) 3.dp else 0.dp, tween(80), label = "card_press"
    )
    val borderColor by animateColorAsState(
        if (isSelected) Color(0xFF2DD4BF) else Color.Black, tween(150), label = "card_border"
    )
    val signalStrength = ((device.rssi + 100) / 60f).coerceIn(0f, 1f)

    AnimatedVisibility(
        visible = visible,
        enter   = scaleIn(
            spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
            initialScale = 0.6f
        ) + fadeIn(tween(200))
    ) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth().height(84.dp)
                    .offset(x = 3.dp, y = 3.dp)
                    .background(Color.Black, shape)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth().height(84.dp)
                    .offset(x = (-3).dp + pressOffset, y = (-3).dp + pressOffset)
                    .background(Color(0xFF1A1A1A), shape)
                    .border(if (isSelected) 1.5.dp else 1.dp, borderColor, shape)
                    .clickable(interactionSource = interactionSource, indication = null) { onClick() }
                    .padding(10.dp)
            ) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        device.name.ifBlank { "UNKNOWN" },
                        fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                        color      = if (isSelected) Color(0xFF2DD4BF) else Color(0xFFE2E8F0),
                        maxLines   = 1, overflow = TextOverflow.Ellipsis
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            repeat(5) { bar ->
                                Box(
                                    modifier = Modifier.width(8.dp).height(6.dp)
                                        .background(
                                            if ((bar + 1) / 5f <= signalStrength)
                                                (if (isSelected) Color(0xFF2DD4BF) else Color(0xFF64748B))
                                            else Color(0xFF334155),
                                            RoundedCornerShape(1.dp)
                                        )
                                )
                            }
                        }
                        Text(
                            "${device.rssi} dBm",
                            fontFamily = JetBrainsMono, fontSize = 9.sp, color = Color(0xFF64748B)
                        )
                    }
                }
            }
        }
    }
}
