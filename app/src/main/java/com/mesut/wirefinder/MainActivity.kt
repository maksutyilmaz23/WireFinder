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
    private var scanMinX = 0f
    private var scanMaxX = 0f
    private var scanMinY = 0f
    private var scanMaxY = 0f
    private var scanPeak = 0f

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
        when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> handleMagnetometer(event)
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event)
            Sensor.TYPE_LINEAR_ACCELERATION -> handleLinearAcceleration(event)
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

        if (scanning && base != null) {
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
    minX: Float, maxX: Float,
    minY: Float, maxY: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
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

        // Toplanan konumlara göre otomatik ölçekleme; en az 0.3m aralık varsay (bölme hatasını önler)
        val rangeX = (maxX - minX).coerceAtLeast(0.3f)
        val rangeY = (maxY - minY).coerceAtLeast(0.3f)
        val intensities = points.map { it.intensity }
        val iMin = intensities.minOrNull() ?: 0f
        val iRange = ((intensities.maxOrNull() ?: 1f) - iMin).coerceAtLeast(1f)

        // Merkezi koru: harita alanının %90'ını kullan, kenarda boşluk bırak
        val margin = 0.05f
        points.forEach { p ->
            val nx = ((p.x - minX) / rangeX)
            val ny = ((p.y - minY) / rangeY)
            val px = (margin + nx * (1f - 2 * margin)) * size.width
            val py = (margin + ny * (1f - 2 * margin)) * size.height
            val n = ((p.intensity - iMin) / iRange).coerceIn(0f, 1f)

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
