package com.criapix.fm1droid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.criapix.fm1droid.BuildInfo
import com.criapix.fm1droid.Emu
import com.criapix.fm1droid.data.Prefs

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(emu: Emu, onDismiss: () -> Unit) {
  val state by emu.state.collectAsState()
  val context = LocalContext.current
  var confirmErase by remember { mutableStateOf(false) }
  val syxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    val fw = state.current
    if (uri != null && fw != null) {
      val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
      if (bytes != null) emu.boot(fw, syx = bytes)
    }
  }
  val license = remember { runCatching { context.assets.open("licenses/fm1-emu-LICENSE.txt").bufferedReader().readText() }.getOrDefault("") }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Configurações", style = MaterialTheme.typography.titleLarge)

      Text("Clock da CPU emulada", style = MaterialTheme.typography.titleMedium)
      Text("240 MHz é o clock real do FM-1. No modo Automático o app reduz o clock sozinho se o som falhar (atual: ${state.mhz} MHz).", style = MaterialTheme.typography.bodySmall)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.autoClock, onClick = { emu.setAutoClock() }, label = { Text("Automático") })
        Prefs.CLOCKS.forEach { mhz ->
          FilterChip(selected = !state.autoClock && state.mhz == mhz, onClick = { emu.setClock(mhz) }, label = { Text("$mhz MHz") })
        }
      }

      Text("Latência", style = MaterialTheme.typography.titleMedium)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Baixa" to 384, "Normal" to 768, "Segura" to 1536).forEach { (label, frames) ->
          FilterChip(selected = state.lead == frames, onClick = { emu.setLead(frames) }, label = { Text(label) })
        }
      }

      val fw = state.current
      if (fw != null && fw.source == "catalog:mvave") {
        Text("Banco DX7", style = MaterialTheme.typography.titleMedium)
        Text("Carrega um banco de 32 timbres (.syx) nos slots 1–32 do firmware original.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { syxPicker.launch(arrayOf("*/*")) }) { Text("Carregar banco DX7 (.syx)") }
      }
      state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

      Text("Firmware atual", style = MaterialTheme.typography.titleMedium)
      Text(fw?.name ?: "nenhum")
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = fw != null, onClick = { emu.restart() }) { Text("Reiniciar firmware") }
        OutlinedButton(enabled = fw != null, onClick = { confirmErase = true }) { Text("Apagar save") }
      }

      Text("Desempenho", style = MaterialTheme.typography.titleMedium)
      Text("Velocidade: ${"%.2f".format(state.speed)}× tempo real · falhas de áudio: ${state.starvedPerSec}/s" +
        if (state.halted) " · CPU parada" else "")
      state.suggestion?.let { lower ->
        Text("Seu aparelho não acompanha este firmware a ${state.mhz} MHz. Reduzir para $lower MHz?")
        Row2(
          { emu.setClock(lower); emu.dismissSuggestion() }, "Reduzir para $lower MHz",
          { emu.dismissSuggestion() }, "Agora não",
        )
      }

      HorizontalDivider()
      Text("Sobre", style = MaterialTheme.typography.titleMedium)
      Text("FM1 Droid ${BuildInfo.VERSION}")
      Text("Emulação: fm1-emu, © Joakim Eriksson e colaboradores, licença MIT.", style = MaterialTheme.typography.bodySmall)
      Text(license, style = MaterialTheme.typography.bodySmall)
      Text("M-VAVE e FM-1 são marcas de seus titulares. Este app não é afiliado à M-VAVE.", style = MaterialTheme.typography.bodySmall)
      Text("")
    }
  }

  if (confirmErase) {
    AlertDialog(
      onDismissRequest = { confirmErase = false },
      title = { Text("Apagar save?") },
      text = { Text("Os presets e ajustes gravados neste firmware serão perdidos.") },
      confirmButton = { TextButton(onClick = { confirmErase = false; state.current?.let(emu::eraseSave) }) { Text("Apagar") } },
      dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Cancelar") } },
    )
  }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Row2(onA: () -> Unit, a: String, onB: () -> Unit, b: String) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = onA) { Text(a) }
    OutlinedButton(onClick = onB) { Text(b) }
  }
}
