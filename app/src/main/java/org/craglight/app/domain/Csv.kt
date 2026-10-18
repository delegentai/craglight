package org.craglight.app.domain

object Csv {
    /** Quote every cell, preserve embedded newlines, and prevent spreadsheet formula execution. */
    fun cell(value: String, neutralizeFormulas: Boolean = true): String {
        val firstMeaningful = value.dropWhile { it.isWhitespace() || it == '\uFEFF' }.firstOrNull()
        val unsafe = firstMeaningful in listOf('=', '+', '-', '@') || value.firstOrNull() in listOf('\t', '\r', '\n')
        val safe = if (neutralizeFormulas && unsafe) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    fun row(values: List<String>): String = values.joinToString(",") { cell(it) }

    fun document(header: List<String>, rows: Iterable<List<String>>): String = buildString {
        append(row(header)).append("\r\n")
        rows.forEach { append(row(it)).append("\r\n") }
    }
}
