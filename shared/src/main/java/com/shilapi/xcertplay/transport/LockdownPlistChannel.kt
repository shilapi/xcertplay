package com.shilapi.xcertplay.transport

import android.util.Xml
import java.io.Closeable
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.xmlpull.v1.XmlPullParser

/** The small plist value set needed by Lockdown messages. */
sealed class LockdownPlistValue {
    data class Dictionary(val entries: Map<String, LockdownPlistValue>) : LockdownPlistValue()
    data class Text(val value: String) : LockdownPlistValue()
    data class Integer(val value: Long) : LockdownPlistValue()
    data class Boolean(val value: kotlin.Boolean) : LockdownPlistValue()

    class Data(bytes: ByteArray) : LockdownPlistValue() {
        private val value = bytes.copyOf()

        val bytes: ByteArray
            get() = value.copyOf()
    }
}

/**
 * Blocking Lockdown plist framing over one already-connected USBMUX TCP byte stream.
 *
 * A frame is a four-byte big-endian XML plist byte length followed by UTF-8 XML. This class does
 * not pair, start a session or service, negotiate TLS, or establish any CarPlay/carkit session.
 * Calls may block and must run away from Android's main thread.
 */
class LockdownPlistChannel(
    private val connection: BlockingDuplexByteStream,
    private val maximumMessageBytes: Int = DEFAULT_MAXIMUM_MESSAGE_BYTES,
    private val defaultTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val onTrace: (String) -> Unit = {},
) : Closeable {
    private val stateLock = Any()
    private val ioLock = Any()
    private var closed = false
    private var detached = false

    init {
        require(maximumMessageBytes in 1..ABSOLUTE_MAXIMUM_MESSAGE_BYTES) {
            "maximumMessageBytes must be between 1 and $ABSOLUTE_MAXIMUM_MESSAGE_BYTES"
        }
        require(defaultTimeoutMillis in 1..MAXIMUM_TIMEOUT_MILLIS) {
            "defaultTimeoutMillis must be between 1 and $MAXIMUM_TIMEOUT_MILLIS"
        }
    }

    /** Sends one root dictionary as a length-prefixed XML plist. */
    fun send(message: LockdownPlistValue.Dictionary) = synchronized(ioLock) {
        checkOpen()
        sendLocked(message)
    }

    /** Receives one root dictionary, consuming exactly one framed XML plist. */
    fun receive(timeoutMillis: Long = defaultTimeoutMillis): LockdownPlistValue.Dictionary = synchronized(ioLock) {
        checkOpen()
        receiveLocked(timeoutMillis)
    }

    /** Sends [message] and receives its corresponding response without interleaving another request. */
    fun request(
        message: LockdownPlistValue.Dictionary,
        timeoutMillis: Long = defaultTimeoutMillis,
    ): LockdownPlistValue.Dictionary = synchronized(ioLock) {
        validateTimeout(timeoutMillis)
        checkOpen()
        sendLocked(message)
        receiveLocked(timeoutMillis)
    }

    /**
     * Permanently closes this channel without closing its stream and transfers stream ownership.
     *
     * Detach is serialized after any active request and may succeed exactly once. Later channel
     * operations fail as closed; later [close] calls are no-ops for the detached stream.
     */
    fun detach(): BlockingDuplexByteStream = synchronized(ioLock) {
        synchronized(stateLock) {
            if (closed) {
                val state = if (detached) "already detached" else "closed"
                throw IphoneUsbException.DeviceUnavailable("Lockdown plist channel is $state")
            }
            detached = true
            closed = true
            connection
        }
    }

    override fun close() {
        val shouldClose = synchronized(stateLock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (shouldClose && !detached) connection.close()
    }

    private fun sendLocked(message: LockdownPlistValue.Dictionary) {
        val xml = encode(message).toByteArray(StandardCharsets.UTF_8)
        if (xml.isEmpty() || xml.size > maximumMessageBytes) {
            throw IphoneUsbException.Protocol("Lockdown plist message length ${xml.size} is outside 1..$maximumMessageBytes")
        }
        val frame = ByteArray(LENGTH_BYTES + xml.size)
        putU32(frame, 0, xml.size.toLong())
        xml.copyInto(frame, LENGTH_BYTES)
        trace(
            "LOCKDOWN TX bytes=${frame.size} frameHex=${frame.toHex()} " +
                "xml=${xml.toString(StandardCharsets.UTF_8)}",
        )
        connection.send(frame)
    }

    private fun receiveLocked(timeoutMillis: Long): LockdownPlistValue.Dictionary {
        validateTimeout(timeoutMillis)
        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        val header = readFully(LENGTH_BYTES, deadlineNanos)
        val length = readU32(header, 0)
        if (length !in 1..maximumMessageBytes.toLong()) {
            throw IphoneUsbException.Protocol("Invalid Lockdown plist message length $length (maximum $maximumMessageBytes)")
        }
        val xml = readFully(length.toInt(), deadlineNanos)
        val frame = header + xml
        trace(
            "LOCKDOWN RX bytes=${frame.size} frameHex=${frame.toHex()} " +
                "xml=${xml.toString(StandardCharsets.UTF_8)}",
        )
        return parse(xml)
    }

    private fun readFully(byteCount: Int, deadlineNanos: Long): ByteArray {
        val result = ByteArray(byteCount)
        var offset = 0
        while (offset < result.size) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                throw IphoneUsbException.TimedOut("Timed out while reading Lockdown plist frame")
            }
            val timeoutMillis = maxOf(1L, (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
            val chunk = connection.recv(result.size - offset, timeoutMillis)
                ?: throw IphoneUsbException.TimedOut("Timed out while reading Lockdown plist frame")
            if (chunk.isEmpty()) {
                throw IphoneUsbException.Protocol("USBMUX TCP stream ended inside a Lockdown plist frame")
            }
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    private fun parse(xml: ByteArray): LockdownPlistValue.Dictionary {
        try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(StringReader(xml.toString(StandardCharsets.UTF_8)))
            }
            if (parser.nextTag() != XmlPullParser.START_TAG || parser.name != "plist") {
                throw IphoneUsbException.Protocol("Lockdown plist root must be <plist>")
            }
            if (parser.nextTag() != XmlPullParser.START_TAG) {
                throw IphoneUsbException.Protocol("Lockdown plist must contain exactly one root value")
            }
            val value = parseValue(parser, 0)
            if (parser.nextTag() != XmlPullParser.END_TAG || parser.name != "plist") {
                throw IphoneUsbException.Protocol("Lockdown plist must contain exactly one root value")
            }
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.TEXT, XmlPullParser.IGNORABLE_WHITESPACE -> {
                        if (!parser.text.orEmpty().isBlank()) {
                            throw IphoneUsbException.Protocol("Unexpected content after Lockdown plist")
                        }
                    }
                    else -> throw IphoneUsbException.Protocol("Unexpected content after Lockdown plist")
                }
            }
            return value as? LockdownPlistValue.Dictionary
                ?: throw IphoneUsbException.Protocol("Lockdown plist root value must be <dict>")
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: Exception) {
            throw IphoneUsbException.Protocol("Invalid Lockdown plist XML: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun parseValue(parser: XmlPullParser, depth: Int): LockdownPlistValue {
        if (depth > MAXIMUM_NESTING) throw IphoneUsbException.Protocol("Lockdown plist nesting exceeds $MAXIMUM_NESTING")
        return when (parser.name) {
            "dict" -> {
                val entries = LinkedHashMap<String, LockdownPlistValue>()
                while (parser.nextTag() == XmlPullParser.START_TAG) {
                    if (parser.name != "key" || entries.size == MAXIMUM_DICTIONARY_ENTRIES) {
                        throw IphoneUsbException.Protocol("Invalid Lockdown plist dictionary")
                    }
                    val keyText = simpleText(parser, "key")
                    if (entries.containsKey(keyText)) throw IphoneUsbException.Protocol("Duplicate Lockdown plist key '$keyText'")
                    if (parser.nextTag() != XmlPullParser.START_TAG) {
                        throw IphoneUsbException.Protocol("Lockdown plist dictionary value is missing")
                    }
                    entries[keyText] = parseValue(parser, depth + 1)
                }
                if (parser.name != "dict") throw IphoneUsbException.Protocol("Invalid Lockdown plist dictionary")
                LockdownPlistValue.Dictionary(entries)
            }
            "string" -> LockdownPlistValue.Text(simpleText(parser, "string"))
            "integer" -> LockdownPlistValue.Integer(
                simpleText(parser, "integer").trim().toLongOrNull()
                    ?: throw IphoneUsbException.Protocol("Invalid Lockdown plist integer"),
            )
            "true" -> {
                requireEmpty(parser, "true")
                LockdownPlistValue.Boolean(true)
            }
            "false" -> {
                requireEmpty(parser, "false")
                LockdownPlistValue.Boolean(false)
            }
            "data" -> {
                val encoded = simpleText(parser, "data").filterNot(Char::isWhitespace)
                try {
                    LockdownPlistValue.Data(Base64.getDecoder().decode(encoded))
                } catch (error: IllegalArgumentException) {
                    throw IphoneUsbException.Protocol("Invalid base64 Lockdown plist data")
                }
            }
            else -> throw IphoneUsbException.Protocol("Unsupported Lockdown plist value <${parser.name}>")
        }
    }

    private fun encode(message: LockdownPlistValue.Dictionary): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">")
        append("<plist version=\"1.0\">")
        appendValue(message, 0)
        append("</plist>")
    }

    private fun StringBuilder.appendValue(value: LockdownPlistValue, depth: Int) {
        if (depth > MAXIMUM_NESTING) throw IphoneUsbException.Protocol("Lockdown plist nesting exceeds $MAXIMUM_NESTING")
        when (value) {
            is LockdownPlistValue.Dictionary -> {
                if (value.entries.size > MAXIMUM_DICTIONARY_ENTRIES) {
                    throw IphoneUsbException.Protocol("Lockdown plist dictionary exceeds $MAXIMUM_DICTIONARY_ENTRIES entries")
                }
                append("<dict>")
                value.entries.forEach { (key, entryValue) ->
                    append("<key>").appendEscaped(key).append("</key>")
                    appendValue(entryValue, depth + 1)
                }
                append("</dict>")
            }
            is LockdownPlistValue.Text -> append("<string>").appendEscaped(value.value).append("</string>")
            is LockdownPlistValue.Integer -> append("<integer>").append(value.value).append("</integer>")
            is LockdownPlistValue.Boolean -> append(if (value.value) "<true/>" else "<false/>")
            is LockdownPlistValue.Data -> append("<data>")
                .append(Base64.getEncoder().encodeToString(value.bytes))
                .append("</data>")
        }
    }

    private fun StringBuilder.appendEscaped(value: String): StringBuilder = apply {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '\"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character.toString()
                },
            )
        }
    }

    private fun simpleText(parser: XmlPullParser, name: String): String {
        val text = StringBuilder()
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> text.append(parser.text.orEmpty())
                XmlPullParser.ENTITY_REF -> text.append(
                    parser.text ?: throw IphoneUsbException.Protocol("Unknown entity in Lockdown plist <$name>"),
                )
                XmlPullParser.END_TAG -> {
                    if (parser.name != name) throw IphoneUsbException.Protocol("Invalid Lockdown plist <$name>")
                    return text.toString()
                }
                XmlPullParser.START_TAG -> throw IphoneUsbException.Protocol("Lockdown plist <$name> must not contain elements")
                XmlPullParser.END_DOCUMENT -> throw IphoneUsbException.Protocol("Lockdown plist ended inside <$name>")
            }
        }
    }

    private fun requireEmpty(parser: XmlPullParser, name: String) {
        if (parser.nextTag() != XmlPullParser.END_TAG || parser.name != name) {
            throw IphoneUsbException.Protocol("Lockdown plist <$name> must be empty")
        }
    }

    private fun checkOpen() {
        synchronized(stateLock) {
            if (closed) throw IphoneUsbException.DeviceUnavailable("Lockdown plist channel is closed")
        }
    }

    private fun validateTimeout(timeoutMillis: Long) {
        require(timeoutMillis in 1..MAXIMUM_TIMEOUT_MILLIS) {
            "timeoutMillis must be between 1 and $MAXIMUM_TIMEOUT_MILLIS"
        }
    }

    private fun trace(message: String) {
        try {
            onTrace(message)
        } catch (_: Exception) {
            // Logging must never change Lockdown behavior.
        }
    }

    private companion object {
        const val LENGTH_BYTES = 4
        const val DEFAULT_MAXIMUM_MESSAGE_BYTES = 1 * 1024 * 1024
        const val ABSOLUTE_MAXIMUM_MESSAGE_BYTES = 4 * 1024 * 1024
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
        const val MAXIMUM_TIMEOUT_MILLIS = 5 * 60_000L
        const val MAXIMUM_NESTING = 64
        const val MAXIMUM_DICTIONARY_ENTRIES = 1_024
        const val NANOS_PER_MILLISECOND = 1_000_000L

        fun putU32(target: ByteArray, offset: Int, value: Long) {
            target[offset] = (value ushr 24).toByte()
            target[offset + 1] = (value ushr 16).toByte()
            target[offset + 2] = (value ushr 8).toByte()
            target[offset + 3] = value.toByte()
        }

        fun readU32(source: ByteArray, offset: Int): Long =
            ((source[offset].toLong() and 0xff) shl 24) or
                ((source[offset + 1].toLong() and 0xff) shl 16) or
                ((source[offset + 2].toLong() and 0xff) shl 8) or
                (source[offset + 3].toLong() and 0xff)

        private fun ByteArray.toHex(): String =
            if (isEmpty()) "<empty>"
            else joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
