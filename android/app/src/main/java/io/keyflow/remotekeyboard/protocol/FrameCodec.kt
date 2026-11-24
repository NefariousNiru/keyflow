package io.keyflow.remotekeyboard.protocol

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.util.UUID

/** The caller owns the authenticated stream and serializes reads and writes per direction. */
internal object FrameCodec {
    private const val MAX_FRAME = 16 * 1024
    private const val MAX_TEXT = 1024
    private val uuid = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    private val integer = Regex("-?(0|[1-9][0-9]*)")

    /** Null means clean EOF. A partial or invalid frame requires closing the connection. */
    fun read(input: DataInputStream): WireMessage? {
        val first = input.read()
        if (first == -1) return null
        val length = (first shl 24) or (input.readUnsignedByte() shl 16) or
            (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        if (length !in 1..MAX_FRAME) invalid()

        val body = ByteArray(length)
        try {
            input.readFully(body)
            val json = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString()
            return decode(json)
        } catch (_: IOException) {
            invalid()
        } catch (_: IllegalStateException) {
            invalid()
        } finally {
            body.fill(0)
        }
    }

    fun write(output: DataOutputStream, message: WireMessage) {
        val fields = message.fields()
        validate(fields)
        val text = StringWriter()
        JsonWriter(text).use { writer ->
            writer.beginObject()
            for ((name, value) in fields) {
                writer.name(name)
                when (value) {
                    is String -> writer.value(value)
                    is Boolean -> writer.value(value)
                    is Long -> writer.value(value)
                    else -> invalid()
                }
            }
            writer.endObject()
        }
        val body = text.toString().toByteArray(Charsets.UTF_8)
        try {
            if (body.size !in 1..MAX_FRAME) invalid()
            output.writeInt(body.size)
            output.write(body)
        } finally {
            body.fill(0)
        }
    }

    private fun decode(json: String): WireMessage {
        val fields = linkedMapOf<String, Any>()
        JsonReader(StringReader(json)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (fields.size >= 7 || fields.containsKey(name)) invalid()
                fields[name] = when (reader.peek()) {
                    JsonToken.STRING -> reader.nextString()
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NUMBER -> {
                        val value = reader.nextString()
                        if (!integer.matches(value)) invalid()
                        value.toLongOrNull() ?: invalid()
                    }
                    else -> invalid()
                }
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) invalid()
        }
        return validate(fields)
    }

    private fun validate(fields: Map<String, Any>): WireMessage {
        if (fields["v"] != 1L) invalid()
        val type = fields["type"] as? String ?: invalid()
        if (type == "hello") {
            if (fields.size != 3) invalid()
            return WireMessage.Hello(fields.number("capabilities", 1L..3L).toInt())
        }

        val id = fields["session"] as? String ?: invalid()
        if (!uuid.matches(id)) invalid()
        val session = UUID.fromString(id)
        if (session == UUID(0, 0)) invalid()
        val seq = fields.number("seq")
        return when (type) {
            "focus" -> {
                if (fields.size != 6) invalid()
                WireMessage.Focus(session, seq, fields.number("generation"), fields.flag("active"))
            }
            "key" -> {
                if (fields.size != 7) invalid()
                WireMessage.Key(session, seq, fields.number("generation"),
                    fields.number("key", 1L..77L).toInt(), fields.flag("down"))
            }
            "text" -> {
                if (fields.size != 6) invalid()
                val value = fields["text"] as? String ?: invalid()
                if (value.isEmpty() || value.length > MAX_TEXT || value.any { it.isISOControl() } ||
                    Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value)).remaining() > MAX_TEXT
                ) invalid()
                WireMessage.Text(session, seq, fields.number("generation"), value)
            }
            "release" -> {
                if (fields.size != 4) invalid()
                WireMessage.Release(session, seq)
            }
            "heartbeat" -> {
                if (fields.size != 4) invalid()
                WireMessage.Heartbeat(session, seq)
            }
            "pause" -> {
                if (fields.size != 5) invalid()
                WireMessage.Pause(session, seq, fields.flag("paused"))
            }
            "error" -> {
                if (fields.size != 5) invalid()
                WireMessage.Error(session, seq, fields.number("code", 1L..3L).toInt())
            }
            else -> invalid()
        }
    }

    private fun Map<String, Any>.number(key: String, range: LongRange = 1..Long.MAX_VALUE): Long =
        (get(key) as? Long)?.takeIf { it in range } ?: invalid()

    private fun Map<String, Any>.flag(key: String): Boolean = get(key) as? Boolean ?: invalid()

    private fun invalid(): Nothing = throw IOException("Invalid Keyflow protocol message.")
}
