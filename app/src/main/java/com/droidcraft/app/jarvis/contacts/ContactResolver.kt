package com.droidcraft.app.jarvis.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.min

data class ContactHit(val name: String, val number: String)

/**
 * Naam se contact dhundhna — exact, nickname (papa/mummy), prefix aur fuzzy (Levenshtein).
 */
class ContactResolver(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Naam se contact number; nahi mila to null. */
    fun resolve(query: String): ContactHit? {
        if (!hasPermission()) return null
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return null
        val all = loadContacts()
        if (all.isEmpty()) return null

        // 1. Exact: duplicate names with different numbers are ambiguous and must be clarified.
        val exact = all.filter { it.name.lowercase(Locale.ROOT) == q }
        if (exact.size == 1) return exact.single()
        if (exact.size > 1) return null

        // 2. Nickname (papa, mummy, bhai...); never silently choose among multiple contacts.
        NICKNAMES[q]?.let { aliases ->
            val matches = all.filter { c -> aliases.any { c.name.lowercase(Locale.ROOT).contains(it) } }
            if (matches.size == 1) return matches.single()
            if (matches.size > 1) return null
        }

        // 3. Prefix / token match ("rahul" -> "Rahul Sharma")
        val prefix = all.filter { c ->
            c.name.lowercase(Locale.ROOT).split(" ", ".", "_", "-").any { it.startsWith(q) }
        }
        if (prefix.size == 1) return prefix.single()
        if (prefix.size > 1) return null

        // 4. Fuzzy
        val scored = all.map { it to similarity(q, it.name.lowercase(Locale.ROOT)) }
            .filter { it.second >= 0.72 }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return null
        if (scored.size == 1) return scored.first().first
        if (scored[0].second - scored[1].second > 0.12) return scored[0].first
        return null
    }

    /**
     * Naam ke sabse qareebi matches, best pehle. Matra ke chhote farak (अधूराम/अधुराम)
     * par bhi match milta hai, taaki Jarvis saamne rakh kar poochh sake ki kaunsa wala.
     */
    fun resolveCandidates(query: String, limit: Int = 5): List<ContactHit> {
        if (!hasPermission()) return emptyList()
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return emptyList()
        val all = loadContacts()
        if (all.isEmpty()) return emptyList()
        val qFlat = stripMatras(q)
        val scored = all.mapNotNull { contact ->
            val name = contact.name.lowercase(Locale.ROOT)
            val nameFlat = stripMatras(name)
            val tokens = name.split(" ", ".", "_", "-")
            val tokenFlat = nameFlat.split(" ", ".", "_", "-")
            val score = when {
                name == q -> 100
                nameFlat == qFlat -> 95
                tokens.any { it.startsWith(q) } || tokenFlat.any { it.startsWith(qFlat) } -> 85
                name.contains(q) || nameFlat.contains(qFlat) -> 75
                else -> (similarity(qFlat, nameFlat) * 100).toInt().takeIf { it >= 60 } ?: 0
            }
            if (score > 0) contact to score else null
        }
        return scored.sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.number }
            .take(limit)
    }

        private fun stripMatras(value: String): String =
        value.replace(Regex("[\\u093C\\u093E-\\u094D\\u0900-\\u0903\\u0962-\\u0963\\u200C\\u200D]"), "")

        private fun loadContacts(): List<ContactHit> {
        val result = mutableListOf<ContactHit>()
        if (!hasPermission()) return result
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(projection[0])
                val numIdx = cursor.getColumnIndex(projection[1])
                val seen = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    val raw = if (numIdx >= 0) cursor.getString(numIdx).orEmpty() else ""
                    val number = normalizeNumber(raw)
                    if (name.isNotBlank() && number.isNotBlank() && seen.add(number)) {
                        result.add(ContactHit(name.trim(), number))
                    }
                }
            }
            result
        }.getOrDefault(emptyList())
    }

    /** 10 digit -> 91 prefix; leading 0 hatayo. */
    private fun normalizeNumber(raw: String): String {
        val digits = raw.replace(Regex("[^0-9]"), "")
        return when {
            digits.length == 10 -> "91$digits"
            digits.length == 11 && digits.startsWith("0") -> "91${digits.substring(1)}"
            digits.length == 12 && digits.startsWith("91") -> digits
            else -> digits
        }
    }

    private fun similarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val dist = levenshtein(a, b)
        return (a.length - dist).toFloat() / a.length.toFloat()
    }

    private fun levenshtein(lhs: String, rhs: String): Int {
        var prev = IntArray(rhs.length + 1) { it }
        var curr = IntArray(rhs.length + 1)
        for (i in 1..lhs.length) {
            curr[0] = i
            for (j in 1..rhs.length) {
                val cost = if (lhs[i - 1] == rhs[j - 1]) 0 else 1
                curr[j] = min(min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[rhs.length]
    }

    companion object {
        private val NICKNAMES = mapOf(
            "papa" to listOf("papa", "father", "daddy", "dad", "abba", "abbu"),
            "mummy" to listOf("mummy", "mom", "mother", "maa", "ammi"),
            "moma" to listOf("mummy", "mom", "mother", "maa"),
            "bhai" to listOf("bhai", "brother", "bro"),
            "didi" to listOf("didi", "sister", "sis"),
            "wife" to listOf("wife", "biwi", "patni"),
            "husband" to listOf("husband", "pati")
        )
    }
}
