package shield.loc.producer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.round

/** Active GPS requester for E-PAS-01. Action: produce [--ei secs N]. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val secs = intent.getIntExtra("secs", 90)
        Thread {
            try {
                produce(secs)
            } catch (t: Throwable) {
                Diag.emit(this, "produce", mapOf("fatal" to t.javaClass.simpleName))
            } finally {
                Diag.emit(this, "produce", mapOf("done" to true))
                runOnUiThread { finish() }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun produce(secs: Int) {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val count = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val listener = LocationListener { loc: Location ->
            val n = count.incrementAndGet()
            Diag.emit(
                this, "produce",
                mapOf(
                    "n" to n,
                    "lat" to round(loc.latitude * 100) / 100,
                    "lon" to round(loc.longitude * 100) / 100,
                    "provider" to loc.provider,
                ),
            )
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LocationRequest.Builder(1000L).build(),
                    mainExecutor,
                    listener,
                )
            } else {
                @Suppress("DEPRECATION")
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            }
            Diag.emit(this, "produce", mapOf("registered" to "gps"))
            latch.await(secs.toLong(), TimeUnit.SECONDS)
        } catch (t: Throwable) {
            Diag.emit(this, "produce", mapOf("error" to t.javaClass.simpleName))
        } finally {
            try {
                lm.removeUpdates(listener)
            } catch (_: Throwable) {
            }
            Diag.emit(this, "produce", mapOf("received" to count.get()))
        }
    }
}

object Diag {
    const val TAG = "LS_DIAG"

    fun emit(ctx: Context, action: String, fields: Map<String, Any?>) {
        val sb = StringBuilder()
        sb.append("{pkg=").append(ctx.packageName).append(", action=").append(action)
        for ((k, v) in fields) sb.append(", ").append(k).append('=').append(v)
        sb.append('}')
        val line = sb.toString()
        Log.i(TAG, line)
        try {
            File(ctx.filesDir, "$action.jsonl").appendText(line + "\n")
        } catch (_: Throwable) {
        }
    }
}
