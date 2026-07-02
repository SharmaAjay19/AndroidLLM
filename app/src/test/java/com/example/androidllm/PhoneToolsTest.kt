package com.example.androidllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolsTest {

    @Test
    fun parsesDateTimeSpaceForm() {
        val t = PhoneTools.parseDateTime("2026-07-02 14:30")
        assertTrue(t != null && t > 0)
    }

    @Test
    fun parsesDateTimeIsoForm() {
        val space = PhoneTools.parseDateTime("2026-07-02 14:30")
        val iso = PhoneTools.parseDateTime("2026-07-02T14:30")
        assertEquals(space, iso)
    }

    @Test
    fun rejectsBlankAndGarbageDateTime() {
        assertNull(PhoneTools.parseDateTime(null))
        assertNull(PhoneTools.parseDateTime(""))
        assertNull(PhoneTools.parseDateTime("tomorrow at noon"))
    }

    @Test
    fun rejectsInvalidCalendarDate() {
        // Lenient parsing off: month 13 must fail.
        assertNull(PhoneTools.parseDateTime("2026-13-02 10:00"))
    }

    @Test
    fun parsesDateOnly() {
        val d = PhoneTools.parseDate("2026-07-02")
        assertTrue(d != null && d > 0)
        assertNull(PhoneTools.parseDate("2026/07/02"))
    }

    @Test
    fun writeToolsRequireConfirmation() {
        assertTrue(PhoneTools.isWrite("create_event"))
        assertTrue(PhoneTools.isWrite("create_reminder"))
        assertTrue(PhoneTools.isWrite("compose_message"))
        assertTrue(!PhoneTools.isWrite("read_calendar"))
        assertTrue(!PhoneTools.isWrite("read_clipboard"))
        assertTrue(!PhoneTools.isWrite("find_contact"))
    }

    @Test
    fun readToolsAreRegistered() {
        val expected = setOf(
            "read_clipboard", "find_contact", "read_calendar",
            "create_event", "create_reminder", "compose_message"
        )
        assertEquals(expected, PhoneTools.names)
    }

    @Test
    fun contactPermissionRequired() {
        assertEquals(
            listOf(android.Manifest.permission.READ_CONTACTS),
            PhoneTools.requiredPermissions("find_contact")
        )
        assertTrue(PhoneTools.requiredPermissions("read_clipboard").isEmpty())
        assertTrue(PhoneTools.requiredPermissions("compose_message").isEmpty())
    }
}
