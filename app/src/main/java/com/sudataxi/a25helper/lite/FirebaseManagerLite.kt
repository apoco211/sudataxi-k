
package com.sudataxi.a25helper.lite

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object FirebaseManagerLite {
    fun send(baseUrl: String, payload: Map<String, Any>, cb: (Boolean)->Unit) {
        Thread {
            try {
                val url = URL("$baseUrl/sudataxi/demand_hotspots.json")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                OutputStreamWriter(conn.outputStream).use { it.write(JSONObject(payload).toString()) }
                cb(conn.responseCode in 200..299)
            } catch (e: Exception) {
                e.printStackTrace()
                cb(false)
            }
        }.start()
    }
}
