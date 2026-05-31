package com.example.data

import android.content.Context
import com.example.security.CryptoEngine
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class VaultRepository(private val vaultDao: VaultDao) {

    val allActiveEntries: Flow<List<VaultEntry>> = vaultDao.getAllEntries()
    val allDeletedEntries: Flow<List<VaultEntry>> = vaultDao.getDeletedEntries()

    suspend fun getEntryById(id: Int): VaultEntry? {
        return vaultDao.getEntryById(id)
    }

    suspend fun insertEntry(entry: VaultEntry): Long {
        return vaultDao.insertEntry(entry)
    }

    suspend fun updateEntry(entry: VaultEntry) {
        vaultDao.updateEntry(entry)
    }

    suspend fun restoreEntry(id: Int) {
        val entry = vaultDao.getEntryById(id)
        if (entry != null && entry.isDeleted) {
            val restored = entry.copy(
                isDeleted = false,
                deletedDate = null,
                modifiedDate = System.currentTimeMillis()
            )
            vaultDao.updateEntry(restored)
        }
    }

    suspend fun softDeleteEntry(id: Int) {
        val entry = vaultDao.getEntryById(id)
        if (entry != null) {
            val deleted = entry.copy(
                isDeleted = true,
                deletedDate = System.currentTimeMillis(),
                modifiedDate = System.currentTimeMillis()
            )
            vaultDao.updateEntry(deleted)
        }
    }

    suspend fun permanentlyDeleteEntry(id: Int) {
        val entry = vaultDao.getEntryById(id)
        if (entry != null) {
            vaultDao.deleteEntry(entry)
        }
    }

    suspend fun clearRecycleBin() {
        vaultDao.clearRecycleBin()
    }

    suspend fun purgeOldDeletedEntries(retentionDays: Int) {
        if (retentionDays <= 0) return // "Never" auto-delete config
        val retentionMillis = retentionDays * 24L * 60L * 60L * 1000L
        val threshold = System.currentTimeMillis() - retentionMillis
        vaultDao.purgeOldDeletedEntries(threshold)
    }

    /**
     * Export active database entries into a JSON String.
     * Contains option to encrypt the exported content or keep fields plain.
     */
    fun exportToJson(entries: List<VaultEntry>): String {
        val jsonArray = JSONArray()
        for (entry in entries) {
            val jo = JSONObject().apply {
                put("website_name", entry.websiteName)
                put("url", entry.url)
                put("category", entry.category)
                put("tags", entry.tags)
                put("username", entry.getDecryptedUsername())
                put("email", entry.getDecryptedEmail())
                put("password", entry.getDecryptedPassword())
                put("notes", entry.getDecryptedNotes())
                put("favorite", entry.isFavorite)
                put("created_at", entry.creationDate)
            }
            jsonArray.put(jo)
        }
        return jsonArray.toString(2)
    }

    /**
     * Export active database entries into a CSV String.
     */
    fun exportToCsv(entries: List<VaultEntry>): String {
        val sb = StringBuilder()
        sb.append("Website/App,URL,Category,Tags,Username,Email,Password,Notes,IsFavorite,CreatedDate\n")
        for (entry in entries) {
            sb.append(escapeCsvField(entry.websiteName)).append(",")
            sb.append(escapeCsvField(entry.url)).append(",")
            sb.append(escapeCsvField(entry.category)).append(",")
            sb.append(escapeCsvField(entry.tags)).append(",")
            sb.append(escapeCsvField(entry.getDecryptedUsername())).append(",")
            sb.append(escapeCsvField(entry.getDecryptedEmail())).append(",")
            sb.append(escapeCsvField(entry.getDecryptedPassword())).append(",")
            sb.append(escapeCsvField(entry.getDecryptedNotes())).append(",")
            sb.append(entry.isFavorite).append(",")
            sb.append(entry.creationDate).append("\n")
        }
        return sb.toString()
    }

    private fun escapeCsvField(field: String): String {
        val escaped = field.replace("\"", "\"\"")
        return if (escaped.contains(",") || escaped.contains("\n") || escaped.contains("\"")) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    /**
     * Import entries from a JSON String.
     */
    suspend fun importFromJson(jsonString: String): Int {
        var count = 0
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val jo = jsonArray.getJSONObject(i)
                val website = jo.optString("website_name", "Imported App")
                val url = jo.optString("url", "")
                val category = jo.optString("category", "Social Media")
                val tags = jo.optString("tags", "")
                val username = jo.optString("username", "")
                val email = jo.optString("email", "")
                val password = jo.optString("password", "")
                val notes = jo.optString("notes", "")
                val favorite = jo.optBoolean("favorite", false)

                val entry = VaultEntry.createEncrypted(
                    websiteName = website,
                    usernamePlain = username,
                    emailPlain = email,
                    passwordPlain = password,
                    url = url,
                    notesPlain = notes,
                    category = category,
                    tags = tags,
                    isFavorite = favorite
                )
                vaultDao.insertEntry(entry)
                count++
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
        return count
    }

    /**
     * Import entries from a CSV String.
     */
    suspend fun importFromCsv(csvString: String): Int {
        var count = 0
        try {
            val lines = csvString.lines()
            if (lines.size <= 1) return 0
            // Detect header and skip it
            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue
                
                // Extremely simple CSV parser which works for simple fields
                val tokens = mutableListOf<String>()
                var current = StringBuilder()
                var inQuotes = false
                var j = 0
                while (j < line.length) {
                    val c = line[j]
                    if (c == '"') {
                        if (inQuotes && j + 1 < line.length && line[j + 1] == '"') {
                            current.append('"')
                            j++
                        } else {
                            inQuotes = !inQuotes
                        }
                    } else if (c == ',' && !inQuotes) {
                        tokens.add(current.toString())
                        current = StringBuilder()
                    } else {
                        current.append(c)
                    }
                    j++
                }
                tokens.add(current.toString())

                if (tokens.size >= 7) {
                    val website = tokens.getOrNull(0) ?: "Imported Web"
                    val url = tokens.getOrNull(1) ?: ""
                    val category = tokens.getOrNull(2) ?: "Social Media"
                    val tags = tokens.getOrNull(3) ?: ""
                    val username = tokens.getOrNull(4) ?: ""
                    val email = tokens.getOrNull(5) ?: ""
                    val password = tokens.getOrNull(6) ?: ""
                    val notes = tokens.getOrNull(7) ?: ""
                    val favorite = tokens.getOrNull(8)?.toBoolean() ?: false

                    val entry = VaultEntry.createEncrypted(
                        websiteName = website,
                        usernamePlain = username,
                        emailPlain = email,
                        passwordPlain = password,
                        url = url,
                        notesPlain = notes,
                        category = category,
                        tags = tags,
                        isFavorite = favorite
                    )
                    vaultDao.insertEntry(entry)
                    count++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
        return count
    }
}
