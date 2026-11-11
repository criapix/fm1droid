package com.criapix.fm1droid.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class Installed(
  val id: String, // first 16 hex of the package's SHA-256
  val name: String,
  val version: String = "",
  val author: String = "",
  val source: String = "", // "file" or "catalog:<catalog id>"
  val addedAt: Long = 0,
)

/** Firmware packages on disk: `<id>.fwsc` plus an `index.json`, written atomically. */
class FirmwareStore(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
  private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
  private val index get() = File(dir, "index.json")
  private val lastUsedFile get() = File(dir, "last_used")

  init { dir.mkdirs() }

  fun list(): List<Installed> = readIndex().sortedByDescending { it.addedAt }

  fun install(bytes: ByteArray, name: String, version: String = "", author: String = "", source: String = "file"): Installed {
    val id = MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
    atomicWrite(File(dir, "$id.fwsc"), bytes)
    val all = readIndex().toMutableList()
    val old = all.indexOfFirst { it.id == id }
    val item = Installed(id, name, version, author, source, if (old >= 0) all[old].addedAt else now())
    if (old >= 0) all[old] = item else all += item
    atomicWrite(index, json.encodeToString(all).toByteArray())
    return item
  }

  fun bytes(id: String): ByteArray? = File(dir, "$id.fwsc").takeIf { it.isFile }?.readBytes()

  fun remove(id: String) {
    File(dir, "$id.fwsc").delete()
    atomicWrite(index, json.encodeToString(readIndex().filter { it.id != id }).toByteArray())
    if (lastUsed == id) lastUsedFile.delete()
  }

  var lastUsed: String?
    get() = lastUsedFile.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
    set(v) { if (v == null) lastUsedFile.delete() else atomicWrite(lastUsedFile, v.toByteArray()) }

  private fun readIndex(): List<Installed> =
    try { if (index.isFile) json.decodeFromString<List<Installed>>(index.readText()) else emptyList() } catch (_: Exception) { emptyList() }

  private fun atomicWrite(f: File, data: ByteArray) {
    val tmp = File(f.path + ".tmp")
    tmp.writeBytes(data)
    java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
  }
}
