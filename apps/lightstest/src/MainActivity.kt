// Lights Test: the screen backlight and the notification LED, the way any
// app can reach them (public API only): it checks the lights HAL
// (hal/lights) and the framework settings (vendor/overlay-lights).
//
//  - Brightness: this window's brightness (WindowManager.LayoutParams):
//    DisplayManager -> LightsService -> ILights BACKLIGHT -> the WLED.
//  - Notification LED: a notification on a channel with a light colour.
//    Android lights the LED only while the screen is off: post, then press
//    power. NotificationManagerService -> ILights NOTIFICATIONS -> the LPG.
//
// From a PC: adb shell am start -n dev.nexus5x.lightstest/.MainActivity
//   --es led red|green|blue|white [--ez blink true]   post at once
//   --ef brightness 0.0..1.0                           set this window's
package dev.nexus5x.lightstest

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private val colors = linkedMapOf(
        "red" to Color.RED, "green" to Color.GREEN, "blue" to Color.BLUE, "white" to Color.WHITE,
    )
    private lateinit var nm: NotificationManager
    private lateinit var status: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        nm = getSystemService(NotificationManager::class.java)
        createChannels()

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        list.addView(text("Brightness (this window)", 18f))
        val value = text("", 14f)
        list.addView(SeekBar(this).apply {
            max = 100
            progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                    setBrightness(p / 100f)
                    value.text = "window brightness ${p}%"
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })
        list.addView(value)
        list.addView(Button(this).apply {
            text = "System brightness again"
            setOnClickListener { setBrightness(-1f); value.text = "following the system setting" }
        })

        list.addView(text("\nNotification LED", 18f))
        list.addView(text("Post one, then press power: Android lights the LED only while the screen is off.", 14f, Color.LTGRAY))
        for (blink in listOf(false, true)) {
            list.addView(LinearLayout(this).apply {
                for ((name, _) in colors) addView(Button(this@MainActivity).apply {
                    text = if (blink) "$name ✱" else name
                    setOnClickListener { post(name, blink) }
                })
            })
        }
        list.addView(text("✱ blinking: 1 s on, 2 s off", 13f, Color.LTGRAY))
        list.addView(Button(this).apply { text = "LED off (cancel all)"; setOnClickListener { nm.cancelAll(); refresh() } })

        status = text("", 13f, Color.LTGRAY)
        list.addView(status)
        setContentView(ScrollView(this).apply { addView(list) })

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        intent.getStringExtra("led")?.let { post(it, intent.getBooleanExtra("blink", false)) }
        if (intent.hasExtra("brightness")) setBrightness(intent.getFloatExtra("brightness", -1f))
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** -1: follow the system setting */
    private fun setBrightness(v: Float) {
        window.attributes = window.attributes.apply { screenBrightness = v }
    }

    /** One channel per colour (a channel's light colour is fixed once created) */
    private fun createChannels() {
        for ((name, color) in colors) for (blink in listOf(false, true)) {
            nm.createNotificationChannel(NotificationChannel(channelId(name, blink), "LED $name" + if (blink) " (blink)" else "",
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                enableLights(true)
                lightColor = color
                setSound(null, null)
            })
        }
    }

    private fun channelId(name: String, blink: Boolean) = "led_$name" + if (blink) "_blink" else ""

    private fun post(name: String, blink: Boolean) {
        val color = colors[name] ?: return
        val n = Notification.Builder(this, channelId(name, blink))
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle("LED: $name" + if (blink) ", blinking" else "")
            .setContentText("Press power: the LED lights while the screen is off")
            .setColor(color)
            .build()
        @Suppress("DEPRECATION")
        n.apply {
            // the channel gives the colour; on/off times (steady: always on)
            ledARGB = color
            ledOnMS = if (blink) 1000 else 1
            ledOffMS = if (blink) 2000 else 0
            flags = flags or Notification.FLAG_SHOW_LIGHTS
        }
        nm.notify(name.hashCode(), n)
        refresh()
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        val cr = contentResolver
        val mode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
        status.text = "\nsystem brightness ${Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1)}/255 · " +
            (if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) "automatic" else "manual") +
            "\nposted notifications ${nm.activeNotifications.size}"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
    }
}
