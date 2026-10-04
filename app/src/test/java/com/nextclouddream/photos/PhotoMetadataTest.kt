package com.nextclouddream.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class PhotoMetadataTest {

    @Test
    fun `date EXIF affichee au format long de la langue`() {
        val metadata = PhotoMetadata(dateTaken = "2017:07:31 16:36:21")
        assertEquals("31 juillet 2017", metadata.formattedDate(Locale.FRANCE))
        assertEquals("July 31, 2017", metadata.formattedDate(Locale.US))
    }

    @Test
    fun `date absente ou invalide`() {
        assertNull(PhotoMetadata().formattedDate(Locale.FRANCE))
        assertNull(PhotoMetadata(dateTaken = "pas une date").formattedDate(Locale.FRANCE))
    }

    @Test
    fun `aller-retour JSON`() {
        val full = PhotoMetadata("2022:10:31 10:00:00", 48.86, 2.35, "Paris, France")
        assertEquals(full, PhotoMetadata.fromJsonObject(full.toJsonObject()))

        val empty = PhotoMetadata()
        assertEquals(empty, PhotoMetadata.fromJsonObject(empty.toJsonObject()))
    }
}
