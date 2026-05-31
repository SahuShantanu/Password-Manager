package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.security.CryptoEngine

@Entity(tableName = "vault_entries")
data class VaultEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val websiteName: String,
    val usernameEncrypted: String = "",
    val emailEncrypted: String = "",
    val passwordEncrypted: String = "",
    val url: String = "",
    val notesEncrypted: String = "",
    val category: String = "Social Media",
    val tags: String = "",
    val creationDate: Long = System.currentTimeMillis(),
    val modifiedDate: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedDate: Long? = null,
    val passwordHistory: String = "" // Past passwords encrypted, joined by ","
) {
    // Utility functions to decrypt values dynamically (only in memory)
    fun getDecryptedUsername(): String = CryptoEngine.decrypt(usernameEncrypted)
    fun getDecryptedEmail(): String = CryptoEngine.decrypt(emailEncrypted)
    fun getDecryptedPassword(): String = CryptoEngine.decrypt(passwordEncrypted)
    fun getDecryptedNotes(): String = CryptoEngine.decrypt(notesEncrypted)

    // Helper to generate a new entry with encrypted fields
    companion object {
        fun createEncrypted(
            id: Int = 0,
            websiteName: String,
            usernamePlain: String,
            emailPlain: String,
            passwordPlain: String,
            url: String,
            notesPlain: String,
            category: String,
            tags: String,
            isFavorite: Boolean = false,
            isDeleted: Boolean = false,
            deletedDate: Long? = null,
            passwordHistory: String = ""
        ): VaultEntry {
            return VaultEntry(
                id = id,
                websiteName = websiteName,
                usernameEncrypted = CryptoEngine.encrypt(usernamePlain),
                emailEncrypted = CryptoEngine.encrypt(emailPlain),
                passwordEncrypted = CryptoEngine.encrypt(passwordPlain),
                url = url,
                notesEncrypted = CryptoEngine.encrypt(notesPlain),
                category = category,
                tags = tags,
                creationDate = System.currentTimeMillis(),
                modifiedDate = System.currentTimeMillis(),
                isFavorite = isFavorite,
                isDeleted = isDeleted,
                deletedDate = deletedDate,
                passwordHistory = passwordHistory
            )
        }
    }
}
