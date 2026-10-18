package org.craglight.app.domain

import org.junit.Assert.*
import org.junit.Test

class CsvTest {
    @Test fun quotesCommasAndNewlinesRoundTrip() {
        assertEquals("\"A, B\",\"Said \"\"yes\"\"\",\"line 1\nline 2\"", Csv.row(listOf("A, B", "Said \"yes\"", "line 1\nline 2")))
        assertEquals("\"\"", Csv.cell(""))
    }

    @Test fun formulasCannotExecuteWhenOpenedInASpreadsheet() {
        listOf("=SUM(1,2)", "+cmd", "-1+2", "@SUM(A1:A2)", "  =WEBSERVICE(\"https://example.invalid\")", "\tformula", "\n=1").forEach {
            assertTrue(Csv.cell(it).startsWith("\"'"))
        }
        assertEquals("\"ordinary notes\"", Csv.cell("ordinary notes"))
    }

    @Test fun recordsUseCrLfAndKeepUnicode() {
        assertEquals("\"name\",\"notes\"\r\n\"Crag\",\"Café ✓\"\r\n", Csv.document(listOf("name", "notes"), listOf(listOf("Crag", "Café ✓"))))
    }
}
