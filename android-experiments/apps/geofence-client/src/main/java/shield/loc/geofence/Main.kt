package shield.loc.geofence

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * E-GEO-02. Actions:
 *  - fw-fence --es lat .. --es lon .. --es radiusM .. : framework proximity alert
 *  - gms-fence (same extras)                          : GMS geofence (GMS REQUIRED)
 *  - gms-remove                                       : remove GMS geofences
 * Fence events arrive at FenceReceiver (both paths use the same PI action).
 * Synthetic coordinates only (emulator geo fix).
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra("action") ?: "fw-fence"
        Thread {
            try {
                when (action) {
                    "fw-fence" -> fwFence()
                    "gms-fence" -> gmsFence()
                    "gms-remove" -> gmsRemove()
                    else -> Diag.emit(this, action, mapOf("error" to "unknown-action"))
                }
            } catch (t: Throwable) {
                Diag.emit(this, action, mapOf("fatal" to t.javaClass.simpleName, "msg" to (t.message ?: "")))
            } finally {
                Diag.emit(this, action, mapOf("done" to true))
                runOnUiThread { finish() }
            }
        }.start()
    }

    private fun fenceIntent(): PendingIntent {
        val i = Intent("shield.loc.geofence.FENCE_EVENT").setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(this, 7, i, flags)
    }

    @SuppressLint("MissingPermission")
    private fun fwFence() {
        val lat = intent.getDoubleExtra("lat", 37.4219983)
        val lon = intent.getDoubleExtra("lon", -122.084)
        val r = intent.getFloatExtra("radiusM", 200f)
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            @Suppress("DEPRECATION")
            lm.addProximityAlert(lat, lon, r, -1L, fenceIntent())
            Diag.emit(this, "fw-fence", mapOf("armed" to true, "radiusM" to r))
            Thread.sleep(75_000L)
        } catch (t: Throwable) {
            Diag.emit(this, "fw-fence", mapOf("error" to t.javaClass.simpleName, "msg" to (t.message ?: "")))
        }
    }

    @SuppressLint("MissingPermission")
    private fun gmsFence() {
        val lat = intent.getDoubleExtra("lat", 37.4219983)
        val lon = intent.getDoubleExtra("lon", -122.084)
        val r = intent.getFloatExtra("radiusM", 200f)
        val client: GeofencingClient = LocationServices.getGeofencingClient(this)
        try {
            val fence = Geofence.Builder()
                .setRequestId("ls-test-fence")
                .setCircularRegion(lat, lon, r)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
            val req = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(fence)
                .build()
            val latch = CountDownLatch(1)
            client.addGeofences(req, fenceIntent()).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Diag.emit(this, "gms-fence", mapOf("armed" to true, "radiusM" to r))
                } else {
                    val e = task.exception
                    Diag.emit(
                        this, "gms-fence",
                        mapOf("armed" to false, "err" to (e?.javaClass?.simpleName ?: "unknown")),
                    )
                }
                latch.countDown()
            }
            latch.await(30, TimeUnit.SECONDS)
            Thread.sleep(60_000L)
        } catch (t: Throwable) {
            Diag.emit(this, "gms-fence", mapOf("error" to t.javaClass.simpleName, "msg" to (t.message ?: "")))
        }
    }

    private fun gmsRemove() {
        val client: GeofencingClient = LocationServices.getGeofencingClient(this)
        try {
            com.google.android.gms.tasks.Tasks.await(client.removeGeofences(listOf("ls-test-fence")), 30, TimeUnit.SECONDS)
            Diag.emit(this, "gms-remove", mapOf("removed" to true))
        } catch (t: Throwable) {
            Diag.emit(this, "gms-remove", mapOf("error" to t.javaClass.simpleName))
        }
    }
}

class FenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Framework proximity alert?
        if (intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) {
            val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
            Diag.emit(
                context, "fence-event",
                mapOf(
                    "kind" to "framework-proximity",
                    "entering" to entering,
                    "hasLocationExtra" to intent.hasExtra("com.google.android.location.LOCATION"),
                ),
            )
            return
        }
        // GMS geofence event?
        try {
            val event = GeofencingEvent.fromIntent(intent)
            if (event == null) {
                Diag.emit(context, "fence-event", mapOf("kind" to "unknown", "hasError" to "null-event"))
                return
            }
            if (event.hasError()) {
                val code = event.errorCode
                Diag.emit(
                    context, "fence-event",
                    mapOf("kind" to "gms", "hasError" to true, "code" to code,
                        "codeName" to GeofenceStatusCodes.getStatusCodeString(code)),
                )
                return
            }
            val trig = event.triggeringLocation
            Diag.emit(
                context, "fence-event",
                mapOf(
                    "kind" to "gms",
                    "transition" to event.geofenceTransition,
                    "ids" to event.triggeringGeofences?.map { it.requestId }.toString(),
                    "hasTriggeringLocation" to (trig != null),
                    "triggeringAcc" to (trig?.accuracy?.toString() ?: "n/a"),
                ),
            )
        } catch (t: Throwable) {
            Diag.emit(context, "fence-event", mapOf("kind" to "parse-error", "err" to t.javaClass.simpleName))
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
