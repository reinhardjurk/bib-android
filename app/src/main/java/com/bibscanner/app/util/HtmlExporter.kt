package com.bibscanner.app.util

import android.content.Context
import com.bibscanner.app.data.BibResult
import java.io.File

/** Writes a race_results.html table matching the Python output layout. */
object HtmlExporter {

    fun export(context: Context, results: List<BibResult>): String {
        val sb = StringBuilder()
        sb.append(
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <title>Race Results (Android)</title>
                <style>
                    body { font-family: Arial, sans-serif; background-color: #f4f4f9; padding: 20px; }
                    table { width: 100%; border-collapse: collapse; margin-top: 20px; background: white; }
                    th, td { padding: 12px; border: 1px solid #ddd; text-align: left; vertical-align: middle; }
                    th { background-color: #007BFF; color: white; }
                    img { max-width: 300px; border-radius: 5px; border: 2px solid green; }
                </style>
            </head>
            <body>
                <h1>Detected Runners (Android)</h1>
                <table>
                    <tr><th>Bib Number</th><th>Time (h:mm:ss)</th><th>Close-up</th></tr>
            """.trimIndent()
        )
        for (r in results) {
            val img = r.imagePath?.let { "<img src=\"file://$it\" alt=\"Bib ${r.bib}\">" } ?: "—"
            sb.append(
                "<tr><td><strong>${r.bib}</strong></td><td>${r.timeText}</td><td>$img</td></tr>\n"
            )
        }
        sb.append("</table></body></html>")

        val file = File(context.getExternalFilesDir(null), "race_results.html")
        file.writeText(sb.toString())
        return file.absolutePath
    }
}
