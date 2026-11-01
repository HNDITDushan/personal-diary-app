package com.personal.mydiary.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetMergeTest {
    private data class Item(val uid: String, val text: String, val updatedAt: Long)

    private class Rules(var protected: Set<String> = emptySet()) : MergeRules<Item> {
        val stored = mutableListOf<Item>()
        val removed = mutableListOf<Item>()
        override fun key(item: Item) = item.uid
        override fun updatedAt(item: Item) = item.updatedAt
        override fun toRow(item: Item) = mapOf("Text" to item.text, "uid" to item.uid)
        override fun rowKey(row: Map<String, String>) = row["uid"]?.takeIf { it.isNotEmpty() }
        override fun fromRow(row: Map<String, String>, existing: Item?, key: String, now: Long) =
            row["Text"]?.takeIf { it.isNotEmpty() }?.let { Item(key, it, now) }
        override suspend fun store(item: Item) { stored += item }
        override suspend fun remove(item: Item) { removed += item }
        override suspend fun canRemove(item: Item) = item.uid !in protected
    }

    private val server = 1_000L
    private val phone = 500L
    private val synced = Item("a", "one", 400)

    private fun merge(rules: Rules, local: List<Item>, rows: List<Map<String, String>>, tabExists: Boolean = true, stats: MergeStats = MergeStats()) =
        runBlocking { mergeSheetRows(rules, local, rows, tabExists, server, phone, 600, stats) }

    private fun row(uid: String, text: String, updated: Long) = mapOf("uid" to uid, "Text" to text, "updatedAt" to updated.toString())

    /** The danger when new tabs arrive: existing money records have no tab yet, and must not be deleted. */
    @Test
    fun aTabThatDoesNotExistYetDeletesNothing() {
        val rules = Rules()
        val out = merge(rules, listOf(synced, Item("b", "two", 100)), emptyList(), tabExists = false)
        assertTrue(rules.removed.isEmpty())
        assertEquals(2, out.size)
    }

    @Test
    fun aClearedTabDeletesNothing() {
        val rules = Rules()
        val out = merge(rules, listOf(synced), emptyList(), tabExists = true)
        assertTrue(rules.removed.isEmpty())
        assertEquals(1, out.size)
    }

    @Test
    fun aDeletedRowDeletesItsRecord() {
        val rules = Rules()
        val other = Item("b", "two", 400)
        val out = merge(rules, listOf(synced, other), listOf(row("b", "two", server)))
        assertEquals(listOf(synced), rules.removed)
        assertEquals(listOf("b"), out.map { it["uid"] })
    }

    @Test
    fun aProtectedRecordSurvivesAndItsRowComesBack() {
        val rules = Rules(protected = setOf("a"))
        val out = merge(rules, listOf(synced, Item("b", "two", 400)), listOf(row("b", "two", server)))
        assertTrue(rules.removed.isEmpty())
        assertEquals(setOf("a", "b"), out.map { it["uid"] }.toSet())
    }

    @Test
    fun anUnchangedSyncChangesNothing() {
        val rules = Rules()
        val stats = MergeStats()
        val out = merge(rules, listOf(synced), listOf(row("a", "one", server)), stats = stats)
        assertTrue(rules.stored.isEmpty() && rules.removed.isEmpty())
        assertEquals(0, stats.added + stats.updated + stats.deleted)
        assertEquals(1, out.size)
    }

    @Test
    fun aRowAddedInTheSheetIsImported() {
        val rules = Rules()
        merge(rules, listOf(synced), listOf(row("a", "one", server), mapOf("Text" to "typed")))
        assertEquals(listOf("typed"), rules.stored.map { it.text })
    }

    @Test
    fun anEditInTheSheetUpdatesTheApp() {
        val rules = Rules()
        merge(rules, listOf(synced), listOf(row("a", "edited", server + 1)))
        assertEquals("edited", rules.stored.single().text)
    }

    @Test
    fun whenBothChangedTheAppWins() {
        val rules = Rules()
        val out = merge(rules, listOf(Item("a", "phone", phone + 1)), listOf(row("a", "sheet", server + 1)))
        assertTrue(rules.stored.isEmpty())
        assertEquals("phone", out.single()["Text"])
    }

    @Test
    fun anAppDeletionRemovesTheRow() {
        val rules = Rules()
        val out = merge(rules, listOf(Item("b", "two", 400)), listOf(row("a", "one", server), row("b", "two", server)))
        assertEquals(listOf("b"), out.map { it["uid"] })
        assertTrue(rules.stored.isEmpty())
    }

    @Test
    fun anUnreadableRowIsKeptAsTyped() {
        val rules = Rules()
        val bad = mapOf("uid" to "z", "Text" to "", "updatedAt" to server.toString())
        val out = merge(rules, listOf(synced), listOf(row("a", "one", server), bad))
        assertTrue(bad in out)
    }
}
