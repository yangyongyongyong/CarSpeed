package com.thomas.carspeed

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object GpxExporter {
    fun buildGpx(record: DriveRecord): String {
        val name = "CarSpeed ${formatFileTime(record.startTimeMs)}"
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<gpx version=\"1.1\" creator=\"CarSpeed\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:carspeed=\"https://carspeed.local/gpx\">")
            appendLine("  <metadata>")
            appendLine("    <name>${escapeXml(name)}</name>")
            appendLine("    <time>${formatGpxTime(record.startTimeMs)}</time>")
            appendLine("  </metadata>")
            appendLine("  <trk>")
            appendLine("    <name>${escapeXml(name)}</name>")
            appendLine("    <trkseg>")
            record.routePoints.forEach { point ->
                appendLine("      <trkpt lat=\"${point.latitude}\" lon=\"${point.longitude}\">")
                appendLine("        <time>${formatGpxTime(point.timeMs)}</time>")
                appendLine("        <extensions>")
                appendLine("          <carspeed:speedKmh>${String.format(Locale.US, "%.2f", point.speedKmh)}</carspeed:speedKmh>")
                point.accuracyMeters?.let {
                    appendLine("          <carspeed:accuracyMeters>${String.format(Locale.US, "%.2f", it)}</carspeed:accuracyMeters>")
                }
                point.bearingDegrees?.let {
                    appendLine("          <carspeed:bearingDegrees>${String.format(Locale.US, "%.1f", it)}</carspeed:bearingDegrees>")
                }
                appendLine("        </extensions>")
                appendLine("      </trkpt>")
            }
            appendLine("    </trkseg>")
            appendLine("  </trk>")
            appendLine("</gpx>")
        }
    }

    fun writeToCache(context: Context, record: DriveRecord): Uri {
        val dir = File(context.cacheDir, "gpx").apply { mkdirs() }
        val file = File(dir, suggestedFileName(record))
        file.writeText(buildGpx(record), Charsets.UTF_8)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    fun shareIntent(context: Context, record: DriveRecord): Intent {
        val uri = writeToCache(context, record)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, suggestedFileName(record))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun suggestedFileName(record: DriveRecord): String {
        return "carspeed-${formatFileTime(record.startTimeMs)}.gpx"
    }

    private val gpxTimeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
    }

    private fun formatGpxTime(timeMs: Long): String {
        return gpxTimeFormat.get()!!.format(Date(timeMs))
    }

    private fun formatFileTime(timeMs: Long): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(timeMs))
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
