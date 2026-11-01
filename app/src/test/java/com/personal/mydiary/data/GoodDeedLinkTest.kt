package com.personal.mydiary.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GoodDeedLinkTest {
    private val expense = MoneyTxn(kind = TxnKind.EXPENSE, date = 100, time = "14:00", amount = 300_00, accountId = 1, category = "Donations", description = "Elders home")

    @Test
    fun aNewDeedComesFromTheExpense() {
        val deed = goodDeedFor(expense, null, "Elders", null)
        assertEquals(EntryType.GOOD_DEED, deed.type)
        assertEquals("Elders home", deed.title)
        assertEquals("Rs 300.00", deed.details)
        assertEquals(100L, deed.date)
        assertEquals("14:00", deed.time)
        assertEquals("Elders", deed.people)
    }

    @Test
    fun aBlankDescriptionFallsBackToTheCategory() {
        assertEquals("Donations", goodDeedFor(expense.copy(description = ""), null, "", null).title)
        assertEquals("Good deed", goodDeedFor(expense.copy(description = "", category = ""), null, "", null).title)
    }

    @Test
    fun editingTheExpenseUpdatesAnUntouchedDeedAndKeepsItsIdentity() {
        val deed = goodDeedFor(expense, null, "", null).copy(id = 5)
        val changed = expense.copy(description = "Elders home visit", amount = 500_00, date = 101)
        val updated = goodDeedFor(changed, deed, "Elders", expense)
        assertEquals(5L, updated.id)
        assertEquals(deed.uid, updated.uid)
        assertEquals("Elders home visit", updated.title)
        assertEquals("Rs 500.00", updated.details)
        assertEquals(101L, updated.date)
    }

    @Test
    fun whatWasWrittenInTheDiaryIsKept() {
        val deed = goodDeedFor(expense, null, "", null).copy(title = "My own title", details = "They were so happy")
        val updated = goodDeedFor(expense.copy(description = "Other", amount = 1), deed, "", expense)
        assertEquals("My own title", updated.title)
        assertEquals("They were so happy", updated.details)
    }
}
