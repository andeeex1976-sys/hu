package com.example.hulauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.location.Geocoder
import android.location.LocationListener
import android.location.LocationManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val h = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        web = WebView(this)
        setContentView(web)
        web.setBackgroundColor(Color.BLACK)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")
        askLocation()
        h.post(poll)
    }

    override fun onBackPressed() { /* launcher: abaikan tombol back */ }

    // ---------- Lokasi & kecepatan ----------
    private fun askLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        } else startLoc()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) startLoc()
    }

    @SuppressLint("MissingPermission")
    private fun startLoc() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val l = LocationListener { loc ->
            val kmh = if (loc.hasSpeed()) loc.speed * 3.6f else -1f
            web.evaluateJavascript("onLoc(${loc.latitude},${loc.longitude},$kmh)", null)
        }
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l) } catch (e: Exception) {}
        try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, l) } catch (e: Exception) {}
    }

    // ---------- Media ----------
    private fun ctl(): MediaController? = try {
        val m = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val l = m.getActiveSessions(ComponentName(this, MediaListener::class.java))
        l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: l.firstOrNull()
    } catch (e: SecurityException) { null }

    private fun label(p: String) = try {
        packageManager.getApplicationInfo(p, 0).loadLabel(packageManager).toString()
    } catch (e: Exception) { p }

    private val poll = object : Runnable {
        override fun run() {
            val c = ctl()
            val j = JSONObject()
            if (c != null) {
                val md = c.metadata
                j.put("t", md?.getString("android.media.metadata.TITLE") ?: "")
                j.put("a", md?.getString("android.media.metadata.ARTIST") ?: "")
                j.put("p", c.playbackState?.state == PlaybackState.STATE_PLAYING)
                j.put("app", label(c.packageName))
                j.put("pkg", c.packageName)
            }
            web.evaluateJavascript("onMedia($j)", null)
            h.postDelayed(this, 1000)
        }
    }

    private fun icon(d: Drawable): String {
        val bm = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 96, 96)
        d.draw(Canvas(bm))
        val o = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.PNG, 100, o)
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }

    // ---------- Jembatan ke HTML ----------
    inner class Bridge {
        @JavascriptInterface
        fun apps(): String {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val arr = JSONArray()
            packageManager.queryIntentActivities(i, 0)
                .filter { it.activityInfo.packageName != packageName }
                .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
                .forEach {
                    arr.put(JSONObject()
                        .put("p", it.activityInfo.packageName)
                        .put("n", it.loadLabel(packageManager).toString())
                        .put("i", icon(it.loadIcon(packageManager))))
                }
            return arr.toString()
        }

        @JavascriptInterface
        fun launch(pkg: String) = runOnUiThread {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
            }
        }

        @JavascriptInterface
        fun play() = runOnUiThread {
            ctl()?.let {
                if (it.playbackState?.state == PlaybackState.STATE_PLAYING) it.transportControls.pause()
                else it.transportControls.play()
            }
        }

        @JavascriptInterface
        fun next() = runOnUiThread { ctl()?.transportControls?.skipToNext() }

        @JavascriptInterface
        fun prev() = runOnUiThread { ctl()?.transportControls?.skipToPrevious() }

        @JavascriptInterface
        fun hasAccess(): Boolean =
            (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)

        @JavascriptInterface
        fun openAccess() = runOnUiThread {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        @JavascriptInterface
        fun place(lat: Double, lon: Double): String = try {
            @Suppress("DEPRECATION")
            val x = Geocoder(this@MainActivity).getFromLocation(lat, lon, 1)!![0]
            "${x.subLocality ?: x.thoroughfare ?: ""} · ${x.locality ?: x.subAdminArea ?: ""}".trim(' ', '·')
        } catch (e: Exception) { "" }
    }
}
