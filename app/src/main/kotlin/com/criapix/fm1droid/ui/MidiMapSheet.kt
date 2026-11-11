package com.criapix.fm1droid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.criapix.fm1droid.data.Prefs
import com.criapix.fm1droid.midi.MidiRouter
import com.criapix.fm1droid.midi.Source
import com.criapix.fm1droid.midi.TARGETS
import com.criapix.fm1droid.midi.Target
import com.criapix.fm1droid.midi.describe

/** Bind the controls of an external MIDI keyboard to the FM-1's buttons, encoders and MASTER. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MidiMapSheet(router: MidiRouter, prefs: Prefs, devices: Int, onDismiss: () -> Unit) {
  val learning by router.learning.collectAsState()
  val revision by router.revision.collectAsState()
  val last by router.lastMessage.collectAsState()
  var units by remember { mutableIntStateOf(router.unitsPerClick) }
  DisposableEffect(Unit) { onDispose { router.cancelLearn() } }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Controle MIDI", style = MaterialTheme.typography.titleLarge)
      Text(
        if (devices == 0) "Nenhum dispositivo MIDI conectado. Ligue o teclado pela porta USB."
        else "Dispositivos MIDI conectados: $devices",
        style = MaterialTheme.typography.bodySmall,
      )
      Text("Última mensagem recebida: ${last.ifEmpty { "—" }}", style = MaterialTheme.typography.bodySmall)
      Text(
        "Toque em Aprender e mexa num botão, knob ou slider do teclado. Botões também aceitam uma tecla. " +
          "O que não estiver associado continua indo para o FM-1 como MIDI normal.",
        style = MaterialTheme.typography.bodySmall,
      )

      Text("Curso dos knobs", style = MaterialTheme.typography.titleMedium)
      Text("Quantos passos do encoder cabem em uma volta completa do knob ou slider do teclado.", style = MaterialTheme.typography.bodySmall)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("127 passos" to 1, "63 passos" to 2, "31 passos" to 4).forEach { (label, u) ->
          FilterChip(selected = units == u, onClick = { units = u; router.unitsPerClick = u; prefs.midiUnits = u }, label = { Text(label) })
        }
      }
      HorizontalDivider()

      TARGETS.forEach { (label, target) ->
        val bound = remember(revision, target) { router.bindings(target) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Column(Modifier.weight(1f)) {
            Text(label)
            Text(
              when {
                learning == target -> "Mexa no controle do teclado…"
                bound.isEmpty() -> "—"
                else -> bound.joinToString { describe(it.first) }
              },
              style = MaterialTheme.typography.bodySmall,
            )
          }
          val cc = bound.firstOrNull { it.first is Source.Cc }
          if (target is Target.Button && cc != null) {
            // off = tap (toggle buttons), on = the button stays down while the control is "on"
            FilterChip(selected = !cc.second.tap, onClick = { router.setTap(target, !cc.second.tap) }, label = { Text("Segurar") })
          }
          if (bound.isNotEmpty()) TextButton(onClick = { router.clear(target) }) { Text("Limpar") }
          if (learning == target) OutlinedButton(onClick = { router.cancelLearn() }) { Text("Cancelar") }
          else Button(onClick = { router.learn(target) }) { Text("Aprender") }
        }
      }

      HorizontalDivider()
      OutlinedButton(onClick = { router.clearAll() }) { Text("Limpar tudo") }
      Text("")
    }
  }
}
