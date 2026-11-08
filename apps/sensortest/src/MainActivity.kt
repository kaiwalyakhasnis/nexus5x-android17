// Sensor Test: every sensor SensorManager reports, with its properties and
// live values. Tap a sensor to start or stop it. Standard SDK API only: it
// checks the sensors HAL (hal/sensors) the way any app would see it.
//
// From a PC: adb shell am start -n dev.nexus5x.sensortest/.MainActivity
//   --ez all_on true   every sensor on at start
//   --ez log true      and every 2 s each sensor's line in logcat (tag SensorTest)
package dev.nexus5x.sensortest

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity(), SensorEventListener {

    private class Row(val sensor: Sensor, val view: TextView) {
        var on = false
        var values: FloatArray? = null
        var accuracy = -1
        var events = 0L
        var lastTimestamp = 0L
        var hz = 0.0
        var latencyMs = 0.0
    }

    private lateinit var sm: SensorManager
    private val rows = LinkedHashMap<Sensor, Row>()
    private val handler = Handler(Looper.getMainLooper())
    private var logEvery = 0     // refreshes between logcat dumps; 0: none
    private var refreshes = 0
    private val refresh = object : Runnable {
        override fun run() {
            rows.values.forEach(::render)
            if (logEvery > 0 && ++refreshes % logEvery == 0)
                rows.values.filter { it.on || it.values != null }.forEach {
                    Log.i("SensorTest", it.view.text.toString().replace("\n", " |"))
                }
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        sm = getSystemService(SensorManager::class.java)
        val sensors = sm.getSensorList(Sensor.TYPE_ALL)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        list.addView(text("${sensors.size} sensors · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}", 18f, bold = true))
        list.addView(text("Tap a sensor to start or stop it.", 14f, color = Color.LTGRAY))
        list.addView(LinearLayout(this).apply {
            addView(Button(this@MainActivity).apply { text = "All on"; setOnClickListener { rows.values.forEach { set(it, true) } } })
            addView(Button(this@MainActivity).apply { text = "All off"; setOnClickListener { rows.values.forEach { set(it, false) } } })
        })
        list.addView(text(defaults(), 13f, color = Color.LTGRAY, mono = true))
        list.addView(text(features(), 13f, color = Color.LTGRAY, mono = true))

        for (s in sensors) {
            val view = text("", 14f, mono = true).apply { setPadding(dp(10), dp(10), dp(10), dp(10)) }
            val row = Row(s, view)
            view.setOnClickListener { set(row, !row.on) }
            rows[s] = row
            list.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            render(row)
        }
        if (sensors.isEmpty()) list.addView(text("No sensors: is the sensors HAL running?", 16f, color = Color.YELLOW))

        val scroll = ScrollView(this).apply { addView(list) }
        setContentView(scroll)
        scroll.post { scroll.scrollTo(0, 0) }

        if (intent.getBooleanExtra("all_on", false)) rows.values.forEach { set(it, true) }
        if (intent.getBooleanExtra("log", false)) logEvery = 10

        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
    }

    override fun onResume() {
        super.onResume()
        rows.values.filter { it.on }.forEach(::listen)
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
        rows.values.filter { it.on && isOneShot(it.sensor) }.forEach { sm.cancelTriggerSensor(trigger, it.sensor) }
        handler.removeCallbacks(refresh)
    }

    private fun set(row: Row, on: Boolean) {
        if (row.on == on) return
        row.on = on
        if (on) {
            row.events = 0
            row.hz = 0.0
            row.lastTimestamp = 0
            listen(row)
        } else if (isOneShot(row.sensor)) {
            sm.cancelTriggerSensor(trigger, row.sensor)
        } else {
            sm.unregisterListener(this, row.sensor)
        }
        render(row)
    }

    private fun isOneShot(s: Sensor) = s.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT

    private fun listen(row: Row) {
        if (isOneShot(row.sensor)) sm.requestTriggerSensor(trigger, row.sensor)
        else sm.registerListener(this, row.sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private val trigger = object : TriggerEventListener() {
        override fun onTrigger(e: TriggerEvent) {
            rows[e.sensor]?.let {
                it.values = e.values.clone()
                it.events++
                it.on = false   // one-shot: disarmed after it fires
            }
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val row = rows[e.sensor] ?: return
        row.values = e.values.clone()
        row.accuracy = e.accuracy
        if (row.lastTimestamp != 0L && e.timestamp > row.lastTimestamp) {
            val hz = 1e9 / (e.timestamp - row.lastTimestamp)
            row.hz = if (row.hz == 0.0) hz else row.hz * 0.9 + hz * 0.1
        }
        row.lastTimestamp = e.timestamp
        // how old the event is when it gets here (both on the boot clock)
        row.latencyMs = (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1e6
        row.events++
    }

    override fun onAccuracyChanged(s: Sensor, accuracy: Int) {
        rows[s]?.accuracy = accuracy
    }

    // --- display

    private fun render(row: Row) {
        val s = row.sensor
        val mode = when (s.reportingMode) {
            Sensor.REPORTING_MODE_CONTINUOUS -> "continuous"
            Sensor.REPORTING_MODE_ON_CHANGE -> "on change"
            Sensor.REPORTING_MODE_ONE_SHOT -> "one shot"
            else -> "special"
        }
        val sb = StringBuilder()
        sb.append(if (row.on) "● " else "○ ").append(s.name).append('\n')
        sb.append("  ${s.vendor} · ${s.stringType.removePrefix("android.sensor.")}")
        sb.append(" · $mode").append(if (s.isWakeUpSensor) " · wake-up" else "").append('\n')
        sb.append("  range ${fmt(s.maximumRange)} res ${fmt(s.resolution)} · ")
        sb.append("delay ${s.minDelay}–${s.maxDelay} µs · ${fmt(s.power)} mA")
        val v = row.values
        if (row.on || v != null) {
            sb.append('\n')
            if (v == null) sb.append("  waiting for the first event…")
            else {
                sb.append("  ").append(valuesText(s.type, v))
                sb.append("\n  ${row.events} events")
                if (row.hz > 0) sb.append(" · ${"%.1f".format(row.hz)} Hz")
                if (row.events > 0 && !isOneShot(s)) sb.append(" · ${"%.0f".format(row.latencyMs)} ms old")
                if (row.accuracy >= 0) sb.append(" · accuracy ${accuracyText(row.accuracy)}")
            }
        }
        row.view.text = sb
        row.view.setBackgroundColor(if (row.on) 0xFF1E3A2A.toInt() else 0xFF222222.toInt())
    }

    private fun valuesText(type: Int, v: FloatArray): String {
        fun xyz(unit: String) = "x ${fmt(v[0])}  y ${fmt(v[1])}  z ${fmt(v[2])} $unit"
        return when (type) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION -> xyz("m/s²")
            Sensor.TYPE_GYROSCOPE -> xyz("rad/s")
            Sensor.TYPE_MAGNETIC_FIELD -> xyz("µT") + "  |B| ${fmt(Math.sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat())}"
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED ->
                "x ${fmt(v[0])} y ${fmt(v[1])} z ${fmt(v[2])}\n  bias ${fmt(v[3])} ${fmt(v[4])} ${fmt(v[5])}"
            Sensor.TYPE_PRESSURE -> "${fmt(v[0])} hPa"
            Sensor.TYPE_LIGHT -> "${fmt(v[0])} lx"
            Sensor.TYPE_PROXIMITY -> "${fmt(v[0])} cm" + if (v[0] < 5f) "  (near)" else "  (far)"
            Sensor.TYPE_STEP_COUNTER -> "${v[0].toLong()} steps since boot"
            Sensor.TYPE_STEP_DETECTOR -> "step!"
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                // as yaw/pitch/roll in degrees, easier to check by hand
                val m = FloatArray(9)
                val o = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(m, v)
                SensorManager.getOrientation(m, o)
                "azimuth ${deg(o[0])}°  pitch ${deg(o[1])}°  roll ${deg(o[2])}°"
            }
            else -> v.joinToString("  ") { fmt(it) }
        }
    }

    private fun accuracyText(a: Int) = when (a) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "high"
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "medium"
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "low"
        SensorManager.SENSOR_STATUS_UNRELIABLE -> "unreliable"
        else -> a.toString()
    }

    /** Which getDefaultSensor() types resolve: what most apps ask for */
    private fun defaults(): String {
        val types = listOf(
            "accelerometer" to Sensor.TYPE_ACCELEROMETER, "gyroscope" to Sensor.TYPE_GYROSCOPE,
            "magnetic" to Sensor.TYPE_MAGNETIC_FIELD, "pressure" to Sensor.TYPE_PRESSURE,
            "light" to Sensor.TYPE_LIGHT, "proximity" to Sensor.TYPE_PROXIMITY,
            "gravity" to Sensor.TYPE_GRAVITY, "rotation" to Sensor.TYPE_ROTATION_VECTOR,
            "steps" to Sensor.TYPE_STEP_COUNTER, "sig.motion" to Sensor.TYPE_SIGNIFICANT_MOTION,
        )
        return "getDefaultSensor():\n" + types.joinToString("\n") { (n, t) ->
            val s = sm.getDefaultSensor(t)
            "  ${n.padEnd(14)} " + (s?.let { "✓ ${it.name}" + if (it.isWakeUpSensor) " (wake-up)" else "" } ?: "–")
        }
    }

    /** The android.hardware.sensor.* features apps (and Play) filter on */
    private fun features(): String {
        val pm = packageManager
        val names = listOf("accelerometer", "gyroscope", "compass", "barometer", "light", "proximity",
            "stepcounter", "stepdetector", "ambient_temperature", "relative_humidity")
        return "features:\n" + names.joinToString("\n") {
            "  ${it.padEnd(20)} " + if (pm.hasSystemFeature("android.hardware.sensor.$it")) "✓" else "–"
        }
    }

    private fun fmt(f: Float) = when {
        f == 0f -> "0"
        Math.abs(f) >= 100 -> "%.1f".format(f)
        Math.abs(f) >= 0.01 -> "%.3f".format(f)
        else -> "%.2e".format(f)
    }

    private fun deg(r: Float) = "%.0f".format(Math.toDegrees(r.toDouble()))

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            gravity = Gravity.START
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.DEFAULT_BOLD
                else -> Typeface.DEFAULT
            }
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
}
