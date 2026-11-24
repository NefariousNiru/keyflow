using System.Buffers;
using System.Buffers.Binary;
using System.Text;
using System.Text.Json;

namespace RemoteKeyboard.Core.Protocol;

/// <summary>
/// Encodes one versioned message per length-prefixed frame. Callers must serialize access
/// per direction and discard the transport after any framing error or partial write.
/// </summary>
public static class FrameCodec
{
    public const int MaximumFrameBytes = 16 * 1024;
    public const int MaximumTextBytes = 1024;

    private static readonly UTF8Encoding StrictUtf8 = new(false, true);

    /// <summary>Returns null only on clean EOF before the next frame header.</summary>
    public static async ValueTask<ProtocolMessage?> ReadAsync(Stream stream, CancellationToken cancellationToken = default)
    {
        var header = new byte[4];
        if (await stream.ReadAsync(header.AsMemory(0, 1), cancellationToken).ConfigureAwait(false) == 0)
            return null;

        await stream.ReadExactlyAsync(header.AsMemory(1), cancellationToken).ConfigureAwait(false);
        var length = BinaryPrimitives.ReadUInt32BigEndian(header);
        Require(length is > 0 and <= MaximumFrameBytes);

        var body = new byte[(int)length];
        try
        {
            await stream.ReadExactlyAsync(body, cancellationToken).ConfigureAwait(false);
            return Decode(body);
        }
        finally
        {
            Array.Clear(body);
        }
    }

