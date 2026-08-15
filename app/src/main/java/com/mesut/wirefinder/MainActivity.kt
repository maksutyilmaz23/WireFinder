package com.mesut.wirefinder

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null
    private var linearAccel: Sensor? = null
    private var rotationVector: Sensor? = null

    // --- Manyetik alan durumu ---
    private var _x by mutableStateOf(0f)
    private var _y by mutableStateOf(0f)
    private var _z by mutableStateOf(0f)
    private var _field by mutableStateOf(0f)
    private var _baseline by mutableStateOf<Float?>(null)
    private var _delta by mutableStateOf(0f)
    private var _anomaly by mutableStateOf(false)
    private val samples = mutableStateListOf<Float>()

    // --- Konum takibi (ivmeölçer + rotasyon vektörü ile dead-reckoning) ---
    private val rotationMatrix = FloatArray(9)
    private var hasRotation = false
    private var lastAccelTimestampNs = 0L
    private var velX = 0f
    private var velY = 0f
    private var posX = 0f
    private var posY = 0f
    private val accelMagWindow = ArrayDeque<Float>()
    private var stillSince = 0L
    private var trackingSupported = true

    private val scanPoints = mutableStateListOf<ScanPoint>()
    private var scanning by mutableStateOf(false)
    private var scanPointCount by mutableStateOf(0)
    private var scanElapsedMs by mutableStateOf(0L)
    private var scanStartMs = 0L
    private var scanMinX by mutableStateOf(0f)
    private var scanMaxX by mutableStateOf(0f)
    private var scanMinY by mutableStateOf(0f)
    private var scanMaxY by mutableStateOf(0f)
    private var scanPeak by mutableStateOf(0f)

    data class ScanPoint(val x: Float, val y: Float, val intensity: Float)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        trackingSupported = linearAccel != null && rotationVector != null

        setContent {
            MaterialTheme {
                WireFinderScreen(
                    supported = magnetometer != null,
                    trackingSupported = trackingSupported,
                    x = _x, y = _y, z = _z,
                    field = _field,
                    baseline = _baseline,
                    delta = _delta,
                    anomaly = _anomaly,
                    samples = samples,
                    scanning = scanning,
                    scanPoints = scanPoints,
                    scanPointCount = scanPointCount,
                    scanElapsedMs = scanElapsedMs,
                    scanPeak = scanPeak,
                    scanMinX = scanMinX, scanMaxX = scanMaxX,
                    scanMinY = scanMinY, scanMaxY = scanMaxY,
                    onCalibrate = { _baseline = if (_field > 0f) _field else null },
                    onStartScan = {
                        scanPoints.clear()
                        scanning = true
                        scanPointCount = 0
                        scanStartMs = System.currentTimeMillis()
                        scanElapsedMs = 0L
                        scanPeak = 0f
                        // Konum takibini bu noktadan sıfırla
                        velX = 0f; velY = 0f; posX = 0f; posY = 0f
                        scanMinX = 0f; scanMaxX = 0f; scanMinY = 0f; scanMaxY = 0f
                        accelMagWindow.clear()
                        stillSince = 0L
                        lastAccelTimestampNs = 0L
                    },
                    onStopScan = { scanning = false },
                    onReset = {
                        _baseline = null
                        samples.clear()
                        scanPoints.clear()
                        scanning = false
                        _anomaly = false
                        velX = 0f; velY = 0f; posX = 0f; posY = 0f
                        scanPeak = 0f
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linearAccel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        try {
            when (event.sensor.type) {
                Sensor.TYPE_MAGNETIC_FIELD -> handleMagnetometer(event)
                Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event)
                Sensor.TYPE_LINEAR_ACCELERATION -> handleLinearAcceleration(event)
            }
        } catch (_: Exception) {
            // Beklenmeyen bir sensör hatası uygulamayı çökertmesin.
        }
    }

    private fun handleMagnetometer(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val b = sqrt(x * x + y * y + z * z)

        _x = x; _y = y; _z = z; _field = b

        val base = _baseline
        var intensity = 0f
        if (base != null) {
            _delta = abs(b - base)
            // Sezgisel bir eşik: kesin kablo kanıtı değildir.
            _anomaly = _delta >= 15f
            intensity = _delta
        }

        samples.add(b)
        if (samples.size > 100) samples.removeAt(0)

        if (scanning && base != null && posX.isFinite() && posY.isFinite()) {
            scanPeak = maxOf(scanPeak, intensity)
            scanPointCount = scanPoints.size + 1
            scanElapsedMs = System.currentTimeMillis() - scanStartMs
            scanPoints.add(ScanPoint(posX, posY, intensity))
            if (posX < scanMinX) scanMinX = posX
            if (posX > scanMaxX) scanMaxX = posX
            if (posY < scanMinY) scanMinY = posY
            if (posY > scanMaxY) scanMaxY = posY
            if (scanPoints.size > 1440) scanPoints.removeAt(0)
        }
    }

    private fun handleRotationVector(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        hasRotation = true
    }

    // İvmeölçer + rotasyon verisini çift entegre ederek göreli konum (posX, posY) hesaplar.
    // Not: Bu bir "dead reckoning" tahminidir, GPS değildir; zamanla sapma (drift) birikir.
    // Basit bir "sıfır hız güncellemesi" (ZUPT) sapmayı azaltmak için kullanılır.
    private fun handleLinearAcceleration(event: SensorEvent) {
        if (!hasRotation || !scanning) {
            lastAccelTimestampNs = event.timestamp
            return
        }
        if (lastAccelTimestampNs == 0L) {
            lastAccelTimestampNs = event.timestamp
            return
        }
        val dt = (event.timestamp - lastAccelTimestampNs) / 1_000_000_000f
        lastAccelTimestampNs = event.timestamp
        if (dt <= 0f || dt > 0.5f) return

        // Cihaz eksenindeki ivmeyi dünya eksenine (Doğu-Kuzey-Yukarı) çevir.
        val ax = rotationMatrix[0] * event.values[0] + rotationMatrix[1] * event.values[1] + rotationMatrix[2] * event.values[2]
        val ay = rotationMatrix[3] * event.values[0] + rotationMatrix[4] * event.values[1] + rotationMatrix[5] * event.values[2]

        if (!ax.isFinite() || !ay.isFinite()) return

        val accelMag = sqrt(ax * ax + ay * ay)
        accelMagWindow.addLast(accelMag)
        if (accelMagWindow.size > 12) accelMagWindow.removeFirst()

        val nowNs = event.timestamp
        val avgMag = accelMagWindow.average().toFloat()
        if (avgMag < 0.12f) {
            if (stillSince == 0L) stillSince = nowNs
            // ~250ms boyunca hareketsizse hızı sıfırla (sapma birikimini önler)
            if ((nowNs - stillSince) > 250_000_000L) {
                velX = 0f
                velY = 0f
            }
        } else {
            stillSince = 0L
        }

        velX += ax * dt
        velY += ay * dt
        posX += velX * dt
        posY += velY * dt

        // Savunma: herhangi bir sayısal bozulma (NaN/sonsuz) konum takibini kalıcı olarak
        // bozmasın diye anında sıfırlanır.
        if (!posX.isFinite() || !posY.isFinite() || !velX.isFinite() || !velY.isFinite()) {
            posX = 0f; posY = 0f; velX = 0f; velY = 0f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun WireFinderScreen(
    supported: Boolean,
    trackingSupported: Boolean,
    x: Float, y: Float, z: Float,
    field: Float,
    baseline: Float?,
    delta: Float,
    anomaly: Boolean,
    samples: List<Float>,
    scanning: Boolean,
    scanPoints: List<MainActivity.ScanPoint>,
    scanPointCount: Int,
    scanElapsedMs: Long,
    scanPeak: Float,
    scanMinX: Float, scanMaxX: Float,
    scanMinY: Float, scanMaxY: Float,
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
            if (!trackingSupported) {
                Text(
                    "Bu cihazda konum takibi için gereken sensörler (ivmeölçer/rotasyon) bulunamadı. " +
                        "Harita otomatik konumlandırılamayacak.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
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

            Text("Duvar Tarama Haritası (otomatik konum)", style = MaterialTheme.typography.titleMedium)
            WallScanMap(
                scanPoints,
                minX = scanMinX, maxX = scanMaxX,
                minY = scanMinY, maxY = scanMaxY,
                modifier = Modifier.fillMaxWidth().height(240.dp)
            )
            val spanX = scanMaxX - scanMinX
            val spanY = scanMaxY - scanMinY
            Text(
                "Taranan alan (yaklaşık): ${String.format("%.2f", spanX)} m × ${String.format("%.2f", spanY)} m",
                style = MaterialTheme.typography.bodySmall
            )
            LegendRow()

            Text("Toplanan nokta: $scanPointCount   Süre: ${scanElapsedMs / 1000}s")
            Text("Tarama boyunca en yüksek değişim: ${String.format("%.2f", scanPeak)} µT")

            Text(
                if (scanning)
                    "Telefonu duvar üzerinde yavaşça, sabit mesafede gezdirin. Yön seçmenize gerek yok; " +
                        "uygulama hareketinizi ivmeölçer ve rotasyon sensörüyle otomatik izler. Kırmızı bölgeler " +
                        "daha güçlü manyetik anomalileri gösterir."
                else
                    "Önce Referans Al'a basın, sonra Duvarı Tara ile taramayı başlatın.",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                "NOT: Konum takibi GPS değildir; ivmeölçer tabanlı bir tahmindir ve uzun taramalarda sapma " +
                    "(drift) birikebilir. Sapmayı azaltmak için telefonu yavaş ve düzenli hareket ettirin, " +
                    "gerekirse taramayı kısa tutup 'Sıfırla' ile yeniden başlayın.",
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
private fun LegendRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LegendChip(Color(0xFF2E7D32), "Düşük")
        LegendChip(Color(0xFFC0CA33), "Orta")
        LegendChip(Color(0xFFFF9800), "Yüksek")
        LegendChip(Color(0xFFD32F2F), "Olası kablo")
    }
}

@Composable
private fun LegendChip(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, shape = androidx.compose.foundation.shape.CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
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

private fun heatColor(t: Float): Color {
    // Sürekli renk skalası: koyu yeşil -> sarı -> turuncu -> kırmızı
    val stops = listOf(
        0.00f to Color(0xFF2E7D32),
        0.35f to Color(0xFFC0CA33),
        0.65f to Color(0xFFFF9800),
        1.00f to Color(0xFFD32F2F)
    )
    val clamped = t.coerceIn(0f, 1f)
    var lo = stops.first()
    var hi = stops.last()
    for (i in 0 until stops.size - 1) {
        if (clamped >= stops[i].first && clamped <= stops[i + 1].first) {
            lo = stops[i]; hi = stops[i + 1]
            break
        }
    }
    val span = (hi.first - lo.first).coerceAtLeast(0.0001f)
    val f = ((clamped - lo.first) / span).coerceIn(0f, 1f)
    return Color(
        red = lo.second.red + (hi.second.red - lo.second.red) * f,
        green = lo.second.green + (hi.second.green - lo.second.green) * f,
        blue = lo.second.blue + (hi.second.blue - lo.second.blue) * f,
        alpha = 1f
    )
}

@Composable
private fun WallScanMap(
    points: List<MainActivity.ScanPoint>,
    minX: Float, maxX: Float,
    minY: Float, maxY: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        try {
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

            // Yalnızca geçerli (NaN/sonsuz olmayan) noktaları kullan.
            val safePoints = points.filter { it.x.isFinite() && it.y.isFinite() && it.intensity.isFinite() }
            if (safePoints.isEmpty()) return@Canvas

            // Gerçek fiziksel oranları bozmamak için X ve Y aynı ölçek faktörüyle çizilir
            // (aksi halde dikdörtgen bir canvas, gezdiğiniz rotayı esnetip yanlış bir şekle sokar).
            val rangeX = (maxX - minX).coerceAtLeast(0.3f)
            val rangeY = (maxY - minY).coerceAtLeast(0.3f)
            val uniformRange = maxOf(rangeX, rangeY)
            val midX = (minX + maxX) / 2f
            val midY = (minY + maxY) / 2f
            val effMinX = midX - uniformRange / 2f
            val effMinY = midY - uniformRange / 2f

            val intensities = safePoints.map { it.intensity }
            val iMin = intensities.minOrNull() ?: 0f
            val iRange = ((intensities.maxOrNull() ?: 1f) - iMin).coerceAtLeast(1f)

            // Merkezi koru: harita alanının %88'ini kullan, kenarda boşluk bırak
            val margin = 0.06f
            val usable = 1f - 2 * margin
            // Canvas kare olmayabilir; kısa kenara göre kareye oturt ki gerçek oranlar korunsun.
            val squareSide = minOf(size.width, size.height) * usable
            val offsetX = (size.width - squareSide) / 2f
            val offsetY = (size.height - squareSide) / 2f

            fun toOffset(p: MainActivity.ScanPoint): Offset {
                val nx = (p.x - effMinX) / uniformRange
                val ny = (p.y - effMinY) / uniformRange
                return Offset(offsetX + nx * squareSide, offsetY + ny * squareSide)
            }
            fun colorFor(p: MainActivity.ScanPoint): Color {
                val n = ((p.intensity - iMin) / iRange).coerceIn(0f, 1f)
                return heatColor(n)
            }

            // Telefonu gezdirdiğiniz rotayı, o andaki manyetik değişime göre renklendirilmiş
            // kalın bir iz olarak çiz — böylece duvarın hangi bölgesinden geçildiği ve
            // orada ölçülen değer birlikte görünür.
            val trailWidth = 22f
            for (i in 1 until safePoints.size) {
                val prev = safePoints[i - 1]
                val cur = safePoints[i]
                drawLine(
                    color = colorFor(cur),
                    start = toOffset(prev),
                    end = toOffset(cur),
                    strokeWidth = trailWidth,
                    cap = StrokeCap.Round
                )
            }

            // Başlangıç noktasını mavi bir işaretle, en son (güncel) konumu beyaz halkayla vurgula
            drawCircle(color = Color(0xFF1565C0), radius = trailWidth * 0.5f, center = toOffset(safePoints.first()))
            if (safePoints.size > 1) {
                val last = toOffset(safePoints.last())
                drawCircle(color = Color.White, radius = trailWidth * 0.55f, center = last, style = Stroke(width = 4f))
                drawCircle(color = colorFor(safePoints.last()), radius = trailWidth * 0.4f, center = last)
            }
        } catch (_: Exception) {
            // Beklenmeyen bir çizim hatası uygulamayı çökertmesin; bu kare atlanır.
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
