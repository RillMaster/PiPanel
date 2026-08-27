package com.rillmaster.pipanel

import androidx.compose.animation.animateContentSize
import com.rillmaster.pipanel.ui.components.AnimatedEntrance
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import com.rillmaster.pipanel.data.db.AppDatabase
import com.rillmaster.pipanel.data.db.MetricEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ══════════════════════════════════════════════════════════════════════════════
//  Constantes et Couleurs par défaut
// ══════════════════════════════════════════════════════════════════════════════

private const val DEFAULT_COLOR_BLUE   = "#2196F3"
private const val DEFAULT_COLOR_GREEN  = "#4CAF50"
private const val DEFAULT_COLOR_PURPLE = "#9C27B0"
private const val DEFAULT_COLOR_RED    = "#E57373"

// ══════════════════════════════════════════════════════════════════════════════
//  Helpers Couleurs
// ══════════════════════════════════════════════════════════════════════════════

private fun String.toComposeColor(): Color {
    return try {
        Color(this.toColorInt())
    } catch (_: Exception) {
        Color.Gray
    }
}

private fun Color.toHex(): String {
    return String.format("#%06X", (0xFFFFFF and this.toArgb()))
}

// ══════════════════════════════════════════════════════════════════════════════
//  Écran principal : Graphiques 24 h
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartsScreen(
    settings           : SettingsManager,
    onOpenMenu         : () -> Unit,
    showNavigationIcon : Boolean = true
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.getInstance(context).metricDao() }
    val since = remember { System.currentTimeMillis() - 24L * 3600_000 }
    val metrics by remember(dao) { dao.getSince(since) }
        .collectAsState(initial = null)

    var showColorPicker by remember { mutableStateOf(false) }

    // État local des couleurs pour rafraîchissement immédiat
    var colorCpu   by remember { mutableStateOf(settings.getChartColor("cpu", DEFAULT_COLOR_BLUE).toComposeColor()) }
    var colorTemp  by remember { mutableStateOf(settings.getChartColor("temp", DEFAULT_COLOR_GREEN).toComposeColor()) }
    var colorRam   by remember { mutableStateOf(settings.getChartColor("ram", DEFAULT_COLOR_PURPLE).toComposeColor()) }
    var colorRead  by remember { mutableStateOf(settings.getChartColor("disk_read", DEFAULT_COLOR_BLUE).toComposeColor()) }
    var colorWrite by remember { mutableStateOf(settings.getChartColor("disk_write", DEFAULT_COLOR_RED).toComposeColor()) }
    var colorDown  by remember { mutableStateOf(settings.getChartColor("net_rx", DEFAULT_COLOR_BLUE).toComposeColor()) }
    var colorUp    by remember { mutableStateOf(settings.getChartColor("net_tx", DEFAULT_COLOR_GREEN).toComposeColor()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.charts_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    if (showNavigationIcon) {
                        IconButton(onClick = onOpenMenu) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showColorPicker = true }) {
                        Icon(Icons.Default.Palette, contentDescription = stringResource(R.string.charts_colors_title))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            val data = metrics
            if (data == null) {
                // Chargement initial
                Spacer(Modifier.height(16.dp))
            } else if (data.isEmpty()) {
                EmptyCharts()
            } else {
                val xIndices = data.indices.map { it.toFloat() }
                val timestamps = data.map { it.timestamp }

                val netRxSeries = mutableListOf<Float>()
                val netTxSeries = mutableListOf<Float>()
                val diskReadSeries = mutableListOf<Float>()
                val diskWriteSeries = mutableListOf<Float>()

                for (i in data.indices) {
                    if (i == 0) {
                        netRxSeries.add(0f)
                        netTxSeries.add(0f)
                        diskReadSeries.add(0f)
                        diskWriteSeries.add(0f)
                    } else {
                        val dt = (data[i].timestamp - data[i-1].timestamp) / 1000.0
                        if (dt > 0) {
                            val rxDelta = (data[i].netRxBytes - data[i-1].netRxBytes).coerceAtLeast(0L)
                            val txDelta = (data[i].netTxBytes - data[i-1].netTxBytes).coerceAtLeast(0L)
                            val diskRDelta = (data[i].diskReadBytes - data[i-1].diskReadBytes).coerceAtLeast(0L)
                            val diskWDelta = (data[i].diskWriteBytes - data[i-1].diskWriteBytes).coerceAtLeast(0L)

                            netRxSeries.add((rxDelta / dt / 1024.0).toFloat())
                            netTxSeries.add((txDelta / dt / 1024.0).toFloat())
                            diskReadSeries.add((diskRDelta / dt / (1024.0 * 1024.0)).toFloat())
                            diskWriteSeries.add((diskWDelta / dt / (1024.0 * 1024.0)).toFloat())
                        } else {
                            netRxSeries.add(0f)
                            netTxSeries.add(0f)
                            diskReadSeries.add(0f)
                            diskWriteSeries.add(0f)
                        }
                    }
                }

                if (data.size > 1) {
                    netRxSeries[0] = netRxSeries[1]
                    netTxSeries[0] = netTxSeries[1]
                    diskReadSeries[0] = diskReadSeries[1]
                    diskWriteSeries[0] = diskWriteSeries[1]
                }

                AnimatedEntrance(delayMillis = 0) {
                    ChartCard(
                        title = stringResource(R.string.charts_cpu_temp),
                        x = xIndices,
                        ySeries = listOf(
                            data.map { it.cpuPercent.toFloat() },
                            data.map { it.tempCelsius.toFloat() }
                        ),
                        colors = listOf(colorCpu, colorTemp),
                        legends = listOf(
                            stringResource(R.string.charts_legend_cpu),
                            stringResource(R.string.charts_legend_temp)
                        ),
                        units = listOf("%", "°C"),
                        timestamps = timestamps
                    )
                }
                AnimatedEntrance(delayMillis = 100) {
                    ChartCard(
                        title = stringResource(R.string.charts_ram),
                        x = xIndices,
                        ySeries = listOf(data.map { it.ramUsedMb.toFloat() }),
                        colors = listOf(colorRam),
                        legends = listOf(stringResource(R.string.charts_legend_ram)),
                        units = listOf(" Mo"),
                        timestamps = timestamps
                    )
                }
                AnimatedEntrance(delayMillis = 200) {
                    ChartCard(
                        title = stringResource(R.string.charts_disk),
                        x = xIndices,
                        ySeries = listOf(diskReadSeries, diskWriteSeries),
                        colors = listOf(colorRead, colorWrite),
                        legends = listOf(
                            stringResource(R.string.charts_legend_read),
                            stringResource(R.string.charts_legend_write)
                        ),
                        units = listOf(" Mo/s", " Mo/s"),
                        timestamps = timestamps
                    )
                }
                AnimatedEntrance(delayMillis = 300) {
                    ChartCard(
                        title = stringResource(R.string.charts_network),
                        x = xIndices,
                        ySeries = listOf(netRxSeries, netTxSeries),
                        colors = listOf(colorDown, colorUp),
                        legends = listOf(
                            stringResource(R.string.charts_legend_download),
                            stringResource(R.string.charts_legend_upload)
                        ),
                        units = listOf(" ko/s", " ko/s"),
                        timestamps = timestamps
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showColorPicker) {
        ChartColorPickerDialog(
            settings = settings,
            onDismiss = { showColorPicker = false },
            onColorsUpdated = {
                colorCpu   = settings.getChartColor("cpu", DEFAULT_COLOR_BLUE).toComposeColor()
                colorTemp  = settings.getChartColor("temp", DEFAULT_COLOR_GREEN).toComposeColor()
                colorRam   = settings.getChartColor("ram", DEFAULT_COLOR_PURPLE).toComposeColor()
                colorRead  = settings.getChartColor("disk_read", DEFAULT_COLOR_BLUE).toComposeColor()
                colorWrite = settings.getChartColor("disk_write", DEFAULT_COLOR_RED).toComposeColor()
                colorDown  = settings.getChartColor("net_rx", DEFAULT_COLOR_BLUE).toComposeColor()
                colorUp    = settings.getChartColor("net_tx", DEFAULT_COLOR_GREEN).toComposeColor()
            }
        )
    }
}

@Composable
private fun EmptyCharts() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ShowChart,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Text(
            text = stringResource(R.string.charts_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartCard(
    title: String,
    x: List<Float>,
    ySeries: List<List<Float>>,
    colors: List<Color>,
    legends: List<String>,
    units: List<String>,
    timestamps: List<Long> = emptyList()
) {
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(x, ySeries) {
        modelProducer.runTransaction {
            lineModel {
                ySeries.forEach { y -> series(x, y) }
            }
        }
    }

    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val bottomAxisFormatter = remember(timestamps) {
        CartesianValueFormatter { _, value, _ ->
            val index = value.toInt()
            val ts = timestamps.getOrNull(index)
            if (ts != null) {
                timeFormatter.format(Date(ts))
            } else {
                ""
            }
        }
    }

    val lineProvider = remember(colors) {
        LineCartesianLayer.LineProvider.series(
            colors.map { color ->
                LineCartesianLayer.Line(
                    fill = LineCartesianLayer.LineFill.single(Fill(color)),
                    areaFill = LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = 0.15f)))
                )
            }
        )
    }

    // Configuration d'un Marker interactif personnalisé avec unités et filtrage intelligent
    val markerLabelBackground = rememberShapeComponent(
        fill = Fill(MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)),
        shape = RoundedCornerShape(8.dp)
    )
    val markerLabel = rememberTextComponent(
        style = MaterialTheme.typography.labelSmall.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        ),
        padding = Insets(8.dp, 6.dp),
        background = markerLabelBackground
    )
    val markerIndicator = remember {
        { color: Color ->
            ShapeComponent(
                fill = Fill(color),
                shape = CircleShape
            )
        }
    }
    val markerGuideline = rememberLineComponent(
        fill = Fill(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)),
        thickness = 1.dp
    )

    // Formateur personnalisé qui formate intelligemment avec les unités adéquates et une précision accrue.
    val customMarkerValueFormatter = remember(legends, units) {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            buildAnnotatedString {
                val lineTarget = targets.firstOrNull { it is LineCartesianLayerMarkerTarget } as? LineCartesianLayerMarkerTarget
                if (lineTarget != null && lineTarget.points.isNotEmpty()) {
                    val points = lineTarget.points
                    points.forEachIndexed { idx, point ->
                        val seriesIdx = point.entry.seriesIndex
                        val name = legends.getOrNull(seriesIdx) ?: ""
                        val unit = units.getOrNull(seriesIdx) ?: ""
                        
                        val pattern = if (point.entry.y < 1.0 && point.entry.y > 0) "%.3f" else "%.1f"
                        val valueStr = String.format(Locale.US, pattern, point.entry.y)
                        
                        append("$name: $valueStr$unit")
                        if (idx < points.lastIndex) append("  |  ")
                    }
                } else {
                    targets.forEachIndexed { index, target ->
                        append(String.format(Locale.US, "%.1f", target.x))
                        if (index < targets.lastIndex) append(", ")
                    }
                }
            }
        }
    }

    val marker = rememberDefaultCartesianMarker(
        label = markerLabel,
        valueFormatter = customMarkerValueFormatter,
        indicator = markerIndicator,
        guideline = markerGuideline
    )

    // Formateur d'axe vertical avec précision dynamique pour éviter les doublons sur les petites valeurs
    val verticalAxisFormatter = remember {
        CartesianValueFormatter { _, value, _ ->
            if (value == 0.0) "0"
            else if (value < 1.0) String.format(Locale.US, "%.3f", value)
            else String.format(Locale.US, "%.1f", value)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(Modifier.height(8.dp))

            // Section des légendes avec puces de couleur
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                legends.forEachIndexed { index, label ->
                    val color = colors.getOrNull(index) ?: Color.Gray
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(color, CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(lineProvider = lineProvider),
                    startAxis  = VerticalAxis.rememberStart(valueFormatter = verticalAxisFormatter),
                    bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = bottomAxisFormatter),
                    marker = marker
                ),
                modelProducer = modelProducer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Composant : Cercle chromatique (Color Wheel)
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ColorWheel(
    modifier: Modifier = Modifier,
    selectedColor: Color,
    onColorChanged: (Color) -> Unit
) {
    val hues = listOf(
        Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red
    )
    val brush = Brush.sweepGradient(hues)
    
    // On garde la position du curseur en interne pour permettre un déplacement libre (partout dans le cercle)
    var cursorPosition by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .pointerInput(Unit) {
                fun updateAt(offset: Offset) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val dx = offset.x - center.x
                    val dy = offset.y - center.y
                    val dist = sqrt(dx.pow(2) + dy.pow(2))
                    val radius = size.width / 2f
                    
                    if (dist <= radius) {
                        cursorPosition = offset
                        val angle = (atan2(dy, dx) * 180 / PI + 360) % 360
                        // Saturation basée sur la distance du centre (0 au centre, 1 au bord)
                        val saturation = (dist / radius).coerceIn(0f, 1f)
                        onColorChanged(Color.hsv(angle.toFloat(), saturation, 1f))
                    }
                }
                detectTapGestures { updateAt(it) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val dx = change.position.x - center.x
                    val dy = change.position.y - center.y
                    val dist = sqrt(dx.pow(2) + dy.pow(2))
                    val radius = size.width / 2f
                    
                    if (dist <= radius) {
                        cursorPosition = change.position
                        val angle = (atan2(dy, dx) * 180 / PI + 360) % 360
                        val saturation = (dist / radius).coerceIn(0f, 1f)
                        onColorChanged(Color.hsv(angle.toFloat(), saturation, 1f))
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.width / 2f
            
            drawCircle(brush = brush)
            // Dégradé blanc au centre pour la saturation
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White, Color.Transparent), center, radius),
                radius = radius
            )

            // Calculer la position initiale du curseur si non défini
            val finalPos = cursorPosition ?: run {
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(selectedColor.toArgb(), hsv)
                val angleRad = hsv[0] * PI / 180f
                val dist = hsv[1] * radius
                Offset(
                    center.x + dist * cos(angleRad).toFloat(),
                    center.y + dist * sin(angleRad).toFloat()
                )
            }
            
            // Dessiner le curseur : Carré Petit
            val side = 10.dp.toPx()
            drawRect(
                color = Color.White,
                topLeft = Offset(finalPos.x - side / 2, finalPos.y - side / 2),
                size = Size(side, side),
                style = Stroke(width = 2.dp.toPx())
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.5f),
                topLeft = Offset(finalPos.x - side / 2 - 1.dp.toPx(), finalPos.y - side / 2 - 1.dp.toPx()),
                size = Size(side + 2.dp.toPx(), side + 2.dp.toPx()),
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Composant : Sélecteur de couleurs amélioré
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ChartColorPickerDialog(
    settings : SettingsManager,
    onDismiss : () -> Unit,
    onColorsUpdated : () -> Unit
) {
    var activeCurveKey by remember { mutableStateOf<String?>(null) }
    
    // État temporaire de la couleur pendant la sélection
    var tempColor by remember { mutableStateOf(Color.White) }

    val curves = listOf(
        "cpu"        to stringResource(R.string.charts_legend_cpu),
        "temp"       to stringResource(R.string.charts_legend_temp),
        "ram"        to stringResource(R.string.charts_legend_ram),
        "disk_read"  to stringResource(R.string.charts_legend_read),
        "disk_write" to stringResource(R.string.charts_legend_write),
        "net_rx"     to stringResource(R.string.charts_legend_download),
        "net_tx"     to stringResource(R.string.charts_legend_upload)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.charts_colors_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (activeCurveKey == null) {
                    curves.forEach { (key, label) ->
                        val default = when(key) {
                            "cpu", "disk_read", "net_rx" -> DEFAULT_COLOR_BLUE
                            "temp", "net_tx"             -> DEFAULT_COLOR_GREEN
                            "ram"                        -> DEFAULT_COLOR_PURPLE
                            else                         -> DEFAULT_COLOR_RED
                        }
                        val currentColor = settings.getChartColor(key, default).toComposeColor()
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { 
                                    activeCurveKey = key
                                    tempColor = currentColor
                                }
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(Modifier.size(28.dp).background(currentColor, CircleShape))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                } else {
                    // Cercle chromatique (Color Wheel)
                    val curveLabel = curves.find { it.first == activeCurveKey }?.second ?: ""
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            curveLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(16.dp))
                        
                        // Aperçu de la couleur choisie
                        Box(
                            modifier = Modifier
                                .size(80.dp, 40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(tempColor)
                                .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        )

                        Spacer(Modifier.height(16.dp))
                        
                        ColorWheel(
                            modifier = Modifier.size(220.dp),
                            selectedColor = tempColor,
                            onColorChanged = { tempColor = it }
                        )
                        
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Glissez pour choisir, puis validez avec OK",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (activeCurveKey != null) {
                TextButton(onClick = { 
                    activeCurveKey?.let { settings.setChartColor(it, tempColor.toHex()) }
                    activeCurveKey = null
                    onColorsUpdated()
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        },
        dismissButton = {
            if (activeCurveKey != null) {
                // Bouton retour/annuler quand on est dans le sélecteur
                TextButton(onClick = { activeCurveKey = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            } else {
                // Bouton réinitialisation globale quand on est sur la liste
                TextButton(onClick = {
                    settings.setChartColor("cpu",        DEFAULT_COLOR_BLUE)
                    settings.setChartColor("temp",       DEFAULT_COLOR_GREEN)
                    settings.setChartColor("ram",        DEFAULT_COLOR_PURPLE)
                    settings.setChartColor("disk_read",  DEFAULT_COLOR_BLUE)
                    settings.setChartColor("disk_write", DEFAULT_COLOR_RED)
                    settings.setChartColor("net_rx",     DEFAULT_COLOR_BLUE)
                    settings.setChartColor("net_tx",     DEFAULT_COLOR_GREEN)
                    onColorsUpdated()
                }) {
                    Text(stringResource(R.string.charts_colors_reset))
                }
            }
        }
    )
}
