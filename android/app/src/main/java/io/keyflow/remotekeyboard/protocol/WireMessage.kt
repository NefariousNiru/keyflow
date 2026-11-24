package io.keyflow.remotekeyboard.protocol

import java.util.UUID

/** Version 1 message values. Text payloads are omitted from diagnostics. */
internal sealed interface WireMessage {
    data class Hello(val capabilities: Int) : WireMessage
    sealed interface Session : WireMessage {
        val id: UUID
        val seq: Long
    }
    data class Focus(override val id: UUID, override val seq: Long, val generation: Long, val active: Boolean) : Session
    data class Key(override val id: UUID, override val seq: Long, val generation: Long, val key: Int, val down: Boolean) : Session
    data class Text(override val id: UUID, override val seq: Long, val generation: Long, val value: String) : Session {
        override fun toString(): String = "Text"
    }
    data class Release(override val id: UUID, override val seq: Long) : Session
    data class Heartbeat(override val id: UUID, override val seq: Long) : Session
    data class Pause(override val id: UUID, override val seq: Long, val paused: Boolean) : Session
    data class Error(override val id: UUID, override val seq: Long, val code: Int) : Session

    fun fields(): Map<String, Any> {
        val fields = linkedMapOf<String, Any>("v" to 1L)
        if (this is Session) {
            fields["session"] = id.toString()
            fields["seq"] = seq
        }
        fields["type"] = when (this) {
            is Hello -> "hello".also { fields["capabilities"] = capabilities.toLong() }
            is Focus -> "focus".also {
                fields["generation"] = generation
                fields["active"] = active
            }
            is Key -> "key".also {
                fields["generation"] = generation
                fields["key"] = key.toLong()
                fields["down"] = down
            }
            is Text -> "text".also {
                fields["generation"] = generation
                fields["text"] = value
            }
            is Release -> "release"
            is Heartbeat -> "heartbeat"
            is Pause -> "pause".also { fields["paused"] = paused }
            is Error -> "error".also { fields["code"] = code.toLong() }
        }
        return fields
    }
}
