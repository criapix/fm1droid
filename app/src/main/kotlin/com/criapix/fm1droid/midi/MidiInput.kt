package com.criapix.fm1droid.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Every MIDI device that sends (USB and paired BLE keyboards) feeds [sink] (the MidiRouter). */
class MidiInput(context: Context, private val sink: (ByteArray, Int, Int) -> Unit) {
  private val manager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
  private val handler = Handler(Looper.getMainLooper())
  private val open = HashMap<Int, Pair<MidiDevice, List<MidiOutputPort>>>()
  private val _count = MutableStateFlow(0)
  val count: StateFlow<Int> = _count

  private val callback = object : MidiManager.DeviceCallback() {
    override fun onDeviceAdded(device: MidiDeviceInfo) = connect(device)
    override fun onDeviceRemoved(device: MidiDeviceInfo) = disconnect(device.id)
  }

  private val receiver = object : MidiReceiver() {
    override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) = sink(msg, offset, count)
  }

  fun start() {
    val m = manager ?: return
    m.registerDeviceCallback(callback, handler)
    m.devices.forEach(::connect)
  }

  fun stop() {
    manager?.unregisterDeviceCallback(callback)
    open.keys.toList().forEach(::disconnect)
  }

  private fun connect(info: MidiDeviceInfo) {
    if (info.outputPortCount == 0 || open.containsKey(info.id)) return
    manager?.openDevice(info, { dev ->
      if (dev == null) return@openDevice
      val ports = (0 until info.outputPortCount).mapNotNull { dev.openOutputPort(it) }
      ports.forEach { it.connect(receiver) }
      open[info.id] = dev to ports
      _count.value = open.size
    }, handler)
  }

  private fun disconnect(id: Int) {
    open.remove(id)?.let { (dev, ports) ->
      ports.forEach { runCatching { it.disconnect(receiver); it.close() } }
      runCatching { dev.close() }
    }
    _count.value = open.size
  }
}
