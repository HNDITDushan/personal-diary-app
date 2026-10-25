package com.personal.mydiary.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

object LocationHelper {
    data class Found(val latitude: Double, val longitude: Double, val name: String, val address: String)

    /** Caller must hold a location permission. Returns null when no fix is available. */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Found? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val token = CancellationTokenSource()
        val location: Location = suspendCancellableCoroutine { cont ->
            client.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, token.token)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
            cont.invokeOnCancellation { token.cancel() }
        } ?: return null
        val (name, address) = reverseGeocode(context, location.latitude, location.longitude)
        return Found(location.latitude, location.longitude, name, address)
    }

    @Suppress("DEPRECATION")
    private suspend fun reverseGeocode(context: Context, lat: Double, lng: Double): Pair<String, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val a = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)?.firstOrNull()
                    ?: return@runCatching "" to ""
                val name = a.featureName?.takeUnless { it.matches(Regex("""[\d\s/-]+""")) }
                    ?: a.subLocality ?: a.locality ?: ""
                name to (a.getAddressLine(0) ?: "")
            }.getOrDefault("" to "")
        }

    fun openInMaps(context: Context, name: String, lat: Double?, lng: Double?) {
        val uri = if (lat != null && lng != null) {
            Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(name)})")
        } else {
            Uri.parse("geo:0,0?q=${Uri.encode(name)}")
        }
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
