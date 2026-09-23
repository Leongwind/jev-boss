package com.sanly.jevboss

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PdfTextTest {
    @Test fun extractsTextAndDetectsImageOnlyPdf() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val withText = pdf("Android Kotlin project experience")
        val withoutText = pdf(null)
        assertTrue(PdfText.extract(context, ByteArrayInputStream(withText)).contains("Kotlin"))
        assertTrue(PdfText.extract(context, ByteArrayInputStream(withoutText)).isBlank())
    }

    @Test fun apiKeyRoundTripsThroughAndroidKeystore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secrets = SecretStore(context)
        secrets.put("instrumentation", "test-secret-value")
        assertEquals("test-secret-value", secrets.get("instrumentation"))
        val stored = context.getSharedPreferences("secrets", 0).getString("instrumentation", "") ?: ""
        assertFalse(stored.contains("test-secret-value"))
    }

    private fun pdf(text: String?): ByteArray {
        val output = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            if (text != null) PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(40f, 700f)
                stream.showText(text)
                stream.endText()
            }
            document.save(output)
        }
        return output.toByteArray()
    }
}
