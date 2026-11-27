package com.opendroid.ai.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TanglishToTamilTest {
    @Test
    fun detectsCommonSpokenTamilWrittenInLatinLetters() {
        assertTrue(TanglishToTamil.looksTanglish("naan unakku help panren"))
    }

    @Test
    fun doesNotConvertANormalEnglishSentence() {
        assertFalse(TanglishToTamil.looksTanglish("I can help you with this"))
    }

    @Test
    fun convertsACommonTanglishWordForTamilSpeech() {
        assertEquals("நான்", TanglishToTamil.convert("naan"))
    }
}
