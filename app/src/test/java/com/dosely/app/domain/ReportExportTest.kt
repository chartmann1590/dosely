package com.dosely.app.domain

import com.dosely.app.export.ReportExport
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportExportTest {
    @Test fun escapesQuotesAndNewlines() {
        assertEquals("\"a,\"\"b\"\"\nc\"", ReportExport.csvCell("a,\"b\"\nc"))
    }
    @Test fun preventsSpreadsheetFormulasInNotes() {
        assertEquals("\"'=1+1\"", ReportExport.csvCell("=1+1"))
        assertEquals("\"'  @SUM(A1)\"", ReportExport.csvCell("  @SUM(A1)"))
    }
}
