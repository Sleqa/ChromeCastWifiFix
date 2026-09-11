package com.sleqa.wififix

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ring buffer of what the watchdog saw and did. This is the app's real
 * deliverable: it is the only way to learn how often the bug actually fires and
 * whether the repair works.
 *
 * Stored as one JSON array in SharedPreferences -- the platform already makes
 * those writes atomic and crash-safe, which matters for a service that can be
 * killed mid-write, and org.json costs no dependency.
 */
object EventLog {
    const val TAG = "WifiFix"
    private const val CAP = 500

    data class Entry(val at: Long, val kind: String, val tier: String?, val detail: String)

    data class Stats(
        val outages: Int,
        val repairs: Int,
        val giveUps: Int,
        val lastOutageAt: Long,
    )

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("eventlog", Context.MODE_PRIVATE)

    @Synchronized
    fun append(ctx: Context, kind: String, tier: String?, detail: String) {
        Log.i(TAG, "$kind${tier?.let { " [$it]" } ?: ""}: $detail")
        val p = prefs(ctx)
        val arr = read(p)
        arr.put(
            JSONObject()
                .put("at", System.currentTimeMillis())
                .put("kind", kind)
                .put("tier", tier ?: JSONObject.NULL)
                .put("detail", detail)
        )
        // Drop from the head once full.
        val trimmed = if (arr.length() > CAP) {
            JSONArray().also { out -> for (i in arr.length() - CAP until arr.length()) out.put(arr.get(i)) }
        } else {
            arr
        }

        val edit = p.edit().putString(KEY_ENTRIES, trimmed.toString())
        // Durable aggregates, so statistics survive ring-buffer eviction.
        when (kind) {
            com.sleqa.wififix.core.Kind.WIFI_OFF -> {
                edit.putInt(KEY_OUTAGES, p.getInt(KEY_OUTAGES, 0) + 1)
                edit.putLong(KEY_LAST_OUTAGE, System.currentTimeMillis())
            }
            com.sleqa.wififix.core.Kind.CONFIRMED ->
                edit.putInt(KEY_REPAIRS, p.getInt(KEY_REPAIRS, 0) + 1)
            com.sleqa.wififix.core.Kind.GAVE_UP ->
                edit.putInt(KEY_GAVEUPS, p.getInt(KEY_GAVEUPS, 0) + 1)
        }
        edit.apply()
    }

    @Synchronized
    fun recent(ctx: Context, n: Int = 25): List<Entry> {
        val arr = read(prefs(ctx))
        val out = ArrayList<Entry>(n)
        for (i in (arr.length() - 1) downTo maxOf(0, arr.length() - n)) {
            val o = arr.optJSONObject(i) ?: continue
            out += Entry(
                at = o.optLong("at"),
                kind = o.optString("kind"),
                tier = o.optString("tier").takeIf { it.isNotEmpty() && it != "null" },
                detail = o.optString("detail"),
            )
        }
        return out
    }

    fun stats(ctx: Context): Stats = prefs(ctx).let {
        Stats(
            outages = it.getInt(KEY_OUTAGES, 0),
            repairs = it.getInt(KEY_REPAIRS, 0),
            giveUps = it.getInt(KEY_GAVEUPS, 0),
            lastOutageAt = it.getLong(KEY_LAST_OUTAGE, 0L),
        )
    }

    @Synchronized
    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    private fun read(p: android.content.SharedPreferences): JSONArray =
        runCatching { JSONArray(p.getString(KEY_ENTRIES, "[]")) }.getOrElse { JSONArray() }

    private const val KEY_ENTRIES = "entries"
    private const val KEY_OUTAGES = "count_outages"
    private const val KEY_REPAIRS = "count_repairs"
    private const val KEY_GAVEUPS = "count_gaveups"
    private const val KEY_LAST_OUTAGE = "last_outage_at"
}
