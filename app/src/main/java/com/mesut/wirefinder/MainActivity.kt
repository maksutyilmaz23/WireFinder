package com.mesut.wirefinder

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt
import kotlin.math.abs

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null

    private var _x by mutableStateOf(0f)
    private var _y by mutableStateOf(0f)
    private var _z by mutableStateOf(0f)
    private var _field by mutableStateOf(0f)
    private var _baseline by mutableStateOf<Float?>(null)
    private var _delta by mutableStateOf(0f)
    private var _anomaly by mutableStateOf(false)

    private val samples = mutableStateListOf<Float>()
    private val scanPoints = mutableStateListOf<ScanPoint>()
    private var scanning by mutableStateOf(false)
    private var scanMin = 0f
    private var scanMax = 100f
    private var scanDirection = ScanDirection.HORIZONTAL
    private var scanProgress = 0f
    private var scanLine = 0f
    private var scanPeak = 0f

    enum class ScanDirection { HORIZONTAL, VERTICAL }
    data class ScanPoint(val x: Float, val y: Float, val intensity: Float)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        setContent {
            MaterialTheme {
                WireFinderScreen(
                    supported = magnetometer != null,
                    x = _x, y = _y, z = _z,
                    field = _field,
                    baseline = _baseline,
                    delta = _delta,
                    anomaly = _anomaly,
                    samples = samples,
                    scanning = scanning,
                    scanPoints = scanPoints,
                    scanDirection = scanDirection,
                    scanProgress = scanProgress,
                    scanPeak = scanPeak,
                    onDirectionChange = { scanDirection = it },
                    onCalibrate = { _baseline = if (_field > 0f) _field else null },
                    onStartScan = {
                        scanPoints.clear()
                        scanning = true
                        scanMin = 0f
                        scanMax = 100f
                        scanProgress = 0f
                        scanLine = 0f
                        scanPeak = 0f
                    },
                    onStopScan = { scanning = false },
                    onReset = {
                        _baseline = null
                        samples.clear()
                        scanPoints.clear()
                        scanning = false
                        _anomaly = false
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val b = sqrt(x*x + y*y + z*z)

        _x = x; _y = y; _z = z; _field = b

        val base = _baseline
        if (base != null) {
            _delta = abs(b - base)
            // Heuristic only: a magnetic anomaly, not proof of a wire.
            _anomaly = _delta >= 15f
        }

        samples.add(b)
        if (samples.size > 100) samples.removeAt(0)

        if (scanning && base != null) {
            val intensity = abs(b - base)
            scanMin = minOf(scanMin, intensity)
            scanMax = maxOf(scanMax, intensity, 1f)
            // A simple time-ordered path. The user moves the phone across the wall.
            scanPeak = maxOf(scanPeak, intensity)
            val i = scanPoints.size
            val width = 120f
            val lines = 12f
            val along = (i % width) / (width - 1f)
            val line = ((i / width) % lines) / (lines - 1f)
            val xPos = if (scanDirection == ScanDirection.HORIZONTAL) along * 119f else line * 119f
            val yPos = if (scanDirection == ScanDirection.HORIZONTAL) line * 5.99f else along * 5.99f
            scanProgress = (i % width) / (width - 1f)
            scanPoints.add(ScanPoint(xPos, yPos, intensity))
            if (scanPoints.size > 1440) scanPoints.removeAt(0)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

@Composable
private fun WireFinderScreen(
    supported: Boolean,
    x: Float, y: Float, z: Float,
    field: Float,
    baseline: Float?,
    delta: Float,
    anomaly: Boolean,
    samples: List<Float>,
    scanning: Boolean,
    scanPoints: List<MainActivity.ScanPoint>,
    scanDirection: MainActivity.ScanDirection,
    scanProgress: Float,
    scanPeak: Float,
    onDirectionChange: (MainActivity.ScanDirection) -> Unit,
    onCalibrate: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onReset: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Wire Finder") }) }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!supported) {
                Text("Bu cihazda manyetometre bulunamadı.", color = MaterialTheme.colorScheme.error)
                return@Column
            }

            Text("Manyetik Alan", style = MaterialTheme.typography.titleMedium)
            Text(String.format("%.2f µT", field), style = MaterialTheme.typography.displaySmall)

            StatusCard(anomaly)

            Text("X  ${String.format("%.2f", x)} µT")
            Text("Y  ${String.format("%.2f", y)} µT")
            Text("Z  ${String.format("%.2f", z)} µT")

            if (baseline != null) {
                Text("Referans: ${String.format("%.2f", baseline)} µT")
                Text("Değişim: ${String.format("%.2f", delta)} µT")
            } else {
                Text("Duvar taramasından önce duvardan uzakta referans alın.")
            }

            Text("Tarama yönü", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = scanDirection == MainActivity.ScanDirection.HORIZONTAL,
                    onClick = { onDirectionChange(MainActivity.ScanDirection.HORIZONTAL) },
                    label = { Text("Yatay") }
                )
                FilterChip(
                    selected = scanDirection == MainActivity.ScanDirection.VERTICAL,
                    onClick = { onDirectionChange(MainActivity.ScanDirection.VERTICAL) },
                    label = { Text("Dikey") }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCalibrate) { Text("Referans Al") }
                if (!scanning) {
                    Button(onClick = onStartScan, enabled = baseline != null) {
                        Text("Duvarı Tara")
                    }
                } else {
                    Button(onClick = onStopScan) { Text("Taramayı Durdur") }
                }
                OutlinedButton(onClick = onReset) { Text("Sıfırla") }
            }

            Text("Canlı Manyetik Grafik", style = MaterialTheme.typography.titleMedium)
            MagneticGraph(samples, Modifier.fillMaxWidth().height(120.dp))

            Text("Duvar Tarama Haritası", style = MaterialTheme.typography.titleMedium)
            WallScanMap(scanPoints, scanMin = 0f, scanMax = scanMax,
                Modifier.fillMaxWidth().height(240.dp))

            LinearProgressIndicator(
                progress = { scanProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Text("Tarama ilerlemesi: ${(scanProgress * 100).toInt()}%")
            Text("Tarama boyunca en yüksek değişim: ${String.format("%.2f", scanPeak)} µT")

            Text(
                if (scanning)
                    "Telefonu duvar üzerinde yavaşça yatay veya dikey hareket ettirin. Kırmızı bölgeler daha güçlü manyetik anomalileri gösterir."
                else
                    "Önce Referans Al'a basın, sonra Duvarı Tara ile taramayı başlatın.",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                "GÜVENLİK: Harita yalnızca manyetik alan anomalilerini gösterir. " +
                    "Kırmızı bölge elektrik kablosunun kesin kanıtı değildir. " +
                    "Delme/kesme öncesinde profesyonel elektrik hattı dedektörü kullanın.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun StatusCard(anomaly: Boolean) {
    val text = if (anomaly) "⚠ Manyetik alan anomalisi" else "✓ Normal / referans çevresinde"
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (anomaly)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun WallScanMap(
    points: List<MainActivity.ScanPoint>,
    scanMin: Float,
    scanMax: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        // Wall grid
        val cols = 12
        val rows = 8
        for (c in 0..cols) {
            val xx = size.width * c / cols
            drawLine(Color.LightGray, Offset(xx, 0f), Offset(xx, size.height), 1f)
        }
        for (r in 0..rows) {
            val yy = size.height * r / rows
            drawLine(Color.LightGray, Offset(0f, yy), Offset(size.width, yy), 1f)
        }

        if (points.isEmpty()) return@Canvas

        val maxX = 119f
        val maxY = 5.99f
        val range = (scanMax - scanMin).coerceAtLeast(1f)

        points.forEach { p ->
            val px = (p.x / maxX) * size.width
            val py = ((p.y / maxY).coerceIn(0f, 1f)) * size.height
            val n = ((p.intensity - scanMin) / range).coerceIn(0f, 1f)

            val color = when {
                n >= 0.75f -> Color.Red
                n >= 0.50f -> Color(0xFFFF9800)
                n >= 0.25f -> Color.Yellow
                else -> Color(0xFF66BB6A)
            }
            drawCircle(color = color, radius = 7f, center = Offset(px, py))
        }
    }
}

@Composable
private fun MagneticGraph(values: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.minOrNull() ?: return@Canvas
        val max = values.maxOrNull() ?: return@Canvas
        val range = (max - min).coerceAtLeast(0.01f)

        for (i in 1 until values.size) {
            val x1 = (i - 1) * size.width / (values.size - 1)
            val x2 = i * size.width / (values.size - 1)
            val y1 = size.height - ((values[i - 1] - min) / range) * size.height
            val y2 = size.height - ((values[i] - min) / range) * size.height
            drawLine(
                color = Color(0xFF1565C0),
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = 4f
            )
        }
    }
}
