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

        // 1. Exact
        all.firstOrNull { it.name.lowercase(Locale.ROOT) == q }?.let { return it }

        // 2. Nickname (papa, mummy, bhai...)
        NICKNAMES[q]?.let { aliases ->
            all.firstOrNull { c -> aliases.any { c.name.lowercase(Locale.ROOT).contains(it) } }
                ?.let { return it }
        }

        // 3. Prefix / token match ("rahul" -> "Rahul Sharma")
        val prefix = all.filter { c ->
            c.name.lowercase(Locale.ROOT).split(" ", ".", "_", "-").any { it.startsWith(q) }
        }
        if (prefix.size == 1) return prefix.first()

        // 4. Fuzzy
        val scored = all.map { it to similarity(q, it.name.lowercase(Locale.ROOT)) }
            .filter { it.second >= 0.72 }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return null
        if (scored.size == 1) return scored.first().first
        if (scored[0].second - scored[1].second > 0.12) return scored[0].first
        return null
    }

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
