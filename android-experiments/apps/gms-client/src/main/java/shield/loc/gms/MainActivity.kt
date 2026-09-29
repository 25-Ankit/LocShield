package shield.loc.gms

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.round

/**
 * GMS-side diagnostic. Actions: flp-updates | flp-current | flp-last | flp-avail.
 * GMS REQUIRED.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra("action") ?: "flp-avail"
        Thread {
            try {
                runAction(action)
            } catch (t: Throwable) {
                Diag.emit(this, action, mapOf("fatal" to t.javaClass.simpleName, "msg" to (t.message ?: "")))
            } finally {
                Diag.emit(this, action, mapOf("done" to true))
                runOnUiThread { finish() }
            }
        }.start()
    }

    private fun client() = LocationServices.getFusedLocationProviderClient(this)

    private fun runAction(action: String) {
        when (action) {
            "flp-updates" -> flpUpdates(action)
            "flp-current" -> flpCurrent(action)
            "flp-last" -> flpLast(action)
            "flp-avail" -> flpAvail(action)
            else -> Diag.emit(this, action, mapOf("error" to "unknown-action"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun flpUpdates(action: String) {
        val count = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    val n = count.incrementAndGet()
                    Diag.emit(this@MainActivity, action, Diag.loc("flp-updates", loc, mapOf("n" to n)))
                    if (n >= 3) latch.countDown()
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                Diag.emit(this@MainActivity, action, mapOf("available" to availability.isLocationAvailable))
            }
        }
        try {
            // Priority + granularity from intent extras (defaults: high accuracy, permission level).
            val prio = intent.getIntExtra("prio", Priority.PRIORITY_HIGH_ACCURACY)
            val gran = intent.getIntExtra("gran", Granularity.GRANULARITY_PERMISSION_LEVEL)
            val req = LocationRequest.Builder(prio, 1000L).setGranularity(gran).build()
            Diag.emit(this, action, mapOf("request" to "prio=$prio gran=$gran"))
            client().requestLocationUpdates(req, cb, Looper.getMainLooper())
            latch.await(60, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            Diag.emit(this, action, mapOf("error" to t.javaClass.simpleName, "msg" to (t.message ?: "")))
        } finally {
            try {
                client().removeLocationUpdates(cb)
            } catch (_: Throwable) {
            }
            Diag.emit(this, action, mapOf("received" to count.get()))
        }
    }

    @SuppressLint("MissingPermission")
    private fun flpCurrent(action: String) {
        try {
            val req = CurrentLocationRequest.Builder()
                .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                .setMaxUpdateAgeMillis(0L)
                .build()
            val task = client().getCurrentLocation(req, null)
            val loc = com.google.android.gms.tasks.Tasks.await(task, 45, TimeUnit.SECONDS)
            if (loc == null) {
                Diag.emit(this, action, mapOf("result" to "null"))
            } else {
                Diag.emit(this, action, Diag.loc("flp-current", loc))
            }
        } catch (t: Throwable) {
            Diag.emit(this, action, mapOf("error" to t.javaClass.simpleName, "msg" to (t.message ?: "")))
        }
    }

    @SuppressLint("MissingPermission")
    private fun flpLast(action: String) {
        try {
            val loc = com.google.android.gms.tasks.Tasks.await(client().lastLocation, 30, TimeUnit.SECONDS)
            if (loc == null) {
                Diag.emit(this, action, mapOf("result" to "null"))
            } else {
                Diag.emit(this, action, Diag.loc("flp-last", loc))
            }
        } catch (t: Throwable) {
            Diag.emit(this, action, mapOf("error" to t.javaClass.simpleName, "msg" to (t.message ?: "")))
        }
    }

    private fun flpAvail(action: String) {
        try {
            val avail = com.google.android.gms.tasks.Tasks.await(client().locationAvailability, 30, TimeUnit.SECONDS)
            Diag.emit(this, action, mapOf("available" to (avail?.isLocationAvailable)))
        } catch (t: Throwable) {
            Diag.emit(this, action, mapOf("error" to t.javaClass.simpleName, "msg" to (t.message ?: "")))
        }
    }
}

object Diag {
    const val TAG = "LS_DIAG"

    fun loc(kind: String, loc: Location, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>(
            "kind" to kind,
            "lat" to round(loc.latitude * 100) / 100,
            "lon" to round(loc.longitude * 100) / 100,
            "acc" to loc.accuracy,
            "provider" to loc.provider,
            "elapsedNanos" to loc.elapsedRealtimeNanos,
            "time" to loc.time,
        )
        m.putAll(extra)
        return m
    }

    fun emit(ctx: Context, action: String, fields: Map<String, Any?>) {
        val sb = StringBuilder()
        sb.append("{pkg=").append(ctx.packageName)
        sb.append(", action=").append(action)
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
