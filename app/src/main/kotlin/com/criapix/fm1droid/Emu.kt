package com.criapix.fm1droid

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.criapix.fm1droid.data.Catalog
import com.criapix.fm1droid.data.CatalogEntry
import com.criapix.fm1droid.data.FirmwareStore
import com.criapix.fm1droid.data.Installed
import com.criapix.fm1droid.data.Prefs
import com.criapix.fm1droid.data.SaveStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class EmuState(
  val current: Installed? = null,
  val installed: List<Installed> = emptyList(),
  val booting: Boolean = false,
  val error: String? = null,
  val speed: Float = 0f,
  val starvedPerSec: Long = 0,
  val halted: Boolean = false,
  val mhz: Int = Prefs.DEFAULT_MHZ,
  val lead: Int = 768,
  /** a lower clock worth offering after sustained audio dropouts */
  val suggestion: Int? = null,
  val needsLibrary: Boolean = false,
  val autoClock: Boolean = true,
  /** set when the app lowered the clock by itself, for a one-line notice */
  val autoNotice: String? = null,
  val autoLead: Boolean = true,
  val audio: AudioInfo? = null,
)

class Emu(app: Application) : AndroidViewModel(app) {
  val store = FirmwareStore(File(app.filesDir, "firmwares"))
  val saves = SaveStore(File(app.filesDir, "saves"))
  val prefs = Prefs(app)

  private val _state = MutableStateFlow(EmuState(
    mhz = prefs.mhz, lead = if (prefs.lead > 0) prefs.lead else 768, autoLead = prefs.lead == 0,
    installed = store.list(), autoClock = prefs.autoClock,
  ))
  val state: StateFlow<EmuState> = _state

  private val bootLock = Mutex()
  private var lastRev = 0
  private var monitor: Job? = null
  private var suggestionDismissed = false
  /** the automatic lead in effect; recomputed when the audio stream opens */
  @Volatile private var autoLead = 768
  /** monitor ticks to ignore: the ring is empty while booting, that is not a dropout */
  @Volatile private var settle = 0

  private fun effectiveLead() = if (prefs.lead > 0) prefs.lead else autoLead

  /** A new stream (the first, or after the output device changed): start the automatic lead over. */
  private fun resetAutoLead() {
    val a = AudioInfo.of(Native.audioInfo())
    autoLead = if (a != null) LeadPolicy.initial(a.burst, a.rate) else 768
    settle = 2
    Native.setLead(effectiveLead())
    _state.update { it.copy(lead = effectiveLead(), autoLead = prefs.lead == 0, audio = a) }
  }

  init {
    val last = store.lastUsed?.let { id -> store.list().firstOrNull { it.id == id } }
    if (last != null) boot(last) else _state.update { it.copy(needsLibrary = true) }
  }

  fun refreshInstalled() = _state.update { it.copy(installed = store.list()) }
  fun clearNotice() = _state.update { it.copy(autoNotice = null) }
  fun clearError() = _state.update { it.copy(error = null) }
  fun reportError(message: String) = _state.update { it.copy(error = message) }

  /** Boot [fw]; the running firmware's pending save is written first (unless [flush] is false). */
  fun boot(fw: Installed, syx: ByteArray = ByteArray(0), flush: Boolean = true) {
    viewModelScope.launch(Dispatchers.IO) {
      bootLock.withLock {
        _state.update { it.copy(booting = true, error = null) }
        if (flush) flushSave()
        val bytes = store.bytes(fw.id)
        val err = if (bytes == null) "firmware file missing" else
          Native.boot(bytes, saves.load(fw.id), syx, prefs.mhz, prefs.jit)
        if (err == null) {
          store.lastUsed = fw.id
          Native.setLead(effectiveLead()); settle = 2
          Native.pot(prefs.pot)
          lastRev = Native.flashRev()
          suggestionDismissed = false
          _state.update { it.copy(current = fw, booting = false, needsLibrary = false, mhz = prefs.mhz, lead = effectiveLead()) }
        } else {
          _state.update { it.copy(booting = false, error = err) }
        }
      }
    }
  }

  /** Validate and install a package; returns the problem, or null (then it is booted). */
  suspend fun install(bytes: ByteArray, name: String, version: String = "", author: String = "", source: String = "file"): String? =
    withContext(Dispatchers.IO) {
      val probe = Native.probe(bytes)
      if (!probe.startsWith("ok:")) return@withContext probe.removePrefix("err:")
      val fw = store.install(bytes, name, version, author, source)
      refreshInstalled()
      boot(fw)
      null
    }

