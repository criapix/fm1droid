package com.criapix.fm1droid.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One flash save (the Rust `flashrec` records) per firmware id. */
class SaveStore(private val dir: File) {
  init { dir.mkdirs() }

  private fun file(id: String) = File(dir, "$id.flash")
  private fun prev(id: String) = File(dir, "$id.flash.prev")

  fun load(firmwareId: String): ByteArray = file(firmwareId).takeIf { it.isFile }?.readBytes() ?: ByteArray(0)

  /** tmp + rename; the previous save becomes `<id>.flash.prev`. Empty records remove the save. */
  fun write(firmwareId: String, records: ByteArray) {
    val f = file(firmwareId)
    if (records.isEmpty()) { f.delete(); return }
    val tmp = File(f.path + ".tmp")
    tmp.writeBytes(records)
    if (f.isFile) Files.move(f.toPath(), prev(firmwareId).toPath(), StandardCopyOption.REPLACE_EXISTING)
    Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }

  fun clear(firmwareId: String) {
    file(firmwareId).delete()
    prev(firmwareId).delete()
  }
}
