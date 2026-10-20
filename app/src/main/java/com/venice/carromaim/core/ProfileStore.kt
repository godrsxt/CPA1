package com.venice.carromaim.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Saves board profiles (one per board / skin) as JSON in SharedPreferences. */
object ProfileStore {
    private const val FILE = "carrom_aim"
    private const val KEY = "profiles"
    private const val ACTIVE = "active"

    fun all(c: Context): MutableList<BoardProfile> {
        val sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val out = ArrayList<BoardProfile>()
        try {
            val arr = JSONArray(sp.getString(KEY, "[]"))
            for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
        } catch (_: Exception) {
        }
        if (out.isEmpty()) {
            out.add(BoardProfile("Board 1"))
            saveAll(c, out)
        }
        return out
    }

    private fun saveAll(c: Context, list: List<BoardProfile>) {
        val arr = JSONArray()
        for (p in list) arr.put(toJson(p))
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun save(c: Context, p: BoardProfile) {
        val list = all(c)
        val idx = list.indexOfFirst { it.name == p.name }
        if (idx >= 0) list[idx] = p else list.add(p)
        saveAll(c, list)
    }

    fun delete(c: Context, name: String) {
        val list = all(c).filter { it.name != name }
        saveAll(c, list)
        if (activeName(c) == name) setActive(c, list.firstOrNull()?.name ?: "")
    }

    fun activeName(c: Context): String =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(ACTIVE, "") ?: ""

    fun setActive(c: Context, name: String) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(ACTIVE, name).apply()
    }

    fun active(c: Context): BoardProfile {
        val list = all(c)
        val n = activeName(c)
        return list.firstOrNull { it.name == n } ?: list.first().also { setActive(c, it.name) }
    }

    fun uniqueName(c: Context, base: String): String {
        val names = all(c).map { it.name }.toSet()
        if (base !in names) return base
        var i = 2
        while ("$base $i" in names) i++
        return "$base $i"
    }

    private fun toJson(p: BoardProfile): JSONObject {
        val o = JSONObject()
        o.put("name", p.name)
        o.put("corners", JSONArray(p.corners.map { it.toDouble() }))
        o.put("screenW", p.screenW); o.put("screenH", p.screenH)
        o.put("coinR", p.coinR.toDouble()); o.put("strikerR", p.strikerR.toDouble())
        o.put("pocketR", p.pocketR.toDouble()); o.put("pocketInset", p.pocketInset.toDouble())
        o.put("pocketCapture", p.pocketCapture.toDouble())
        o.put("baselineY", p.baselineY.toDouble())
        o.put("slideMin", p.slideMin.toDouble()); o.put("slideMax", p.slideMax.toDouble())
        o.put("colors", JSONArray(p.colors.toList()))
        o.put("tol", p.tol.toDouble())
        o.put("wallRest", p.wallRest.toDouble()); o.put("coinRest", p.coinRest.toDouble())
        o.put("strikerMass", p.strikerMass.toDouble()); o.put("rollLen", p.rollLen.toDouble())
        o.put("power", p.power.toDouble()); o.put("maxDrag", p.maxDrag.toDouble())
        return o
    }

    private fun fromJson(o: JSONObject): BoardProfile {
        val p = BoardProfile(o.optString("name", "Board"))
        val c = o.optJSONArray("corners")
        if (c != null && c.length() == 8) p.corners = FloatArray(8) { c.getDouble(it).toFloat() }
        p.screenW = o.optInt("screenW"); p.screenH = o.optInt("screenH")
        p.coinR = o.optDouble("coinR", p.coinR.toDouble()).toFloat()
        p.strikerR = o.optDouble("strikerR", p.strikerR.toDouble()).toFloat()
        p.pocketR = o.optDouble("pocketR", p.pocketR.toDouble()).toFloat()
        p.pocketInset = o.optDouble("pocketInset", p.pocketInset.toDouble()).toFloat()
        p.pocketCapture = o.optDouble("pocketCapture", p.pocketCapture.toDouble()).toFloat()
        p.baselineY = o.optDouble("baselineY", p.baselineY.toDouble()).toFloat()
        p.slideMin = o.optDouble("slideMin", p.slideMin.toDouble()).toFloat()
        p.slideMax = o.optDouble("slideMax", p.slideMax.toDouble()).toFloat()
        val col = o.optJSONArray("colors")
        if (col != null && col.length() == 5) p.colors = IntArray(5) { col.getInt(it) }
        p.tol = o.optDouble("tol", p.tol.toDouble()).toFloat()
        p.wallRest = o.optDouble("wallRest", p.wallRest.toDouble()).toFloat()
        p.coinRest = o.optDouble("coinRest", p.coinRest.toDouble()).toFloat()
        p.strikerMass = o.optDouble("strikerMass", p.strikerMass.toDouble()).toFloat()
        p.rollLen = o.optDouble("rollLen", p.rollLen.toDouble()).toFloat()
        p.power = o.optDouble("power", p.power.toDouble()).toFloat()
        p.maxDrag = o.optDouble("maxDrag", p.maxDrag.toDouble()).toFloat()
        return p
    }
}
