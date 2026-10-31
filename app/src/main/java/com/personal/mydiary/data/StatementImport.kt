package com.personal.mydiary.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.withTransaction
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Statement passwords, one per card, encrypted with a key held in the Android Keystore.
 * The key can't leave this phone, so these are left out of backups (see res/xml backup rules).
 */
class StatementPasswords(context: Context) {
    private val prefs = context.getSharedPreferences("statement_passwords", Context.MODE_PRIVATE)

    fun get(accountUid: String): String? {
        val stored = prefs.getString(accountUid, null) ?: return null
        return runCatching {
            val (iv, data) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data))
        }.getOrNull()
    }

    fun set(accountUid: String, password: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(password.toByteArray())
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
        prefs.edit().putString(accountUid, encoded).apply()
    }

    fun clear(accountUid: String) = prefs.edit().remove(accountUid).apply()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "statement_passwords"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** One statement line on the review screen. */
data class ReviewLine(
    val line: StatementParser.Line,
    val key: String,
    val duplicate: Boolean,
    val selected: Boolean = !duplicate,
)

object StatementImport {
    class WrongPassword : Exception()

    /** The PDF's text. The file itself is read straight from where it is; no copy is kept. */
    suspend fun readText(context: Context, uri: Uri, password: String): String = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't open the file")
        input.use {
            try {
                PDDocument.load(it, password).use { doc ->
                    PDFTextStripper().apply { sortByPosition = true }.getText(doc)
                }
            } catch (e: InvalidPasswordException) {
                throw WrongPassword()
            }
        }
    }

    fun fileName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull().orEmpty()

    /** Pairs parsed lines with their duplicate check, and reuses how you filed the same shop before. */
    suspend fun prepareReview(db: AppDatabase, account: Account, parsed: StatementParser.Result): List<ReviewLine> {
        val imported = db.moneyTxns().importedKeys(account.id).toSet()
        val known = db.moneyTxns().knownCategories().associate { it.description.uppercase() to it.category }
        val seen = mutableMapOf<String, Int>()
        return parsed.lines.map { line ->
            val base = "${line.date}|${line.amount}|${line.description.uppercase().filter { it.isLetterOrDigit() }}"
            // Two identical purchases on one day are both real, so number them within the statement.
            val n = seen.merge(base, 1, Int::plus)
            val key = "$base|$n"
            val category = if (line.kind == TxnKind.EXPENSE) known[line.description.uppercase()] ?: line.category else line.category
            ReviewLine(line.copy(category = category), key, duplicate = key in imported)
        }
    }

    /** Saves the ticked lines; returns how many were added. */
    suspend fun save(
        db: AppDatabase, account: Account, parsed: StatementParser.Result, fileName: String, lines: List<ReviewLine>,
    ): Int = db.withTransaction {
        val firstImport = db.moneyTxns().countForAccount(account.id) == 0
        val statementId = db.statements().insert(
            CardStatement(
                accountId = account.id, fileName = fileName,
                statementDate = parsed.statementDate?.toEpochDay(), dueDate = parsed.dueDate?.toEpochDay(),
                closingBalance = parsed.closingBalance, minimumDue = parsed.minimumDue,
            )
        )
        val chosen = lines.filter { it.selected }
        db.moneyTxns().insertAll(
            chosen.map {
                MoneyTxn(
                    kind = it.line.kind, date = it.line.date.toEpochDay(), amount = it.line.amount, accountId = account.id,
                    category = it.line.category, description = it.line.description, statementId = statementId, dedupeKey = it.key,
                )
            }
        )
        // A card's first statement tells us what was already owed before these lines.
        val previous = parsed.previousBalance
        if (firstImport && account.openingBalance == 0L && previous != null) {
            db.accounts().save(account.copy(openingBalance = -previous))
        }
        chosen.size
    }
}
