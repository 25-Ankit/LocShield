package shield.loc.client

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.round

/**
 * Headless diagnostic activity. Launch with `--es action <name>`.
 * Actions: lms-updates | lms-current | lms-last | gnss-status | gnss-meas |
 *          nmea | perm-state
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra("action") ?: "perm-state"
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

    private fun runAction(action: String) {
        when (action) {
            "perm-state" -> emitPerms(action)
            "lms-updates" -> lmsUpdates(action)
            "lms-current" -> lmsCurrent(action)
            "lms-last" -> lmsLast(action)
            "gnss-status" -> gnssStatus(action)
            "gnss-meas" -> gnssMeas(action)
            "nmea" -> nmea(action)
            else -> Diag.emit(this, action, mapOf("error" to "unknown-action"))
        }
    }

    private fun emitPerms(action: String) {
        Diag.emit(
            this, action,
            mapOf(
                "api" to Build.VERSION.SDK_INT,
                "fine" to checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION).toString(),
                "coarse" to checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION).toString(),
                "bg" to if (Build.VERSION.SDK_INT >= 29)
                    checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION).toString()
                else "n/a",
            ),
        )
    }

    private fun lm(): LocationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    private fun lmsUpdates(action: String) {
        emitPerms(action)
        val count = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val listener = LocationListener { loc: Location ->
            val n = count.incrementAndGet()
            Diag.emit(this, action, Diag.loc("lms-updates", loc, mapOf("n" to n)))
            if (n >= 3) latch.countDown()
        }
        val providers = lm().getProviders(true)
        Diag.emit(this, action, mapOf("enabledProviders" to providers.toString()))
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    if (Build.VERSION.SDK_INT >= 31) {
                        lm().requestLocationUpdates(p, LocationRequest.Builder(1000L).build(), mainExecutor, listener)
                    } else {
                        @Suppress("DEPRECATION")
                        lm().requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
                    }
                    Diag.emit(this, action, mapOf("registered" to p))
                } catch (t: Throwable) {
                    Diag.emit(this, action, mapOf("registerFail" to p, "err" to t.javaClass.simpleName))
                }
            }
            latch.await(45, TimeUnit.SECONDS)
        } finally {
            try {
                lm().removeUpdates(listener)
            } catch (_: Throwable) {
            }
            Diag.emit(this, action, mapOf("received" to count.get()))
        }
    }

    @SuppressLint("MissingPermission")
    private fun lmsCurrent(action: String) {
        emitPerms(action)
        val latch = CountDownLatch(1)
        try {
            val cancel = CancellationSignal()
            lm().getCurrentLocation(
                LocationManager.GPS_PROVIDER,
                LocationRequest.Builder(1000L).build(),
                cancel,
                mainExecutor,
            ) { loc: Location? ->
                if (loc == null) {
                    Diag.emit(this, action, mapOf("result" to "null"))
                } else {
                    Diag.emit(this, action, Diag.loc("lms-current", loc))
                }
                latch.countDown()
            }
            if (!latch.await(45, TimeUnit.SECONDS)) {
                cancel.cancel()
                Diag.emit(this, action, mapOf("result" to "timeout"))
            }
        } catch (t: Throwable) {
            Diag.emit(this, action, mapOf("error" to t.javaClass.simpleName, "msg" to (t.message ?: "")))
        }
    }

    @SuppressLint("MissingPermission")
    private fun lmsLast(action: String) {
        emitPerms(action)
        for (p in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.FUSED_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )) {
            try {
                val loc = lm().getLastKnownLocation(p)
                if (loc == null) {
                    Diag.emit(this, action, mapOf("provider" to p, "result" to "null"))
                } else {
                    Diag.emit(this, action, Diag.loc("lms-last", loc, mapOf("provider" to p)))
                }
            } catch (t: Throwable) {
                Diag.emit(this, action, mapOf("provider" to p, "error" to t.javaClass.simpleName))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun gnssStatus(action: String) {
        emitPerms(action)
        val latch = CountDownLatch(1)
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                Diag.emit(
                    this@MainActivity, action,
                    mapOf("svCount" to status.satelliteCount, "note" to "status-callback-ok"),
                )
                latch.countDown()
            }
        }
        try {
            val ok = lm().registerGnssStatusCallback(mainExecutor, cb)
            Diag.emit(this, action, mapOf("registered" to ok))
            latch.await(30, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            Diag.emit(this, action, mapOf("error" to t.javaClass.simpleName, "msg" to (t.message ?: "")))
        } finally {
            try {
                lm().unregisterGnssStatusCallback(cb)
            } catch (_: Throwable) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun gnssMeas(action: String) {
        emitPerms(action)
        val latch = CountDownLatch(1)
        val cb = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                val ms = event.measurements
                Diag.emit(
                    this@MainActivity, action,
                    mapOf(
                        "measCount" to ms.size,
                        "clockNanos" to event.clock.timeNanos,
                        "fullBias" to event.clock.fullBiasNanos,
                    ),
                )
                latch.countDown()
            }

            override fun onStatusChanged(status: Int) {
                Diag.emit(this@MainActivity, action, mapOf("measStatus" to status))
            }
        }
        try {
            val ok = lm().registerGnssMeasurementsCallback(mainExecutor, cb)
            Diag.emit(this, action, mapOf("registered" to ok))
            latch.await(30, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            Diag.emit(this, action, mapOf("error" to t.javaClass.simpleName, "msg" to (t.message ?: "")))
        } finally {
            try {
                lm().unregisterGnssMeasurementsCallback(cb)
            } catch (_: Throwable) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun nmea(action: String) {
        emitPerms(action)
        val latch = CountDownLatch(1)
        val seen = AtomicInteger(0)
        try {
            val ok: Boolean = if (Build.VERSION.SDK_INT >= 30) {
                lm().addNmeaListener(
                    mainExecutor,
                    android.location.OnNmeaMessageListener { message: String, timestamp: Long ->
                        val type = message.take(6)
                        Diag.emit(
                            this, action,
                            mapOf("nmeaType" to type, "len" to message.length, "ts" to timestamp),
                        )
                        if (seen.incrementAndGet() >= 3) latch.countDown()
                    },
                )
                true
            } else {
                @Suppress("DEPRECATION")
                lm().addNmeaListener(object : android.location.GpsStatus.NmeaListener {
                    override fun onNmeaReceived(timestamp: Long, nmea: String) {
                        Diag.emit(this@MainActivity, action, mapOf("nmeaType" to nmea.take(6), "len" to nmea.length, "ts" to timestamp))
                        if (seen.incrementAndGet() >= 3) latch.countDown()
                    }
                })
                true
            }
            Diag.emit(this, action, mapOf("registered" to ok))
            latch.await(30, TimeUnit.SECONDS)
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
            // Synthetic emulator coordinates only; rounded to 2 decimals (~1 km) in logs.
            "lat" to round(loc.latitude * 100) / 100,
            "lon" to round(loc.longitude * 100) / 100,
            "acc" to loc.accuracy,
            "provider" to loc.provider,
            "elapsedNanos" to loc.elapsedRealtimeNanos,
            "time" to loc.time,
            "hasAlt" to loc.hasAltitude(),
            "hasSpeed" to loc.hasSpeed(),
            "hasBearing" to loc.hasBearing(),
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
