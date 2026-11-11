package com.criapix.fm1droid

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.criapix.fm1droid.midi.MidiInput
import com.criapix.fm1droid.panel.PanelScreen
import com.criapix.fm1droid.ui.LibrarySheet
import com.criapix.fm1droid.ui.SettingsSheet
import kotlinx.coroutines.launch

object BuildInfo { const val VERSION = "0.1.0" }

class MainActivity : ComponentActivity() {
  private val emu: Emu by viewModels()
  private lateinit var midi: MidiInput

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if (Build.VERSION.SDK_INT >= 28) {
      window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    midi = MidiInput(this)
    lifecycle.addObserver(object : DefaultLifecycleObserver {
      override fun onStart(owner: LifecycleOwner) { emu.onStart(); midi.start() }
      override fun onStop(owner: LifecycleOwner) { midi.stop(); emu.onStop() }
    })
    setContent { Root(emu, midi) }
    handleIntent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIntent(intent)
  }

  /** "Open with" on a .fwsc: validate, install and boot it. */
  private fun handleIntent(i: Intent?) {
    val uri = i?.data ?: return
    if (i.action != Intent.ACTION_VIEW) return
    lifecycleScope.launch { emu.importUri(uri)?.let { err -> emu.reportError(err) } }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
  }
}

@Composable
private fun Root(emu: Emu, midi: MidiInput) {
  MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
    val state by emu.state.collectAsState()
    val devices by midi.count.collectAsState()
    var library by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(state.needsLibrary) { if (state.needsLibrary) library = true }

    var anchor by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(Modifier.fillMaxSize().background(Color(0xFF18191B))) {
      // the panel keeps clear of the camera cut-out; the leftover strip is the panel's own backdrop
      Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
        PanelScreen(initialPot = emu.prefs.pot.toFloat(), onPot = emu::savePot, onAnchor = { x, y -> anchor = androidx.compose.ui.geometry.Offset(x, y) })

        // floating menu: in a free spot of the layout, never over a control
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((anchor.x - with(density) { 20.dp.toPx() }).toInt(), (anchor.y - with(density) { 20.dp.toPx() }).toInt()) }) {
          Box(
            Modifier.size(40.dp).clip(CircleShape).background(Color(0x66000000)).clickable { menu = true },
            contentAlignment = Alignment.Center,
          ) { Text("⋮", color = Color.White) }
          DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Firmwares") }, onClick = { menu = false; library = true })
            DropdownMenuItem(text = { Text("Configurações") }, onClick = { menu = false; settings = true })
            DropdownMenuItem(
              enabled = false,
              text = { Text("${"%.2f".format(state.speed)}× · ${state.starvedPerSec} falhas/s · MIDI: $devices") },
              onClick = {},
            )
          }
        }
      }
      if (state.booting) Text("Iniciando…", color = Color.White, modifier = Modifier.align(Alignment.Center))
      state.autoNotice?.let {
        Text(
          it, color = Color(0xFFFFE082),
          modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).clip(CircleShape)
            .background(Color(0xCC000000)).clickable { emu.clearNotice() }.padding(horizontal = 16.dp, vertical = 8.dp),
        )
      }
      state.error?.let {
        Text(it, color = Color(0xFFFF8A80), modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).clickable { emu.clearError() })
      }
    }
    if (library) LibrarySheet(emu) { library = false }
    if (settings) SettingsSheet(emu) { settings = false }
  }
}
