package com.autoclicker.floating

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class ClickPoint(val x: Float, val y: Float)

/** One recorded tap in a macro sequence. */
data class RecordedTap(
    val x: Float,
    val y: Float,
    val holdMs: Long,
    /** Delay AFTER this tap before the next one (ms). */
    val gapAfterMs: Long
)

object Prefs {
    private const val NAME = "auto_clicker_prefs"
    private const val KEY_HOLD = "hold_ms"
    private const val KEY_CPS = "cps"
    private const val KEY_POINTS = "click_points"
    private const val KEY_RECORDING = "recording_seq"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getHold(ctx: Context): Int = prefs(ctx).getInt(KEY_HOLD, 10)
    fun setHold(ctx: Context, v: Int) = prefs(ctx).edit().putInt(KEY_HOLD, v).apply()

    fun getCps(ctx: Context): Int = prefs(ctx).getInt(KEY_CPS, 50)
    fun setCps(ctx: Context, v: Int) = prefs(ctx).edit().putInt(KEY_CPS, v).apply()

    fun getPoints(ctx: Context): MutableList<ClickPoint> {
        val json = prefs(ctx).getString(KEY_POINTS, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                ClickPoint(o.getDouble("x").toFloat(), o.getDouble("y").toFloat())
            }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun setPoints(ctx: Context, points: List<ClickPoint>) {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONObject().apply {
                put("x", p.x.toDouble())
                put("y", p.y.toDouble())
            })
        }
        prefs(ctx).edit().putString(KEY_POINTS, arr.toString()).apply()
    }

    fun addPoint(ctx: Context, x: Float, y: Float) {
        val list = getPoints(ctx)
        list.add(ClickPoint(x, y))
        setPoints(ctx, list)
    }

    fun removeLastPoint(ctx: Context): Boolean {
        val list = getPoints(ctx)
        if (list.isEmpty()) return false
        list.removeAt(list.lastIndex)
        setPoints(ctx, list)
        return true
    }

    fun getRecording(ctx: Context): MutableList<RecordedTap> {
        val json = prefs(ctx).getString(KEY_RECORDING, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                RecordedTap(
                    o.getDouble("x").toFloat(),
                    o.getDouble("y").toFloat(),
                    o.getLong("hold"),
                    o.getLong("gap")
                )
            }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun setRecording(ctx: Context, seq: List<RecordedTap>) {
        val arr = JSONArray()
        seq.forEach { t ->
            arr.put(JSONObject().apply {
                put("x", t.x.toDouble())
                put("y", t.y.toDouble())
                put("hold", t.holdMs)
                put("gap", t.gapAfterMs)
            })
        }
        prefs(ctx).edit().putString(KEY_RECORDING, arr.toString()).apply()
    }
}
