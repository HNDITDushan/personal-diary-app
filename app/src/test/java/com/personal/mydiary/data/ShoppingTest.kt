package com.personal.mydiary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ShoppingTest {
    private fun row(ln: Int, item: String, price: String, qty: String, amt: String) =
        "<tr class=\"f-size-2\"> <td id='ln' style='x'>$ln</td> <td id='itm' style='x'>$item</td> " +
            "<td id='prz' style='x'>$price</td> <td id='qty' style='x'>$qty</td> <td id='amt' style='x'>$amt</td> </tr>"

    private val ebill = """
        <section class='bill'><div class='title f-size-1'> <p>No. 1, Main Street , Sampletown</p>
        <p>011 000 0000 / Store Code: SXYZ</p> <p>03-Feb-2026 09:05:10 C:123 R:98765</p> </div>
        <table>${row(1, "100200: CARROT", "480.00", "0.512", "245.76")}${row(2, "300400: SAMPLE BISCUIT 100G", "1,150.00", "2.0", "2,300.00")}
        ${row(3, "R1234: SHOP BAG", "0.01", "1.0", "0.01")}
        <!-- ${row(9, "1919: COMMENTED OUT DRINK", "120.00", "1.0", "120.00")} --></table>
        <table><tr> <td colspan='6'>Gross Amount</td> <td>2,545.77</td> </tr><tr> <td colspan='6'>Promotion Discount</td> <td>100.00</td> </tr>
        <tr> <td colspan='6' style='padding: 0'>Net Amount</td> <td style='text-align: right'>2,445.77</td> </tr><tr> <td colspan='6'>Cash</td> <td>2,445.77</td> </tr>
        <tr> <td colspan='6'>Total promotion(s) savings</td> <td>100.00</td> </tr></table>
    """.trimIndent()

    @Test
    fun readsKeellsEBill() {
        val bill = KeellsEBill.parse(ebill)
        assertEquals(LocalDate.of(2026, 2, 3), bill.date)
        assertEquals("09:05", bill.time)
        assertEquals("Sampletown", bill.branch)
        assertEquals(3, bill.lines.size)
        assertEquals(ParsedLine("100200", "CARROT", 48000, 0.512, 24576, weighed = true), bill.lines[0])
        assertEquals(ParsedLine("300400", "SAMPLE BISCUIT 100G", 115000, 2.0, 230000), bill.lines[1])
        assertEquals(254577L, bill.gross)
        assertEquals(10000L, bill.discount)
        assertEquals(244577L, bill.net)
        assertEquals("Cash", bill.payment)
        assertEquals("keells:SXYZ:98765:2026-02-03", bill.dedupeKey)
    }

    @Test
    fun findsTheLinkInAMessage() {
        val msg = "Hello,\nview your e-bill for shopping\nat SAMPLETOWN\non 3 February 2026.\nhttps://digibill.keellssuper.com/AB12CD"
        assertEquals("https://digibill.keellssuper.com/AB12CD", KeellsEBill.findLink(msg))
        assertNull(KeellsEBill.findLink("https://example.com/AB12CD"))
    }

    @Test
    fun readsItemsFromPhotoText() {
        val rows = listOf(
            "SAMPLE SUPER", "KEELLS", "03/02/2026 09:05",
            "Item Qty Price Amount",
            "1 100200 CARROT", "480. 00 0.512 245.76",
            "2 300400 SAMPLE BISCUIT 100G 1,150.00 2 2,300.00",
            "4 BREAD 160.00",
            "Gross Amount 2,705.76",
            "LATER 1.00 1 1.00",
        )
        val bill = ReceiptText.parse(rows)
        assertEquals(LocalDate.of(2026, 2, 3), bill.date)
        assertEquals("Keells", bill.store)
        assertEquals(
            listOf(
                ParsedLine("100200", "CARROT", 48000, 0.512, 24576, weighed = true),
                ParsedLine("300400", "SAMPLE BISCUIT 100G", 115000, 2.0, 230000),
                ParsedLine("", "BREAD", 16000, 1.0, 16000),
            ),
            bill.lines,
        )
    }

    private fun purchase(key: String, price: Long, date: Long, bill: String) =
        Purchase(key, key, price, 1.0, price, false, "i$bill$key", bill, date, "", "", "")

    @Test
    fun priceChangeComparesWithThePreviousBill() {
        val list = listOf(purchase("C", 40000, 1, "a"), purchase("C", 44000, 8, "b"), purchase("C", 44000, 15, "c"))
        val history = ShoppingPrices.history(list.shuffled())
        val change = ShoppingPrices.changeFor(list[1], history.getValue("C"))!!
        assertEquals(4000L, change.diff)
        assertEquals(10, change.percent)
        assertNull(ShoppingPrices.changeFor(list[2], history.getValue("C")))
        assertNull(ShoppingPrices.changeFor(list[0], history.getValue("C")))
    }

    @Test
    fun latestChangesListBiggestMovesFirst() {
        val history = ShoppingPrices.history(
            listOf(
                purchase("A", 10000, 1, "a"), purchase("A", 9000, 2, "b"),
                purchase("B", 10000, 1, "a"), purchase("B", 13000, 2, "b"),
                purchase("C", 5000, 1, "a"), purchase("C", 5000, 2, "b"),
            ),
        )
        assertEquals(listOf("B" to 30, "A" to -10), ShoppingPrices.latestChanges(history).map { it.current.itemKey to it.percent })
    }

    @Test
    fun keysMatchByCodeThenByName() {
        val known = listOf(KnownItem("#100200", "CARROT", "100200"), KnownItem("BREAD", "Bread", ""))
        assertEquals("#100200", ShoppingPrices.keyFor("100200", "Carrots", known))
        assertEquals("#555", ShoppingPrices.keyFor("555", "New thing", known))
        assertEquals("#100200", ShoppingPrices.keyFor("", "carrot", known))
        assertEquals("BREAD", ShoppingPrices.keyFor("", "  bread ", known))
        assertEquals("MILK 1L", ShoppingPrices.keyFor("", "Milk 1L", known))
    }

    /** A market bill: Sinhala names on their own lines, figures below, a Sinhala total. */
    @Test
    fun readsSinhalaBillWithNamesAbove() {
        val rows = listOf(
            "Tel : 011 000 0000", "#000009", "2026.10.24", "09.10.20", "Kg/Pcs",
            "කැරට්", "400 0.555 222.0",
            "තක්කාලි", "800 0.270 216.0",
            "120 2 240.0",
            "මුළු එකතුව : 678.0",
            "678.0",
        )
        val bill = ReceiptText.parse(rows)
        assertEquals(LocalDate.of(2026, 10, 24), bill.date)
        assertEquals("09:10", bill.time)
        assertEquals(
            listOf(
                ParsedLine("", "කැරට්", 40000, 0.555, 22200, weighed = true),
                ParsedLine("", "තක්කාලි", 80000, 0.27, 21600, weighed = true),
                ParsedLine("", "", 12000, 2.0, 24000),
            ),
            bill.lines,
        )
    }

    @Test
    fun aLoneFigureEqualToTheItemsIsTheTotal() {
        val bill = ReceiptText.parse(listOf("Bread", "160.00", "Milk", "100 2 200.00", "360.00", "Later", "50.00"))
        assertEquals(listOf("Bread", "Milk"), bill.lines.map { it.name })
    }

    /** Faint print: the points are lost and some digits read as letters, as text recognition gave for a real bill. */
    @Test
    fun recoversFiguresWithoutDecimalPoints() {
        val rows = listOf(
            "Tel: 011 222 3333", "#000009", "2026 10 24", "09 10 20", "ngIPcs",
            "400 0 555 222 0",
            "480 0 095 456",
            "88aux)",
            "240 G 4G0 96 0",
            "300 0120 36 0",
            "640 G.085 54 4",
            "520 02/0 140 4",
            "340 1115 379 1",
            "I013 406 0",
            "400",
            "1213 0",
        )
        val lines = ReceiptText.parse(rows).lines
        assertEquals(
            listOf(
                Triple(40000L, 0.555, 22200L), Triple(48000L, 0.095, 4560L), Triple(24000L, 0.4, 9600L), Triple(30000L, 0.12, 3600L),
                Triple(64000L, 0.085, 5440L), Triple(52000L, 0.27, 14040L), Triple(34000L, 1.115, 37910L),
            ),
            lines.map { Triple(it.unitPrice, it.qty, it.amount) },
        )
        assert(lines.all { it.weighed })
    }

    @Test
    fun sinhalaNamesKeepTheirOwnKeys() {
        assertEquals("කැරට්", ShoppingPrices.keyFor("", "කැරට්", emptyList()))
        val known = listOf(KnownItem("කැරට්", "කැරට්", ""), KnownItem("බෝංචි", "බෝංචි", ""))
        assertEquals("බෝංචි", ShoppingPrices.keyFor("", "බෝංචි", known))
    }

    @Test
    fun misreadNamesMatchWhatWasBoughtBefore() {
        val known = listOf(KnownItem("බෝංචි", "බෝංචි", ""), KnownItem("තක්කාලි", "තක්කාලි", ""), KnownItem("#1", "Carrot", "1"))
        assertEquals("බෝංචි", ShoppingPrices.closestKnown("බඛෝෞංචි", known))
        assertEquals("තක්කාලි", ShoppingPrices.closestKnown("තක්කලි", known))
        assertEquals("Carrot", ShoppingPrices.closestKnown("carrots", known))
        assertNull(ShoppingPrices.closestKnown("ගෝව මල්", known))
    }
}
