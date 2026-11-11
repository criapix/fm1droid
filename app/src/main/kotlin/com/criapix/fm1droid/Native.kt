package com.criapix.fm1droid

import java.nio.ByteBuffer

/** JNI surface of libfm1droid.so (native/fm1droid/src/jni_api.rs). */
object Native {
  init { System.loadLibrary("fm1droid") }

  /** null on success, the reason otherwise. */
  external fun boot(pkg: ByteArray, saved: ByteArray, syx: ByteArray, mhz: Int, jit: Boolean): String?
  /** "ok:<identity>" or "err:<reason>". */
  external fun probe(pkg: ByteArray): String
  external fun audioStart(): Int
  external fun audioStop()
  external fun audioLost(): Boolean
  /** [rate, burst, buffer, performance mode (12 = low latency), underruns, output latency µs, least ring fill]; null with no stream. Call once a second: it also tunes the buffer. */
  external fun audioInfo(): IntArray?
  external fun speed(): Float
  external fun starved(): Long
  /** bit 0: booted, bit 1: halted */
  external fun status(): Int
  external fun flashRev(): Int
  external fun flashSnapshot(): ByteArray?
  external fun setLead(frames: Int)

  external fun key(id: Int, down: Boolean, vel: Int)
  external fun enc(i: Int, clicks: Int)
  external fun pot(v: Int)
  external fun midi(data: ByteArray, off: Int, len: Int)
  external fun hit(x: Float, y: Float): Int
  external fun keyVel(id: Int, y: Float): Int
  /** Lay the panel out for a logical width (820 tall); returns the width used (1280..1900). */
  external fun setLayout(width: Int): Int
  /** Free spot for the floating menu button, logical panel pixels: [x, y]. */
  external fun menuPos(): FloatArray?
  /** True when the buffer was rewritten, false when nothing changed. */
  external fun draw(buf: ByteBuffer): Boolean
}
