package com.criapix.fm1droid.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FirmwareStoreTest {
  @get:Rule val tmp = TemporaryFolder()
  private var clock = 1000L
  private fun store(dir: File = File(tmp.root, "fw")) = FirmwareStore(dir) { clock++ }

  @Test fun listsNewestFirst() {
    val s = store()
    val a = s.install(byteArrayOf(1, 2, 3), "A")
    val b = s.install(byteArrayOf(4, 5, 6), "B")
    assertEquals(listOf(b.id, a.id), s.list().map { it.id })
    assertEquals(16, a.id.length)
  }

  @Test fun reinstallingTheSameBytesDoesNotDuplicateAndUpdatesTheName() {
    val s = store()
    val a = s.install(byteArrayOf(1, 2, 3), "Old", version = "1")
    val again = s.install(byteArrayOf(1, 2, 3), "New", version = "2")
    assertEquals(a.id, again.id)
    assertEquals(1, s.list().size)
    assertEquals("New", s.list().single().name)
    assertEquals("2", s.list().single().version)
  }

  @Test fun bytesComeBackAndRemoveDeletesBoth() {
    val s = store()
    val a = s.install(byteArrayOf(9, 8, 7), "A")
    assertArrayEquals(byteArrayOf(9, 8, 7), s.bytes(a.id))
    s.remove(a.id)
    assertNull(s.bytes(a.id))
    assertTrue(s.list().isEmpty())
  }

  @Test fun aCorruptIndexMeansAnEmptyListNotACrash() {
    val s = store()
    s.install(byteArrayOf(1), "A")
    File(tmp.root, "fw/index.json").writeText("{ not json")
    assertTrue(s.list().isEmpty())
  }

  @Test fun lastUsedSurvivesANewInstance() {
    val s = store()
    val a = s.install(byteArrayOf(1), "A")
    s.lastUsed = a.id
    assertEquals(a.id, store().lastUsed)
    s.remove(a.id)
    assertNull(store().lastUsed)
  }
}

class SaveStoreTest {
  @get:Rule val tmp = TemporaryFolder()
  private fun store() = SaveStore(File(tmp.root, "saves"))

  @Test fun unknownIdLoadsEmpty() = assertEquals(0, store().load("nope").size)

  @Test fun writeThenLoadRoundTrips() {
    val s = store()
    s.write("a", byteArrayOf(1, 2, 3))
    assertArrayEquals(byteArrayOf(1, 2, 3), s.load("a"))
  }

  @Test fun theSecondWriteKeepsTheFirstAsPrev() {
    val s = store()
    s.write("a", byteArrayOf(1))
    s.write("a", byteArrayOf(2))
    assertArrayEquals(byteArrayOf(2), s.load("a"))
    assertArrayEquals(byteArrayOf(1), File(tmp.root, "saves/a.flash.prev").readBytes())
  }

  @Test fun emptyRecordsRemoveTheSaveInsteadOfLeavingAZeroByteFile() {
    val s = store()
    s.write("a", byteArrayOf(1))
    s.write("a", ByteArray(0))
    assertFalse(File(tmp.root, "saves/a.flash").exists())
    assertEquals(0, s.load("a").size)
  }

  @Test fun clearRemovesBothFiles() {
    val s = store()
    s.write("a", byteArrayOf(1)); s.write("a", byteArrayOf(2))
    s.clear("a")
    assertFalse(File(tmp.root, "saves/a.flash").exists())
    assertFalse(File(tmp.root, "saves/a.flash.prev").exists())
  }
}

class CatalogTest {
  private val sample = """
    {"note":"x","default":"felucca","skipped":[],"firmwares":[
      {"id":"felucca","name":"Felucca","version":"1.8.2-beta","author":"hugelton","license":"GPL-3.0",
       "about":"Open firmware.","repo":"https://example.org","package":"https://thefirmwarehub.com/fw/felucca.fwsc",
       "installer":"https://example.org/installer","hub":"felucca"},
      {"id":"mvave","name":"M-VAVE stock firmware","author":"M-VAVE","license":"own","about":"stock","repo":"r",
       "byo":true,"download":"https://yms-file-store.oss-cn-hongkong.aliyuncs.com/software/firmware/FM-1.fwsc"},
      {"id":"april","name":"April","author":"x","license":"MIT","about":"no package","repo":"r"}
    ]}
  """.trimIndent()

  @Test fun entriesWithoutAnAddressAreLeftOutAndUnknownKeysAreIgnored() {
    val list = Catalog.parse(sample)
    assertEquals(listOf("felucca", "mvave"), list.map { it.id })
    assertEquals("https://thefirmwarehub.com/fw/felucca.fwsc", list[0].url)
  }

  @Test fun theStockEntryUsesItsDownloadAddress() {
    val mvave = Catalog.parse(sample).first { it.id == "mvave" }
    assertTrue(mvave.byo)
    assertNotNull(mvave.url)
    assertEquals(mvave.download, mvave.url)
  }
}
