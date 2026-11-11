package com.criapix.fm1droid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.criapix.fm1droid.Emu
import com.criapix.fm1droid.data.Catalog
import com.criapix.fm1droid.data.CatalogEntry
import com.criapix.fm1droid.data.Installed
import kotlinx.coroutines.launch

const val STOCK_NOTE =
  "Firmware original da M-VAVE, baixado do servidor da M-VAVE. Começa com um banco de timbres do " +
    "emulador; carregue um banco DX7 (.syx) nas configurações."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySheet(emu: Emu, onDismiss: () -> Unit) {
  val state by emu.state.collectAsState()
  val scope = rememberCoroutineScope()
  var tab by remember { mutableStateOf(if (state.installed.isEmpty()) 1 else 0) }
  var message by remember { mutableStateOf<String?>(null) }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) scope.launch {
      message = emu.importUri(uri)
      if (message == null) onDismiss()
    }
  }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
      TabRow(selectedTabIndex = tab) {
        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Instalados") })
        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Catálogo") })
      }
      Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Importar arquivo .fwsc") }
      }
      message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
      if (tab == 0) Installed(emu, onDismiss) else CatalogTab(emu, onDismiss) { message = it }
    }
  }
}

@Composable
private fun Installed(emu: Emu, onDismiss: () -> Unit) {
  val state by emu.state.collectAsState()
  if (state.installed.isEmpty()) {
    Text("Nenhum firmware instalado. Use o catálogo ou importe um arquivo .fwsc.")
    return
  }
  LazyColumn {
    items(state.installed, key = { it.id }) { fw -> InstalledRow(fw, fw.id == state.current?.id, emu, onDismiss) }
  }
}

@Composable
private fun InstalledRow(fw: Installed, current: Boolean, emu: Emu, onDismiss: () -> Unit) {
  var menu by remember { mutableStateOf(false) }
  Row(
    Modifier.fillMaxWidth().clickable { emu.boot(fw); onDismiss() }.padding(vertical = 12.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column(Modifier.weight(1f)) {
      Text(fw.name + if (current) "  ● em uso" else "", style = MaterialTheme.typography.titleMedium)
      Text(listOf(fw.version, fw.author).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = { menu = true }) { Text("⋮") }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
      DropdownMenuItem(text = { Text("Apagar save") }, onClick = { menu = false; emu.eraseSave(fw) })
      DropdownMenuItem(text = { Text("Remover") }, onClick = { menu = false; emu.remove(fw) })
    }
  }
  HorizontalDivider()
}

@Composable
private fun CatalogTab(emu: Emu, onDismiss: () -> Unit, report: (String?) -> Unit) {
  var entries by remember { mutableStateOf<List<CatalogEntry>?>(null) }
  var failure by remember { mutableStateOf<String?>(null) }
  var query by remember { mutableStateOf("") }
  val scope = rememberCoroutineScope()
  val busy = remember { mutableStateOf<String?>(null) }
  val errors = remember { mutableStateOf(mapOf<String, String>()) }

  LaunchedEffect(Unit) {
    try { entries = Catalog.fetch() } catch (e: Exception) { failure = "Não foi possível carregar o catálogo: ${e.message}" }
  }
  failure?.let { Text(it, color = MaterialTheme.colorScheme.error); return }
  val all = entries ?: run { Text("Carregando…"); return }

  OutlinedTextField(query, { query = it }, label = { Text("Buscar por nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
  LazyColumn {
    items(all.filter { it.name.contains(query, ignoreCase = true) }, key = { it.id }) { e ->
      Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(e.name + if (e.version.isNotEmpty()) "  ${e.version}" else "", style = MaterialTheme.typography.titleMedium)
        Text("${e.author} · ${e.license}", style = MaterialTheme.typography.bodySmall)
        Text(if (e.byo) STOCK_NOTE else e.about, style = MaterialTheme.typography.bodySmall)
        errors.value[e.id]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
          enabled = busy.value == null,
          onClick = {
            busy.value = e.id
            scope.launch {
              val err = emu.installFromCatalog(e)
              busy.value = null
              errors.value = if (err == null) errors.value - e.id else errors.value + (e.id to err)
              if (err == null) onDismiss()
            }
          },
        ) { Text(if (busy.value == e.id) "Instalando…" else "Instalar") }
      }
      HorizontalDivider()
    }
  }
}
