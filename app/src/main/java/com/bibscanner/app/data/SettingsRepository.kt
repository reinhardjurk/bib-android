package com.bibscanner.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Reads / writes [AppSettings] backed by Jetpack DataStore. */
class SettingsRepository(context: Context) {

    private val ds = context.applicationContext.dataStore

    private object Keys {
        val CALLBACK_URL = stringPreferencesKey("callback_url")
        val HTTP_METHOD = stringPreferencesKey("http_method")
        val FIRE_CALLBACK = booleanPreferencesKey("fire_callback")
        val TIME_OFFSET = doublePreferencesKey("time_offset_seconds")
        val MIN_CONSECUTIVE = intPreferencesKey("min_consecutive")
        val PATIENCE = doublePreferencesKey("patience_seconds")
        val MIN_DIGITS = intPreferencesKey("min_digits")
        val MAX_DIGITS = intPreferencesKey("max_digits")
        val RECORD_BACKUP = booleanPreferencesKey("record_backup")
        val FRONT_CAMERA = booleanPreferencesKey("front_camera")
        val VIDEO_SAMPLE_MS = intPreferencesKey("video_sample_ms")
        val REPORT_NO_NUMBER = booleanPreferencesKey("report_no_number")
        val CONFIRM_ON_THRESHOLD = booleanPreferencesKey("confirm_on_threshold")
    }

    val settings: Flow<AppSettings> = ds.data.map { p ->
        val d = AppSettings()
        AppSettings(
            callbackUrl = p[Keys.CALLBACK_URL] ?: d.callbackUrl,
            httpMethod = p[Keys.HTTP_METHOD] ?: d.httpMethod,
            fireCallback = p[Keys.FIRE_CALLBACK] ?: d.fireCallback,
            timeOffsetSeconds = p[Keys.TIME_OFFSET] ?: d.timeOffsetSeconds,
            minConsecutiveDetections = p[Keys.MIN_CONSECUTIVE] ?: d.minConsecutiveDetections,
            patienceSeconds = p[Keys.PATIENCE] ?: d.patienceSeconds,
            minBibDigits = p[Keys.MIN_DIGITS] ?: d.minBibDigits,
            maxBibDigits = p[Keys.MAX_DIGITS] ?: d.maxBibDigits,
            recordBackup = p[Keys.RECORD_BACKUP] ?: d.recordBackup,
            useFrontCamera = p[Keys.FRONT_CAMERA] ?: d.useFrontCamera,
            videoSampleMs = p[Keys.VIDEO_SAMPLE_MS] ?: d.videoSampleMs,
            reportNoNumber = p[Keys.REPORT_NO_NUMBER] ?: d.reportNoNumber,
            confirmOnThreshold = p[Keys.CONFIRM_ON_THRESHOLD] ?: d.confirmOnThreshold,
        )
    }

    suspend fun save(s: AppSettings) {
        ds.edit { p ->
            p[Keys.CALLBACK_URL] = s.callbackUrl
            p[Keys.HTTP_METHOD] = s.httpMethod
            p[Keys.FIRE_CALLBACK] = s.fireCallback
            p[Keys.TIME_OFFSET] = s.timeOffsetSeconds
            p[Keys.MIN_CONSECUTIVE] = s.minConsecutiveDetections
            p[Keys.PATIENCE] = s.patienceSeconds
            p[Keys.MIN_DIGITS] = s.minBibDigits
            p[Keys.MAX_DIGITS] = s.maxBibDigits
            p[Keys.RECORD_BACKUP] = s.recordBackup
            p[Keys.FRONT_CAMERA] = s.useFrontCamera
            p[Keys.VIDEO_SAMPLE_MS] = s.videoSampleMs
            p[Keys.REPORT_NO_NUMBER] = s.reportNoNumber
            p[Keys.CONFIRM_ON_THRESHOLD] = s.confirmOnThreshold
        }
    }
}
