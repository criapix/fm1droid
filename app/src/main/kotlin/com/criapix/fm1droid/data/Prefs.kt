package com.criapix.fm1droid.data

import android.content.Context

class Prefs(context: Context) {
  private val p = context.getSharedPreferences("fm1droid", Context.MODE_PRIVATE)

  var mhz: Int
    get() = p.getInt("mhz", DEFAULT_MHZ)
    set(v) = p.edit().putInt("mhz", v).apply()

  /** the app lowers the clock by itself while the audio keeps dropping; a manual choice turns it off */
  var autoClock: Boolean
    get() = p.getBoolean("autoClock", true)
    set(v) = p.edit().putBoolean("autoClock", v).apply()

  /** frames of audio the engine keeps ahead */
  var lead: Int
    get() = p.getInt("lead", 768)
    set(v) = p.edit().putInt("lead", v).apply()

  /** native-code (JIT) CPU emulation; off only as a fallback if the device refuses executable memory */
  var jit: Boolean
    get() = p.getBoolean("jit", true)
    set(v) = p.edit().putBoolean("jit", v).apply()

  var pot: Int
    get() = p.getInt("pot", 900)
    set(v) = p.edit().putInt("pot", v).apply()

  companion object {
    const val DEFAULT_MHZ = 240
    val CLOCKS = listOf(60, 80, 100, 120, 160, 200, 240)
  }
}
