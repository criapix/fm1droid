package com.criapix.fm1droid.panel

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.criapix.fm1droid.data.Prefs

/**
 * Vibration feedback for the panel. Uses the haptic primitives (API 30+, crisp on the phone's linear
 * actuator, strength scaled per touch), falling back to the predefined effects and then to a
 * short one-shot pulse on old versions.
 */
class Haptics(context: Context, private val prefs: Prefs) : HapticSink {
  private val vibrator: Vibrator? =
    if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

  private val primitives = Build.VERSION.SDK_INT >= 30 && vibrator?.areAllPrimitivesSupported(
    VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK,
  ) == true

  private var last = 0L

  /** Leve / Normal / Forte */
  private fun gain() = when (prefs.hapticLevel) { 0 -> 0.5f; 1 -> 0.8f; else -> 1f }

  override fun key(button: Boolean, vel: Int) {
    val strength = if (button) 0.9f else 0.35f + 0.65f * vel.coerceIn(1, 127) / 127f
    play(click = true, scale = strength * gain())
  }

  override fun step() = play(click = false, scale = gain())

  private fun play(click: Boolean, scale: Float) {
    if (!prefs.haptics) return
    val v = vibrator ?: return
    if (!v.hasVibrator()) return
    val now = SystemClock.uptimeMillis()
    if (now - last < 8) return // faster than the motor can render distinct pulses
    last = now
    val effect = when {
      primitives -> VibrationEffect.startComposition()
        .addPrimitive(if (click) VibrationEffect.Composition.PRIMITIVE_CLICK else VibrationEffect.Composition.PRIMITIVE_TICK, scale.coerceIn(0f, 1f))
        .compose()
      Build.VERSION.SDK_INT >= 29 -> VibrationEffect.createPredefined(if (click) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK)
      else -> VibrationEffect.createOneShot(if (click) 15L else 8L, if (click) 180 else 100)
    }
    v.vibrate(effect)
  }
}
