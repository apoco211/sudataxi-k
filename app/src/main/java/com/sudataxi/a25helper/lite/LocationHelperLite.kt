
package com.sudataxi.a25helper.lite

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices

class LocationHelperLite(private val ctx: Context) {
    @SuppressLint("MissingPermission")
    fun getCurrentLocation(cb: (Double, Double)->Unit) {
        try {
            LocationServices.getFusedLocationProviderClient(ctx).lastLocation
                .addOnSuccessListener { loc ->
                    if (loc != null) cb(loc.latitude, loc.longitude)
                    else cb(37.3912, 126.9498)
                }
                .addOnFailureListener { cb(37.3912, 126.9498) }
        } catch (e: Exception) {
            cb(37.3912, 126.9498)
        }
    }
}
