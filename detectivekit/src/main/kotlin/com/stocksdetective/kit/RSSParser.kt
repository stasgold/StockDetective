package com.stocksdetective.kit

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

/** Minimal RSS 2.0 parser for news feeds (SAX, available on both the JVM and Android). */
object RSSParser {
    fun parse(data: ByteArray): List<NewsArticle> {
        val handler = Handler()
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            factory.newSAXParser().parse(InputSource(ByteArrayInputStream(data)), handler)
        } catch (_: Exception) {
            // Malformed feeds: keep whatever items were read before the error.
        }
        return handler.items
    }

    private val rfc1123: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME
    private val fallback: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)

    internal fun parseDate(s: String): Instant? = try {
        ZonedDateTime.parse(s.trim(), rfc1123).toInstant()
    } catch (_: DateTimeParseException) {
        try { ZonedDateTime.parse(s.trim(), fallback).toInstant() } catch (_: DateTimeParseException) { null }
    }

    private class Handler : DefaultHandler() {
        val items = mutableListOf<NewsArticle>()
        private var current: MutableMap<String, String>? = null
        private val text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
            if (qName == "item") current = mutableMapOf()
            text.setLength(0)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val c = current
            if (qName == "item" && c != null) {
                var title = c["title"] ?: ""
                var source = c["source"] ?: ""
                // Google News titles end with " - Publisher".
                if (source.isEmpty()) {
                    val i = title.lastIndexOf(" - ")
                    if (i >= 0) {
                        source = title.substring(i + 3)
                        title = title.substring(0, i)
                    }
                } else if (title.endsWith(" - $source")) {
                    title = title.dropLast(source.length + 3)
                }
                if (title.isNotEmpty()) {
                    items.add(
                        NewsArticle(
                            title = title,
                            summary = stripHTML(c["description"] ?: ""),
                            source = source,
                            link = c["link"] ?: "",
                            published = c["pubDate"]?.let(::parseDate),
                        )
                    )
                }
                current = null
            } else if (c != null) {
                c[qName] = text.toString().trim()
            }
            text.setLength(0)
        }
    }

    private val entities = listOf(
        "&amp;" to "&", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&lt;" to "<", "&gt;" to ">", "&nbsp;" to " ",
    )

    internal fun stripHTML(s: String): String {
        var out = s.replace(Regex("<[^>]+>"), " ")
        for ((k, v) in entities) out = out.replace(k, v)
        out = out.replace(Regex("\\s+"), " ")
        return out.trim().take(280)
    }
}
