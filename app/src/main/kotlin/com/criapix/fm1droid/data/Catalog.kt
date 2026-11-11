package com.criapix.fm1droid.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class CatalogEntry(
  val id: String,
  val name: String,
  val version: String = "",
  val author: String = "",
  val license: String = "",
  val about: String = "",
  val repo: String = "",
  @SerialName("package") val pkg: String? = null,
  val byo: Boolean = false,
  val download: String? = null,
) {
  val url: String? get() = pkg ?: download
}

@Serializable
private data class CatalogFile(val firmwares: List<CatalogEntry> = emptyList())

object Catalog {
  const val URL = "https://raw.githubusercontent.com/joakimeriksson/fm1-emu/master/web/firmwares.json"
  const val MAX_BYTES = 8 * 1024 * 1024
  private val json = Json { ignoreUnknownKeys = true }

  /** Entries without a download address are left out. */
  fun parse(text: String): List<CatalogEntry> = json.decodeFromString<CatalogFile>(text).firmwares.filter { it.url != null }

  suspend fun fetch(): List<CatalogEntry> = withContext(Dispatchers.IO) { parse(get(URL, 2 * 1024 * 1024).decodeToString()) }

  /** https only, at most [MAX_BYTES]. */
  suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
    require(url.startsWith("https://")) { "only https downloads are allowed" }
    get(url, MAX_BYTES)
  }

  private fun get(url: String, limit: Int): ByteArray {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15_000
    c.readTimeout = 15_000
    c.instanceFollowRedirects = true
    try {
      check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
      c.inputStream.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          out.write(buf, 0, n)
          check(out.size() <= limit) { "file too large" }
        }
        return out.toByteArray()
      }
    } finally {
      c.disconnect()
    }
  }
}
