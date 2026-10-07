package com.paperboy.android

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Keeps imported ROMs and their saves in app-private storage.
 *
 * Android 11 (SDK 30) enforces scoped storage, so the app cannot read an arbitrary
 * path like the ESP32 firmware did on its SD card. The user picks files with the
 * system picker, each ROM is copied here once, and saves are keyed by the SHA-1 of
 * the ROM contents (renaming or re-importing a ROM never orphans its save).
 *
 *   files/roms/<sha1>.gb     the ROM
 *   files/roms/<sha1>.meta   line 1: display name, line 2: "gbc" if Color-only
 *   files/saves/<sha1>.sav   battery RAM + RTC  (same "PBSV" format as the firmware)
 *   files/saves/<sha1>.state save state         (firmware format + APU footer)
 */
class GameStore(context: Context) {
    data class Game(val id: String, val name: String, val gbcOnly: Boolean)

    sealed class ImportResult {
        data class Added(val game: Game, val alreadyPresent: Boolean) : ImportResult()
        data class Failed(val reason: String) : ImportResult()
    }

    private class TooLargeException : IOException("too large")

    private val romDir = File(context.filesDir, "roms").also { it.mkdirs() }
    private val saveDir = File(context.filesDir, "saves").also { it.mkdirs() }

    fun list(): List<Game> {
        val files = romDir.listFiles { f -> f.isFile && f.name.endsWith(".gb") } ?: return emptyList()
        return files.map { readMeta(it.name.removeSuffix(".gb")) }.sortedBy { it.name.lowercase() }
    }

    fun readRom(id: String): ByteArray? = if (isValidId(id)) readFile(File(romDir, "$id.gb")) else null

    fun sramFile(id: String): File = File(saveDir, "$id.sav")
    fun stateFile(id: String): File = File(saveDir, "$id.state")

    fun delete(id: String) {
        if (!isValidId(id)) return
        File(romDir, "$id.gb").delete()
        File(romDir, "$id.meta").delete()
        sramFile(id).delete()
        stateFile(id).delete()
    }

    fun importRom(resolver: ContentResolver, uri: Uri): ImportResult {
        val displayName = queryDisplayName(resolver, uri)
        val label = displayName ?: "file"

        val raw: ByteArray = try {
            resolver.openInputStream(uri)?.use { readLimited(it) }
                ?: return ImportResult.Failed("Couldn't open $label")
        } catch (e: TooLargeException) {
            return ImportResult.Failed("$label is too big to be a Game Boy ROM")
        } catch (e: IOException) {
            return ImportResult.Failed("Couldn't read $label")
        } catch (e: SecurityException) {
            return ImportResult.Failed("No permission to read $label")
        }

        var name = displayName?.substringBeforeLast('.')
        val rom: ByteArray
        if (isZip(raw)) {
            val found = try {
                extractFirstRom(raw)
            } catch (e: TooLargeException) {
                return ImportResult.Failed("$label contains a file that is too big")
            } catch (e: IOException) {
                return ImportResult.Failed("Couldn't read the zip $label")
            } ?: return ImportResult.Failed("No .gb or .gbc file inside $label")
            rom = found.second
            if (name.isNullOrBlank()) name = found.first.substringBeforeLast('.')
        } else {
            rom = raw
        }

        if (rom.size < 0x150 || !hasNintendoLogo(rom)) {
            return ImportResult.Failed("$label doesn't look like a Game Boy ROM")
        }

        val id = sha1Hex(rom)
        val romFile = File(romDir, "$id.gb")
        val alreadyPresent = romFile.exists()
        val gbcOnly = (rom[0x143].toInt() and 0xFF) == 0xC0
        val finalName = (name ?: "").trim().ifBlank { headerTitle(rom) }.ifBlank { id.take(8) }

        if (!alreadyPresent) {
            if (!writeAtomic(romFile, rom)) return ImportResult.Failed("Not enough storage for $label")
            writeAtomic(
                File(romDir, "$id.meta"),
                (finalName + "\n" + (if (gbcOnly) "gbc" else "") + "\n").toByteArray(Charsets.UTF_8)
            )
        }
        return ImportResult.Added(Game(id, finalName, gbcOnly), alreadyPresent)
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun readMeta(id: String): Game {
        val lines = try {
            File(romDir, "$id.meta").readLines()
        } catch (e: IOException) {
            emptyList()
        }
        val name = lines.getOrNull(0)?.takeIf { it.isNotBlank() } ?: id.take(8)
        return Game(id, name, lines.getOrNull(1) == "gbc")
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Reads at most [MAX_ROM_BYTES]; throws [TooLargeException] beyond that. */
    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(256 * 1024)
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ROM_BYTES) throw TooLargeException()
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun isZip(b: ByteArray) =
        b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() &&
            b[2] == 3.toByte() && b[3] == 4.toByte()

    /** Returns (entry name, bytes) of the first .gb/.gbc entry, or null. */
    private fun extractFirstRom(zip: ByteArray): Pair<String, ByteArray>? {
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val lower = entry.name.lowercase()
                if (!entry.isDirectory && (lower.endsWith(".gb") || lower.endsWith(".gbc"))) {
                    return Pair(entry.name.substringAfterLast('/'), readLimited(zin))
                }
                entry = zin.nextEntry
            }
        }
        return null
    }

    private fun hasNintendoLogo(rom: ByteArray) =
        (rom[0x104].toInt() and 0xFF) == 0xCE && (rom[0x105].toInt() and 0xFF) == 0xED &&
            (rom[0x106].toInt() and 0xFF) == 0x66 && (rom[0x107].toInt() and 0xFF) == 0x66

    private fun headerTitle(rom: ByteArray): String {
        val sb = StringBuilder()
        for (i in 0x134..0x143) {
            val c = rom[i].toInt() and 0xFF
            if (c == 0) break
            if (c in 0x20..0x7E) sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    private fun sha1Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    private fun isValidId(id: String) = id.length == 40 && id.all { it in '0'..'9' || it in 'a'..'f' }

    companion object {
        /** The largest Game Boy cartridge is 8 MiB. */
        const val MAX_ROM_BYTES = 8 * 1024 * 1024

        /** Temp file + fsync + rename, so a crash never leaves a half-written save. */
        fun writeAtomic(target: File, data: ByteArray): Boolean {
            val tmp = File(target.parentFile, target.name + ".tmp")
            return try {
                FileOutputStream(tmp).use { out ->
                    out.write(data)
                    out.fd.sync()
                }
                val ok = tmp.renameTo(target)
                if (!ok) tmp.delete()
                ok
            } catch (e: IOException) {
                tmp.delete()
                false
            }
        }

        fun readFile(file: File): ByteArray? = try {
            if (file.isFile) file.readBytes() else null
        } catch (e: IOException) {
            null
        }
    }
}
