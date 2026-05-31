package com.example.security

import java.security.SecureRandom

object PasswordGenerator {
    private val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"
    private val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val DIGITS = "0123456789"
    private val SYMBOLS = "!@#$%^&*()_+-=[]{}|;':\",./<>?\\`~"

    private val WORDLIST = listOf(
        "cyber", "vault", "matrix", "shield", "packet", "kernel", "daemon", "crypto",
        "vector", "nexus", "quantum", "vertex", "pixel", "binary", "hacker", "cipher",
        "entropy", "secure", "bypass", "proxy", "subnet", "access", "shadow", "beacon",
        "beacon", "flux", "static", "socket", "firewall", "grid", "synth", "cobalt"
    )

    private val random = SecureRandom()

    fun generate(
        length: Int,
        includeUpper: Boolean,
        includeLower: Boolean,
        includeNumbers: Boolean,
        includeSymbols: Boolean,
        customSymbols: String = ""
    ): String {
        val charPool = StringBuilder()
        if (includeLower) charPool.append(LOWERCASE)
        if (includeUpper) charPool.append(UPPERCASE)
        if (includeNumbers) charPool.append(DIGITS)
        if (includeSymbols) {
            if (customSymbols.isNotEmpty()) charPool.append(customSymbols) else charPool.append(SYMBOLS)
        }

        if (charPool.isEmpty()) {
            return ""
        }

        val password = StringBuilder()
        // Ensure at least one character of each selected type is included
        val requiredChars = mutableListOf<Char>()
        if (includeLower) requiredChars.add(LOWERCASE[random.nextInt(LOWERCASE.length)])
        if (includeUpper) requiredChars.add(UPPERCASE[random.nextInt(UPPERCASE.length)])
        if (includeNumbers) requiredChars.add(DIGITS[random.nextInt(DIGITS.length)])
        if (includeSymbols) {
            val syms = if (customSymbols.isNotEmpty()) customSymbols else SYMBOLS
            requiredChars.add(syms[random.nextInt(syms.length)])
        }

        // Fill remaining length
        val pool = charPool.toString()
        val fillLength = (length - requiredChars.size).coerceAtLeast(0)
        for (i in 0 until fillLength) {
            password.append(pool[random.nextInt(pool.length)])
        }

        // Insert required chars randomly
        for (char in requiredChars) {
            if (password.length < length) {
                password.append(char)
            } else {
                val index = random.nextInt(password.length)
                password.insert(index, char)
            }
        }

        // Do a clean final shuffle
        val shuffledList = password.toList().shuffled(random)
        return shuffledList.joinToString("")
    }

    fun generatePassphrase(wordCount: Int, separator: String = "-"): String {
        val list = mutableListOf<String>()
        val size = WORDLIST.size
        for (i in 0 until wordCount) {
            val word = WORDLIST[random.nextInt(size)]
            // Add a random capital letter to some words or append a number for cyber flair
            val cyberStyled = if (random.nextBoolean()) {
                word.replaceFirstChar { it.uppercase() }
            } else {
                word
            }
            list.add(cyberStyled)
        }
        // Append a random number at the end for high entropy
        list[list.lastIndex] = list.last() + random.nextInt(100).toString()
        return list.joinToString(separator)
    }

    /**
     * Generates readable but strong 'pronounceable' passwords (CV-CV-CV syllable patterns).
     */
    fun generatePronounceable(length: Int): String {
        val consonants = "bcdfghjklmnpqrstvwxyz"
        val vowels = "aeiou"
        val sb = StringBuilder()
        var generateConsonant = true
        for (i in 0 until length) {
            if (generateConsonant) {
                sb.append(consonants[random.nextInt(consonants.length)])
            } else {
                sb.append(vowels[random.nextInt(vowels.length)])
            }
            generateConsonant = !generateConsonant
        }
        return sb.toString().replaceFirstChar { if (random.nextBoolean()) it.uppercase() else it.toString() }
    }
}
