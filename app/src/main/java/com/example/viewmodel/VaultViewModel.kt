package com.example.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.VaultDatabase
import com.example.data.VaultEntry
import com.example.data.VaultRepository
import com.example.security.CryptoEngine
import com.example.security.SecurityPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class AuthState {
    SETUP,     // Setup Master PIN
    LOCKED,    // Enter Master PIN to unlock
    UNLOCKED   // Authenticated
}

enum class Screen {
    DASHBOARD,
    VAULT,
    GENERATOR,
    SECURITY_CENTER,
    RECYCLE_BIN,
    SETTINGS
}

class VaultViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val database = VaultDatabase.getDatabase(context)
    val repository = VaultRepository(database.vaultDao())
    val securityPrefs = SecurityPrefs(context)

    // Auth State
    private val _authState = MutableStateFlow(AuthState.LOCKED)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // Navigation state
    private val _currentScreen = MutableStateFlow(Screen.DASHBOARD)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    // Search Query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Selected Category
    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    // Selected Entry for Edit/Detail
    private val _selectedEntry = MutableStateFlow<VaultEntry?>(null)
    val selectedEntry: StateFlow<VaultEntry?> = _selectedEntry.asStateFlow()

    // Multi-select actions in Vault List
    private val _selectedIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedIds: StateFlow<Set<Int>> = _selectedIds.asStateFlow()

    // Clipboard Countdown
    private val _clipboardTimer = MutableStateFlow<Int?>(null)
    val clipboardTimer: StateFlow<Int?> = _clipboardTimer.asStateFlow()

    // Password Health Scanner Alerts list
    private val _scannedRecommendations = MutableStateFlow<List<String>>(emptyList())
    val scannedRecommendations: StateFlow<List<String>> = _scannedRecommendations.asStateFlow()

    // Active screen secure toggle flow for Activity
    val secureScreenshotsFlow = MutableStateFlow(securityPrefs.secureScreenshotsEnabled)

    // Data lists from database
    val activeEntries: StateFlow<List<VaultEntry>> = repository.allActiveEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val deletedEntries: StateFlow<List<VaultEntry>> = repository.allDeletedEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Filtered entries based on Search Query & Category
    val filteredEntries: StateFlow<List<VaultEntry>> = combine(
        activeEntries,
        searchQuery,
        selectedCategory
    ) { entries, query, category ->
        var list = entries
        if (category != "All") {
            list = list.filter { it.category == category }
        }
        if (query.isNotEmpty()) {
            list = list.filter {
                it.websiteName.contains(query, ignoreCase = true) ||
                it.tags.contains(query, ignoreCase = true) ||
                it.url.contains(query, ignoreCase = true) ||
                it.getDecryptedUsername().contains(query, ignoreCase = true)
            }
        }
        list
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Dashboard Stats combining active entries
    val dashboardStats = activeEntries.map { list ->
        val total = list.size
        val weak = list.count { CryptoEngine.getPasswordStrength(it.getDecryptedPassword()) == CryptoEngine.PasswordStrengthLevel.WEAK }
        val strong = list.count { CryptoEngine.getPasswordStrength(it.getDecryptedPassword()) == CryptoEngine.PasswordStrengthLevel.STRONG }
        
        // Setup reuse check
        val plainPasswords = list.map { it.getDecryptedPassword() }.filter { it.isNotEmpty() }
        val reusedCount = plainPasswords.groupBy { it }.filter { it.value.size > 1 }.values.sumOf { it.size }

        // Security score calculation (0 to 100)
        var score = 100
        if (total > 0) {
            val weakPenalty = (weak.toDouble() / total) * 40
            val reusePenalty = (reusedCount.toDouble() / total) * 30
            val remainingRatio = (strong.toDouble() / total) * 30 // Positive contribution
            
            score = (100 - weakPenalty - reusePenalty + (remainingRatio - 30)).coerceIn(10.0, 100.0).toInt()
        }

        DashboardStats(
            totalCount = total,
            weakCount = weak,
            strongCount = strong,
            reusedCount = reusedCount,
            securityScore = score
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardStats())

    private var clipboardJob: Job? = null
    private var inactivityJob: Job? = null
    private var lastActivityTime = System.currentTimeMillis()

    init {
        // Init auth state based on PIN existence
        _authState.value = if (securityPrefs.isPinSetup()) AuthState.LOCKED else AuthState.SETUP
        
        // Start inactivity tracking
        startInactivityTracker()
        
        // Auto-purge recycle bin on start
        viewModelScope.launch {
            repository.purgeOldDeletedEntries(securityPrefs.recycleBinRetentionDays)
        }
    }

    // --- Authentication ---
    fun submitSetupPin(pin: String) {
        if (pin.length >= 4) {
            securityPrefs.setupPin(pin)
            _authState.value = AuthState.UNLOCKED
            securityPrefs.clearFailedAttempts()
        }
    }

    fun submitUnlockPin(pin: String): Boolean {
        return if (securityPrefs.verifyPin(pin)) {
            _authState.value = AuthState.UNLOCKED
            securityPrefs.clearFailedAttempts()
            resetInactivityTimer()
            true
        } else {
            securityPrefs.logFailedAttempt()
            false
        }
    }

    fun simulateBiometricUnlock() {
        if (securityPrefs.isBiometricsEnabled) {
            _authState.value = AuthState.UNLOCKED
            securityPrefs.clearFailedAttempts()
            resetInactivityTimer()
        }
    }

    fun lockVault() {
        _authState.value = AuthState.LOCKED
        _currentScreen.value = Screen.DASHBOARD
        _selectedEntry.value = null
    }

    // --- Navigation ---
    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
        _selectedEntry.value = null
        _selectedIds.value = emptySet()
        resetInactivityTimer()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        resetInactivityTimer()
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
        resetInactivityTimer()
    }

    fun setSelectedEntry(entry: VaultEntry?) {
        _selectedEntry.value = entry
        resetInactivityTimer()
    }

    // --- Multi Select Commands ---
    fun toggleSelectId(id: Int) {
        val current = _selectedIds.value
        _selectedIds.value = if (current.contains(id)) {
            current - id
        } else {
            current + id
        }
        resetInactivityTimer()
    }

    fun clearSelections() {
        _selectedIds.value = emptySet()
    }

    fun bulkSoftDelete() {
        viewModelScope.launch {
            _selectedIds.value.forEach { id ->
                repository.softDeleteEntry(id)
            }
            clearSelections()
        }
    }

    fun bulkRestore() {
        viewModelScope.launch {
            _selectedIds.value.forEach { id ->
                repository.restoreEntry(id)
            }
            clearSelections()
        }
    }

    fun bulkPermanentDelete() {
        viewModelScope.launch {
            _selectedIds.value.forEach { id ->
                repository.permanentlyDeleteEntry(id)
            }
            clearSelections()
        }
    }

    // --- Entry Actions ---
    fun addOrUpdateEntry(
        id: Int = 0,
        website: String,
        usernamePlain: String,
        emailPlain: String,
        passwordPlain: String,
        url: String,
        notesPlain: String,
        category: String,
        tags: String,
        isFavorite: Boolean = false
    ) {
        viewModelScope.launch {
            val history = if (id != 0) {
                val old = repository.getEntryById(id)
                val oldPlainPass = old?.getDecryptedPassword() ?: ""
                val currentHistory = old?.passwordHistory ?: ""
                if (oldPlainPass.isNotEmpty() && oldPlainPass != passwordPlain) {
                    val encryptedOldPass = CryptoEngine.encrypt(oldPlainPass)
                    if (currentHistory.isEmpty()) encryptedOldPass else "$currentHistory,$encryptedOldPass"
                } else {
                    currentHistory
                }
            } else ""

            val entry = VaultEntry.createEncrypted(
                id = id,
                websiteName = website,
                usernamePlain = usernamePlain,
                emailPlain = emailPlain,
                passwordPlain = passwordPlain,
                url = url,
                notesPlain = notesPlain,
                category = category,
                tags = tags,
                isFavorite = isFavorite,
                passwordHistory = history
            )
            repository.insertEntry(entry)
            resetInactivityTimer()
        }
    }

    fun toggleFavorite(entry: VaultEntry) {
        viewModelScope.launch {
            repository.updateEntry(entry.copy(isFavorite = !entry.isFavorite))
        }
    }

    fun softDelete(id: Int) {
        viewModelScope.launch {
            repository.softDeleteEntry(id)
        }
    }

    fun restore(id: Int) {
        viewModelScope.launch {
            repository.restoreEntry(id)
        }
    }

    fun permanentDelete(id: Int) {
        viewModelScope.launch {
            repository.permanentlyDeleteEntry(id)
        }
    }

    fun clearTrash() {
        viewModelScope.launch {
            repository.clearRecycleBin()
        }
    }

    // --- Clipboard Manager ---
    fun copyToClipboard(label: String, text: String) {
        try {
            val clipManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipManager.setPrimaryClip(clip)
            
            // Trigger auto clear countdown
            val timeout = securityPrefs.clipboardTimeoutSeconds
            startClipboardTimer(timeout, clipManager)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startClipboardTimer(seconds: Int, clipboardManager: ClipboardManager) {
        clipboardJob?.cancel()
        _clipboardTimer.value = seconds
        clipboardJob = viewModelScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                delay(1000)
                remaining--
                _clipboardTimer.value = remaining
            }
            try {
                // Clear Primary Clip safely
                val clip = ClipData.newPlainText("cleared", "")
                clipboardManager.setPrimaryClip(clip)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            _clipboardTimer.value = null
        }
    }

    // --- Inactivity Timeout ---
    fun resetInactivityTimer() {
        lastActivityTime = System.currentTimeMillis()
    }

    private fun startInactivityTracker() {
        inactivityJob?.cancel()
        inactivityJob = viewModelScope.launch {
            while (true) {
                delay(5000) // check every 5s
                if (_authState.value == AuthState.UNLOCKED) {
                    val timeoutMillis = securityPrefs.autoLockTimeoutSeconds * 1000L
                    val elapsed = System.currentTimeMillis() - lastActivityTime
                    if (elapsed >= timeoutMillis) {
                        lockVault()
                    }
                }
            }
        }
    }

    // --- Security Center scanner alerts ---
    fun runSecurityScan(entries: List<VaultEntry>) {
        val recommendations = mutableListOf<String>()
        val weakCount = entries.count { CryptoEngine.getPasswordStrength(it.getDecryptedPassword()) == CryptoEngine.PasswordStrengthLevel.WEAK }
        if (weakCount > 0) {
            recommendations.add("Critical: $weakCount credentials contain weak passwords. Use the password generator to update them.")
        }

        val plainPasswords = entries.map { it.getDecryptedPassword() }.filter { it.isNotEmpty() }
        val reusedCount = plainPasswords.groupBy { it }.filter { it.value.size > 1 }.size
        if (reusedCount > 0) {
            recommendations.add("Warning: $reusedCount passwords are reused across multiple sites. Reused passwords are vulnerable to credential stuffing.")
        }

        val shortCount = entries.count { it.getDecryptedPassword().length < 8 && it.getDecryptedPassword().isNotEmpty() }
        if (shortCount > 0) {
            recommendations.add("Warning: $shortCount passwords are shorter than 8 characters, which can be easily cracked via brute-force.")
        }

        val oldThreshold = System.currentTimeMillis() - (180L * 24L * 60L * 60L * 1000L) // 6 months
        val oldCount = entries.count { it.modifiedDate < oldThreshold }
        if (oldCount > 0) {
            recommendations.add("Advisory: $oldCount passwords haven't been updated in over 180 days. Rotate old passwords regularly to stay secure.")
        }

        if (recommendations.isEmpty() && entries.isNotEmpty()) {
            recommendations.add("All clean! Your vault is optimized. Practice rotating keys or adding dual tags to maintain robust safety.")
        } else if (entries.isEmpty()) {
            recommendations.add("Add setup keys to run a database health audit and view security suggestions.")
        }
        
        _scannedRecommendations.value = recommendations
    }

    // --- Settings & Screenshots ---
    fun setSecureScreenshots(enabled: Boolean) {
        securityPrefs.secureScreenshotsEnabled = enabled
        secureScreenshotsFlow.value = enabled
    }

    override fun onCleared() {
        super.onCleared()
        clipboardJob?.cancel()
        inactivityJob?.cancel()
    }
}

data class DashboardStats(
    val totalCount: Int = 0,
    val weakCount: Int = 0,
    val strongCount: Int = 0,
    val reusedCount: Int = 0,
    val securityScore: Int = 100
)
