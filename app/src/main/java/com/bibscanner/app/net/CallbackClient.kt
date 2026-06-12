package com.bibscanner.app.net

import android.util.Log
import com.bibscanner.app.data.AppSettings
import com.bibscanner.app.util.ImageUtils
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fires the configurable webhook for each confirmed bib. Fire-and-forget
 * (async enqueue) so network latency never stalls recognition — same intent as
 * the threaded callback in the Python script.
 */
class CallbackClient {

    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun fire(settings: AppSettings, bib: String, elapsedSeconds: Double) {
        if (!settings.fireCallback) return

        val timeHms = ImageUtils.formatHms(elapsedSeconds)
        // Locale.US so decimals always use '.' (a German locale would emit ',').
        val time2 = String.format(Locale.US, "%.2f", elapsedSeconds)
        val url = settings.callbackUrl
            .replace("{bib}", bib)
            .replace("{time}", time2)
            .replace("{time_hms}", timeHms)
            .replace("{raw_seconds}", elapsedSeconds.toString())

        val request = try {
            val builder = Request.Builder().url(url)
            if (settings.httpMethod.equals("POST", ignoreCase = true)) {
                val body = """{"bib":"$bib","time":$time2,"time_hms":"$timeHms"}"""
                    .toRequestBody(jsonMedia)
                builder.post(body)
            } else {
                builder.get()
            }
            builder.build()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Bad callback URL: $url", e)
            return
        }

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "callback FAILED -> $url (${e.message})")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { Log.i(TAG, "callback -> $url [${it.code}]") }
            }
        })
    }

    companion object {
        private const val TAG = "CallbackClient"
    }
}
