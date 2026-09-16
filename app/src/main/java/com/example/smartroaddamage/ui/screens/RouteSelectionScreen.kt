package com.example.smartroaddamage.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smartroaddamage.data.RouteAnalysis
import com.example.smartroaddamage.ui.components.NeoButton
import com.example.smartroaddamage.ui.theme.*
import com.example.smartroaddamage.ui.viewmodels.RouteSelectionState
import com.example.smartroaddamage.ui.viewmodels.RouteSelectionViewModel
import com.mappls.sdk.services.api.autosuggest.model.ELocation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteSelectionScreen(
    currentLat: Double,
    currentLng: Double,
    onBack: () -> Unit,
    onStartJourney: (RouteAnalysis) -> Unit,
    modifier: Modifier = Modifier,
    vm: RouteSelectionViewModel = viewModel()
) {
    val state             by vm.state.collectAsState()
    val suggestions       by vm.suggestions.collectAsState()
    val originSuggestions by vm.originSuggestions.collectAsState()

    var destinationText  by remember { mutableStateOf("") }
    var originText       by remember { mutableStateOf("") }
    var selectedAnalysis by remember { mutableStateOf<RouteAnalysis?>(null) }

    // Resolved origin — starts as GPS, overrideable
    var resolvedOriginLat by remember { mutableStateOf<Double?>(if (currentLat != 0.0) currentLat else null) }
    var resolvedOriginLng by remember { mutableStateOf<Double?>(if (currentLng != 0.0) currentLng else null) }
    var resolvedOriginPin by remember { mutableStateOf<String?>(null) }

    // Resolved destination — set when user picks from autocomplete dropdown
    var resolvedDestLat by remember { mutableStateOf<Double?>(null) }
    var resolvedDestLng by remember { mutableStateOf<Double?>(null) }
    var resolvedDestPin by remember { mutableStateOf<String?>(null) }

    // Which suggestion dropdown is open
    var showDestSuggestions   by remember { mutableStateOf(false) }
    var showOriginSuggestions by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.reset() }

    Column(
        modifier = modifier.fillMaxSize().background(Color(0xFF0F172A))
    ) {
        // ── HEADER ──────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A))
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { vm.reset(); onBack() },
                    modifier = Modifier
                        .size(44.dp)
                        .neoBrutalSurface(backgroundColor = Color(0xFF172033), borderColor = Color(0xFF334155))
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("ROUTE PROTOCOL", fontFamily = Outfit, fontWeight = FontWeight.Black, fontSize = 24.sp, color = Color.White)
                    Text("POTHOLE-AWARE NAVIGATION", fontFamily = JetBrainsMono, fontSize = 10.sp, color = Color(0xFF38BDF8), letterSpacing = 1.5.sp)
                }
            }
        }

        Divider(color = Color(0xFF1E293B), thickness = 1.dp)

        // ── SCROLLABLE BODY ──────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── ORIGIN field ──────────────────────────────────────────────
            Text("FROM", fontFamily = JetBrainsMono, fontSize = 10.sp, color = Color(0xFF475569), letterSpacing = 1.sp)

            Box {
                OutlinedTextField(
                    value         = originText,
                    onValueChange = { text ->
                        originText = text
                        showOriginSuggestions = text.length >= 2
                        showDestSuggestions = false
                        vm.onOriginTyped(text)
                    },
                    placeholder = {
                        Text(
                            if (currentLat != 0.0) "GPS: %.5f, %.5f".format(currentLat, currentLng)
                            else "Type origin or using GPS…",
                            fontFamily = JetBrainsMono, fontSize = 11.sp
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color(0xFF4ADE80), modifier = Modifier.size(18.dp))
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color(0xFF172033),
                        focusedContainerColor   = Color(0xFF1E293B),
                        unfocusedBorderColor    = Color(0xFF4ADE80).copy(alpha = 0.4f),
                        focusedBorderColor      = Color(0xFF4ADE80),
                        cursorColor             = Color(0xFF4ADE80),
                        focusedTextColor        = Color.White,
                        unfocusedTextColor      = Color(0xFF94A3B8),
                        focusedLabelColor       = Color(0xFF4ADE80),
                        unfocusedLabelColor     = Color(0xFF475569)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Color.White),
                    modifier = Modifier.fillMaxWidth()
                )

                // Origin suggestion dropdown
                if (showOriginSuggestions && originSuggestions.isNotEmpty()) {
                    SuggestionDropdown(
                        suggestions = originSuggestions,
                        onSelect    = { eloc ->
                            originText            = eloc.placeName ?: eloc.placeAddress ?: originText
                            resolvedOriginLat     = eloc.latitude
                            resolvedOriginLng     = eloc.longitude
                            resolvedOriginPin     = eloc.mapplsPin
                            showOriginSuggestions = false
                            vm.clearOriginSuggestions()
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 58.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── DESTINATION field ─────────────────────────────────────────
            Text("TO", fontFamily = JetBrainsMono, fontSize = 10.sp, color = Color(0xFF475569), letterSpacing = 1.sp)

            Box {
                OutlinedTextField(
                    value         = destinationText,
                    onValueChange = { text ->
                        destinationText     = text
                        showDestSuggestions = text.length >= 2
                        showOriginSuggestions = false
                        vm.onDestinationTyped(text, resolvedOriginLat ?: currentLat, resolvedOriginLng ?: currentLng)
                    },
                    placeholder   = { Text("Boduppal, Hyderabad…", fontFamily = JetBrainsMono, fontSize = 11.sp) },
                    leadingIcon   = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp)) },
                    singleLine    = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color(0xFF172033),
                        focusedContainerColor   = Color(0xFF1E293B),
                        unfocusedBorderColor    = Color(0xFF334155),
                        focusedBorderColor      = Color(0xFF38BDF8),
                        cursorColor             = Color(0xFF38BDF8),
                        focusedTextColor        = Color.White,
                        unfocusedTextColor      = Color.White,
                        focusedLabelColor       = Color(0xFF38BDF8),
                        unfocusedLabelColor     = Color(0xFF475569)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Color.White),
                    modifier  = Modifier.fillMaxWidth()
                )

                // Destination suggestion dropdown
                if (showDestSuggestions && suggestions.isNotEmpty()) {
                    SuggestionDropdown(
                        suggestions = suggestions,
                        // When user picks a destination suggestion — cache the lat/lng
                        // These are passed directly to the Directions API (no second lookup)
                        onSelect    = { eloc ->
                            destinationText     = eloc.placeName ?: eloc.placeAddress ?: destinationText
                            resolvedDestLat     = eloc.latitude
                            resolvedDestLng     = eloc.longitude
                            resolvedDestPin     = eloc.mapplsPin
                            showDestSuggestions = false
                            vm.clearSuggestions()
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 58.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── SCAN button ───────────────────────────────────────────────
            val hasOriginOptions  = currentLat != 0.0 || originText.isNotBlank()
            val canScan = destinationText.isNotBlank() && hasOriginOptions && state !is RouteSelectionState.Loading

            NeoButton(
                text     = if (state is RouteSelectionState.Loading) "SCANNING ROUTES…" else "SCAN POTHOLE-FREE ROUTES",
                status   = if (canScan) NeoStatus.ACTIVE else NeoStatus.IDLE,
                enabled  = canScan,
                onClick  = {
                    showDestSuggestions   = false
                    showOriginSuggestions = false
                    selectedAnalysis      = null
                    vm.searchRoutes(
                        originText  = originText,
                        originLat   = resolvedOriginLat,
                        originLng   = resolvedOriginLng,
                        originPin   = resolvedOriginPin,
                        destination = destinationText,
                        destLat     = resolvedDestLat,
                        destLng     = resolvedDestLng,
                        destPin     = resolvedDestPin,
                        currentLat  = currentLat,
                        currentLng  = currentLng
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )

            // ── STATE CONTENT ─────────────────────────────────────────────
            when (val s = state) {

                is RouteSelectionState.Idle -> {
                    Column(
                        modifier            = Modifier.fillMaxWidth().padding(top = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🛣", fontSize = 36.sp)
                        Text(
                            "Enter your destination.\nWe'll cross-reference live pothole data\nfrom Firestore for each route.",
                            fontFamily = JetBrainsMono, fontSize = 11.sp,
                            color      = Color(0xFF475569), textAlign = TextAlign.Center
                        )
                    }
                }

                is RouteSelectionState.Loading -> {
                    Column(
                        modifier            = Modifier.fillMaxWidth().padding(top = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = Color(0xFF38BDF8), strokeWidth = 3.dp, modifier = Modifier.size(48.dp))
                        Text(
                            "Querying Mappls routes…\nFetching Firestore potholes…\nRunning 50m Haversine analysis…",
                            fontFamily = JetBrainsMono, fontSize = 11.sp,
                            color      = Color(0xFF475569), textAlign = TextAlign.Center
                        )
                    }
                }

                is RouteSelectionState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF7F1D1D), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(8.dp))
                            .padding(16.dp)
                    ) {
                        Text("⚠  ${s.message}", fontFamily = JetBrainsMono, fontSize = 12.sp, color = Color(0xFFFECACA))
                    }
                }

                is RouteSelectionState.Ready -> {
                    Text(
                        "${s.analyses.size} ROUTES ANALYSED  ·  RANKED BY RISK",
                        fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold,
                        fontSize   = 11.sp, color = Color(0xFF38BDF8), letterSpacing = 1.sp
                    )

                    s.analyses.forEachIndexed { idx, analysis ->
                        RouteResultCard(
                            label      = listOf("ALPHA", "BETA", "GAMMA").getOrElse(idx) { "ROUTE ${idx+1}" },
                            analysis   = analysis,
                            isSelected = selectedAnalysis?.route?.index == analysis.route.index,
                            isSafest   = idx == 0,
                            onClick    = { selectedAnalysis = analysis }
                        )
                    }

                    if (selectedAnalysis != null) {
                        NeoButton(
                            text     = "COMMENCE JOURNEY",
                            status   = NeoStatus.CRITICAL,
                            onClick  = { selectedAnalysis?.let { onStartJourney(it) } },
                            modifier = Modifier.fillMaxWidth().height(58.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Autosuggest dropdown — shown below a text field
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun SuggestionDropdown(
    suggestions: List<ELocation>,
    onSelect: (ELocation) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
    ) {
        suggestions.take(5).forEach { eloc ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(eloc) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(14.dp))
                Column {
                    Text(
                        text       = eloc.placeName ?: "Unknown Place",
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 13.sp,
                        color      = Color.White,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    if (!eloc.placeAddress.isNullOrBlank()) {
                        Text(
                            text       = eloc.placeAddress!!,
                            fontFamily = JetBrainsMono,
                            fontSize   = 10.sp,
                            color      = Color(0xFF64748B),
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (suggestions.indexOf(eloc) < minOf(suggestions.size - 1, 4)) {
                Divider(color = Color(0xFF0F172A), thickness = 1.dp)
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Route result card
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun RouteResultCard(
    label: String, analysis: RouteAnalysis,
    isSelected: Boolean, isSafest: Boolean, onClick: () -> Unit
) {
    val riskColor   = Color(analysis.riskColor or 0xFF000000)
    val borderColor = when { isSelected -> Color(0xFF38BDF8); isSafest -> Color(0xFF4ADE80); else -> Color(0xFF334155) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .neoBrutalSurface(backgroundColor = if (isSelected) Color(0xFF1E293B) else Color(0xFF172033), borderColor = borderColor, shadowOffset = if (isSelected) 5.dp else 3.dp)
            .clickable { onClick() }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontFamily = JetBrainsMono, fontWeight = FontWeight.Black, fontSize = 15.sp, color = Color.White)
                if (isSafest) {
                    Box(modifier = Modifier.background(Color(0xFF14532D), RoundedCornerShape(4.dp)).border(1.dp, Color(0xFF4ADE80), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("SAFEST", fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = Color(0xFF4ADE80))
                    }
                }
            }
            if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatCol("ETA",      "${analysis.route.durationMin} min")
            StatCol("DISTANCE", "${analysis.route.distanceKm} km")
            StatCol("REPORTS",  "${analysis.totalReportCount}")
            StatCol("POTHOLES", "${analysis.potholeCount}", if (analysis.potholeCount > 15) Color(0xFFEF4444) else Color.White)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).background(riskColor.copy(0.12f), RoundedCornerShape(6.dp)).border(1.dp, riskColor, RoundedCornerShape(6.dp)).padding(8.dp), Alignment.Center) {
                Text(analysis.riskLabel, fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = riskColor)
            }
            Box(Modifier.weight(1f).background(Color(0xFF0F172A), RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF334155), RoundedCornerShape(6.dp)).padding(8.dp), Alignment.Center) {
                Text("HAZARD: ${analysis.severityScore}", fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF94A3B8))
            }
        }

        if (analysis.potholes.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("⚠", fontSize = 12.sp)
                Text(
                    "PRIMARY: ${analysis.primaryHazardLabel}  |  CONFIRMED: ${analysis.confirmedHazardCount}",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = Color(0xFF94A3B8)
                )
            }
        }
    }
}

@Composable
private fun StatCol(label: String, value: String, valueColor: Color = Color.White) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 9.sp,  color = Color(0xFF475569), letterSpacing = 1.sp)
        Text(value, fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = valueColor)
    }
}