    /// <summary>Validates a complete message before writing its header or body.</summary>
    public static async ValueTask WriteAsync(
        Stream stream, ProtocolMessage message, CancellationToken cancellationToken = default)
    {
        var body = new ArrayBufferWriter<byte>();
        try
        {
            using (var json = new Utf8JsonWriter(body))
                Encode(json, message);

            Require(body.WrittenCount <= MaximumFrameBytes);
            var header = new byte[4];
            BinaryPrimitives.WriteUInt32BigEndian(header, (uint)body.WrittenCount);
            await stream.WriteAsync(header, cancellationToken).ConfigureAwait(false);
            await stream.WriteAsync(body.WrittenMemory, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            body.Clear();
        }
    }

    private static ProtocolMessage Decode(ReadOnlyMemory<byte> body)
    {
        try
        {
            // JsonDocument can defer decoding string contents until a property is read.
            StrictUtf8.GetCharCount(body.Span);
            using var document = JsonDocument.Parse(body, new JsonDocumentOptions { MaxDepth = 2 });
            Require(document.RootElement.ValueKind == JsonValueKind.Object);
            var fields = new Dictionary<string, JsonElement>(StringComparer.Ordinal);
            foreach (var property in document.RootElement.EnumerateObject())
                Require(fields.TryAdd(property.Name, property.Value));

            Require(Get(fields, "v").GetInt32() == 1);
            var kind = Get(fields, "type").GetString();
            if (kind == "hello")
            {
                Require(fields.Count == 3);
                var capabilities = (ProtocolCapabilities)Get(fields, "capabilities").GetInt32();
                ValidateCapabilities(capabilities);
                return new Hello(capabilities);
            }

            var sessionId = Get(fields, "session").GetGuid();
            var sequence = Positive(fields, "seq");
            Require(sessionId != Guid.Empty);
            return kind switch
            {
                "focus" when fields.Count == 6 => new FocusState(sessionId, sequence,
                    Positive(fields, "generation"), Get(fields, "active").GetBoolean()),
                "key" when fields.Count == 7 => new KeyChange(sessionId, sequence,
                    Positive(fields, "generation"), ReadEnum<CanonicalKey>(fields, "key"),
                    Get(fields, "down").GetBoolean()),
                "text" when fields.Count == 6 => new TextCommit(sessionId, sequence,
                    Positive(fields, "generation"), ValidateText(Get(fields, "text").GetString())),
                "release" when fields.Count == 4 => new ReleaseAll(sessionId, sequence),
                "heartbeat" when fields.Count == 4 => new Heartbeat(sessionId, sequence),
                "pause" when fields.Count == 5 => new Pause(sessionId, sequence, Get(fields, "paused").GetBoolean()),
                "error" when fields.Count == 5 => new ErrorNotice(sessionId, sequence,
                    ReadEnum<ProtocolError>(fields, "code")),
                _ => throw InvalidMessage()
            };
        }
        catch (Exception error) when (error is JsonException or FormatException
            or InvalidOperationException or DecoderFallbackException or EncoderFallbackException)
        {
            throw InvalidMessage();
        }
    }

    private static void Encode(Utf8JsonWriter json, ProtocolMessage message)
    {
        json.WriteStartObject();
        json.WriteNumber("v", 1);
        var kind = message switch
        {
            Hello => "hello",
            FocusState => "focus",
            KeyChange => "key",
            TextCommit => "text",
            ReleaseAll => "release",
            Heartbeat => "heartbeat",
            Pause => "pause",
            ErrorNotice => "error",
            _ => throw InvalidMessage()
        };
        json.WriteString("type", kind);
        if (message is SessionMessage session)
        {
            Require(session.SessionId != Guid.Empty && session.Sequence > 0);
            json.WriteString("session", session.SessionId);
            json.WriteNumber("seq", session.Sequence);
        }

        switch (message)
        {
            case Hello hello:
                ValidateCapabilities(hello.Capabilities);
                json.WriteNumber("capabilities", (int)hello.Capabilities);
                break;
            case FocusState focus:
                WriteGeneration(json, focus.Generation);
                json.WriteBoolean("active", focus.Active);
                break;
            case KeyChange key:
                WriteGeneration(json, key.Generation);
                Require(Enum.IsDefined(key.Key));
                json.WriteNumber("key", (int)key.Key);
                json.WriteBoolean("down", key.Down);
                break;
            case TextCommit text:
                WriteGeneration(json, text.Generation);
                json.WriteString("text", ValidateText(text.Text));
                break;
            case Pause pause:
                json.WriteBoolean("paused", pause.Paused);
                break;
            case ErrorNotice error:
                Require(Enum.IsDefined(error.Error));
                json.WriteNumber("code", (int)error.Error);
                break;
        }
        json.WriteEndObject();
    }

    private static void WriteGeneration(Utf8JsonWriter json, long generation)
    {
        Require(generation > 0);
        json.WriteNumber("generation", generation);
    }

    private static string ValidateText(string? text)
    {
        if (string.IsNullOrEmpty(text) || text.Length > MaximumTextBytes || text.Any(char.IsControl))
            throw InvalidMessage();

        try
        {
            Require(StrictUtf8.GetByteCount(text) <= MaximumTextBytes);
            return text;
        }
        catch (EncoderFallbackException)
        {
            throw InvalidMessage();
        }
    }

    private static void ValidateCapabilities(ProtocolCapabilities capabilities) =>
        Require(capabilities != 0 && (capabilities & ~(ProtocolCapabilities.Keys | ProtocolCapabilities.UnicodeText)) == 0);

    private static T ReadEnum<T>(Dictionary<string, JsonElement> fields, string name) where T : struct, Enum
    {
        var value = Get(fields, name).GetInt32();
        Require(Enum.IsDefined(typeof(T), value));
        return (T)Enum.ToObject(typeof(T), value);
    }

    private static long Positive(Dictionary<string, JsonElement> fields, string name)
    {
        var value = Get(fields, name).GetInt64();
        Require(value > 0);
        return value;
    }

    private static JsonElement Get(Dictionary<string, JsonElement> fields, string name) =>
        fields.TryGetValue(name, out var value) ? value : throw InvalidMessage();

    private static void Require(bool condition)
    {
        if (!condition)
            throw InvalidMessage();
    }

    private static InvalidDataException InvalidMessage() => new("Invalid Keyflow protocol message.");
}
