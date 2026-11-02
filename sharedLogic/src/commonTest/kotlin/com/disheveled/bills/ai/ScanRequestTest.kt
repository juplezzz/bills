package com.disheveled.bills.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanRequestTest {

    private val provider = AiProvider.gemini("key")

    @Test
    fun onePhotoIsTheRequestItAlwaysWas() {
        val content = scanRequest(provider, listOf(ReceiptPhoto("b25l"))).messages.last().content
        assertEquals(listOf("image_url", "text"), content.map { it.type })
        assertEquals("data:image/jpeg;base64,b25l", content[0].imageUrl?.url)
        assertEquals("Extract this receipt.", content[1].text)
    }

    @Test
    fun thePartsOfALongReceiptGoTopFirstWithHowTheyFitTogether() {
        val photos = listOf("YQ", "Yg", "Yw").map { ReceiptPhoto(it) }
        val content = scanRequest(provider, photos).messages.last().content
        assertEquals(
            listOf("data:image/jpeg;base64,YQ", "data:image/jpeg;base64,Yg", "data:image/jpeg;base64,Yw"),
            content.take(3).map { it.imageUrl?.url },
        )
        val text = content.last().text.orEmpty()
        assertTrue(text.startsWith("These 3 photos are parts of one long receipt"), text)
        // The overlap is listed once, but two identical lines in one photo are both kept.
        assertTrue("List each of those repeated lines once" in text, text)
        assertTrue("Two identical lines inside the same photo are two separate items" in text, text)
    }
}