  suspend fun installFromCatalog(e: CatalogEntry): String? =
    try {
      val bytes = Catalog.download(e.url ?: return "no download address")
      install(bytes, e.name, e.version, e.author, "catalog:${e.id}")
    } catch (ex: Exception) {
      ex.message ?: "download failed"
    }

  suspend fun importUri(uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
      val cr = getApplication<Application>().contentResolver
      val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext "could not read the file"
      var name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "firmware"
      cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) name = it.getString(0) ?: name
      }
      install(bytes, name.removeSuffix(".fwsc"))
    } catch (ex: Exception) {
      ex.message ?: "import failed"
    }
  }

  fun remove(fw: Installed) {
    viewModelScope.launch(Dispatchers.IO) {
      store.remove(fw.id); saves.clear(fw.id); refreshInstalled()
      if (_state.value.current?.id == fw.id) _state.update { it.copy(current = null, needsLibrary = true) }
    }
  }

  fun eraseSave(fw: Installed) {
    viewModelScope.launch(Dispatchers.IO) {
      saves.clear(fw.id)
      if (_state.value.current?.id == fw.id) boot(fw, flush = false)
    }
  }

  fun restart() { _state.value.current?.let { boot(it) } }

  /** A clock picked by the user: the automatic step-down stops second-guessing it. */
  fun setClock(mhz: Int) { prefs.autoClock = false; prefs.mhz = mhz; restart() }

  fun setAutoClock() { prefs.autoClock = true; _state.update { it.copy(autoClock = true) } }

  /** [frames] 0 = automatic. */
  fun setLead(frames: Int) {
    prefs.lead = frames
    Native.setLead(effectiveLead())
    _state.update { it.copy(lead = effectiveLead(), autoLead = frames == 0) }
  }

  fun dismissSuggestion() { suggestionDismissed = true; _state.update { it.copy(suggestion = null) } }

  fun savePot(v: Int) { prefs.pot = v }

  private fun flushSave() {
    val cur = _state.value.current ?: return
    val rev = Native.flashRev()
    if (rev != lastRev) {
      Native.flashSnapshot()?.let { saves.write(cur.id, it) }
      lastRev = rev
    }
  }

  fun onStart() {
    Native.audioStart()
    resetAutoLead()
    monitor?.cancel()
    monitor = viewModelScope.launch(Dispatchers.IO) {
      var lastStarved = Native.starved()
      var bad = 0
      while (isActive) {
        delay(1000)
        if (Native.audioLost()) { Native.audioStop(); Native.audioStart(); resetAutoLead() }
        val s = Native.starved()
        val per = s - lastStarved
        lastStarved = s
        val info = AudioInfo.of(Native.audioInfo()) // also the AAudio buffer's tuning step
        val live = _state.value.current != null && !_state.value.booting
        when {
          settle > 0 -> { settle--; bad = 0 }
          per > 0 && live && prefs.lead == 0 && autoLead < LeadPolicy.MAX -> {
            // dropouts with room to grow: more reserve first, the clock is the last resort
            autoLead = LeadPolicy.raised(autoLead)
            Native.setLead(autoLead)
            bad = 0
          }
          else -> bad = if (per > 0) bad + 1 else 0
        }
        val st = _state.value
        val lower = Prefs.CLOCKS.lastOrNull { it < st.mhz }
        if (bad >= 5 && prefs.autoClock && lower != null && st.current != null && !st.booting) {
          // the device cannot keep up at this clock: step down and watch again
          bad = 0
          prefs.mhz = lower
          _state.update { it.copy(autoNotice = "Aparelho sem fôlego a ${st.mhz} MHz: clock reduzido para $lower MHz") }
          viewModelScope.launch { delay(6000); clearNotice() }
          restart()
          continue
        }
        _state.update {
          it.copy(
            speed = Native.speed(), starvedPerSec = per, halted = Native.status() and 2 != 0,
            suggestion = if (bad >= 5 && !suggestionDismissed && !prefs.autoClock) lower else it.suggestion,
            autoClock = prefs.autoClock, mhz = prefs.mhz,
            lead = effectiveLead(), autoLead = prefs.lead == 0, audio = info,
          )
        }
        bootLock.withLock { flushSave() }
      }
    }
  }

  fun onStop() {
    monitor?.cancel()
    runCatching { flushSave() }
    Native.audioStop()
  }
}
