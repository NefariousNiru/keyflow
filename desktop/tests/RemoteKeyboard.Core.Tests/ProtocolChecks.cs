using System.Net;
using RemoteKeyboard.Core.Network;
using RemoteKeyboard.Core.Pairing;
using System.Buffers.Binary;
using System.Text;
using RemoteKeyboard.Core.Protocol;
using RemoteKeyboard.Core.Sessions;

internal static class ProtocolChecks
{
    public static async Task RunAsync()
    {
        var id = Guid.NewGuid();
        ProtocolMessage[] messages =
        [
            new Hello(ProtocolCapabilities.Keys | ProtocolCapabilities.UnicodeText),
            new FocusState(id, 1, 5, true),
            new KeyChange(id, 2, 5, CanonicalKey.F12, true),
            new TextCommit(id, 3, 5, "Keyflow \u00e9 \U0001f642"),
            new ReleaseAll(id, 4),
            new Heartbeat(id, 5),
            new Pause(id, 6, true),
            new ErrorNotice(id, 7, ProtocolError.StaleFocus)
        ];

        using var stream = new MemoryStream();
        foreach (var message in messages)
            await FrameCodec.WriteAsync(stream, message);
        stream.Position = 0;
        foreach (var message in messages)
            Check(message.Equals(await FrameCodec.ReadAsync(stream)), "Message did not round trip.");
        Check(await FrameCodec.ReadAsync(stream) is null, "Clean EOF was not recognized.");

        await RejectAsync("""{"v":2,"type":"hello","capabilities":1}""");
        await RejectAsync("""{"v":1,"type":"hello","capabilities":4}""");
        await RejectAsync("""{"v":1,"type":"hello","capabilities":1,"v":1}""");
        await RejectAsync("""{"v":1,"type":"hello","capabilities":1,"extra":0}""");
        await RejectAsync("""{"v":1,"type":"heartbeat","session":"00000000-0000-0000-0000-000000000000","seq":1}""");
        await RejectAsync("""{"v":1,"type":"key","session":"12345678-1234-1234-1234-123456789abc","seq":1,"generation":1,"key":999,"down":true}""");
        await RejectAsync("""{"v":1,"type":"text","session":"12345678-1234-1234-1234-123456789abc","seq":1,"generation":1,"text":"\ud800"}""");
        await RejectAsync(new byte[] { 0, 0, 0, 0 });
        await RejectAsync(new byte[] { 0, 0, 64, 1 });
        await RejectAsync(new byte[] { 0, 0, 0, 1, 255 });
        await RejectAsync(new byte[] { 0, 0, 0, 2, 123 });

        using var output = new MemoryStream();
        try
        {
            await FrameCodec.WriteAsync(output, new TextCommit(id, 1, 1, new string('x', 1025)));
            throw new InvalidOperationException("Oversized text was serialized.");
        }
        catch (InvalidDataException)
        {
            Check(output.Length == 0, "A rejected message wrote partial bytes.");
        }

        Check(!new TextCommit(id, 1, 1, "test-payload").ToString().Contains("test-payload"),
            "Message diagnostics exposed text.");

        var clock = new TestClock();
        var paired = clock.GetUtcNow();
        var session = new SessionAuthorization(id, paired, paired, clock);
        Check(session.TryAccept(new Heartbeat(id, 1)), "First sequence rejected.");
        Check(!session.TryAccept(new Heartbeat(id, 1)) && !session.IsAuthorized(), "Replay did not invalidate session.");
        session = new SessionAuthorization(id, paired, paired, clock);
        Check(!session.TryAccept(new Heartbeat(Guid.NewGuid(), 1)), "Foreign session was accepted.");
        session = new SessionAuthorization(id, paired, paired, clock);
        Check(!session.TryAccept(new Heartbeat(id, 2)), "Sequence gap was accepted.");
        session = new SessionAuthorization(id, paired, paired, clock);
        session.Revoke();
        Check(!session.TryAccept(new Heartbeat(id, 1)), "Revocation failed.");
        session = new SessionAuthorization(id, paired, paired, clock);
        clock.Now += SessionAuthorization.Lifetime - TimeSpan.FromTicks(1);
        Check(session.IsAuthorized(), "Session expired early.");
        clock.Now += TimeSpan.FromTicks(1);
        Check(!session.IsAuthorized(), "Session survived exact expiry.");
        session = new SessionAuthorization(id, paired, clock.Now, clock);
        Check(!session.IsAuthorized(), "Expired credentials admitted a new connection.");
        clock.Now = paired;
        session = new SessionAuthorization(id, paired, paired.AddSeconds(1), clock);
        Check(!session.IsAuthorized(), "Persisted rollback was accepted.");
        session = new SessionAuthorization(id, paired, paired, clock);
        clock.Now = paired.AddMinutes(1);
        Check(session.IsAuthorized(), "Forward clock movement rejected.");
        clock.Now = paired.AddSeconds(30);
        Check(!session.IsAuthorized(), "In-session rollback was accepted.");
        clock.Now = paired;
        session = new SessionAuthorization(id, paired, paired, clock);
        clock.Timestamp = SessionAuthorization.Lifetime.Ticks;
        Check(!session.IsAuthorized(), "Monotonic expiry was ignored.");

        CheckLanScope();
        CheckPairingWindow();
        CheckInputSession();
        Console.WriteLine("Passed M1 framing, authorization, input-session and pairing-window checks.");
    }

    private static void CheckLanScope()
    {
        var scope = new LanScope(IPAddress.Parse("10.9.8.5"), 28);
        Check(scope.AllowsPeer(IPAddress.Parse("10.9.8.2")), "On-link private peer rejected.");
        string[] forbidden = ["10.9.8.0", "10.9.8.5", "10.9.8.15", "10.9.8.16", "192.168.0.2",
            "127.0.0.1", "8.8.8.8", "224.0.0.251", "::1", "::ffff:10.9.8.2"];
        foreach (string address in forbidden)
            Check(!scope.AllowsPeer(IPAddress.Parse(address)), "Incorrect network scope admitted.");
        var moved = new LanScope(IPAddress.Parse("10.9.8.21"), 28);
        Check(!moved.AllowsPeer(IPAddress.Parse("10.9.8.2")), "Peer from the previous subnet was retained.");
        Check(moved.AllowsPeer(IPAddress.Parse("10.9.8.22")), "Peer on the current subnet was rejected.");
        Check(LanScope.IsPrivate(IPAddress.Parse("172.16.0.1")), "Private range was rejected.");
        Check(!LanScope.IsPrivate(IPAddress.Parse("172.32.0.1")), "Public range was admitted.");
    }

    private static void CheckInputSession()
    {
        var clock = new TestClock();
        var id = Guid.NewGuid();
        var input = new TestInput();
        var auth = new SessionAuthorization(id, clock.Now, clock.Now, clock);
        using var session = new DesktopInputSession(auth, input);
        session.SetFocus(1, true);
        Check(session.Receive(new KeyChange(id, 1, 1, CanonicalKey.Control, true)) == InputResult.Accepted,
            "Current focus key was refused.");
        session.SetFocus(2, true);
        Check(input.Released.SequenceEqual([CanonicalKey.Control]), "Focus change did not release held keys.");
        Check(session.Receive(new TextCommit(id, 2, 1, "synthetic")) == InputResult.StaleFocus,
            "Stale text reached input.");
        Check(session.Receive(new KeyChange(id, 3, 2, CanonicalKey.Shift, false)) == InputResult.Accepted &&
            input.KeyCalls == 1, "A key owned by another source was released.");
        Check(session.Receive(new KeyChange(id, 4, 2, CanonicalKey.Alt, true)) == InputResult.Accepted,
            "New focus input was refused.");
        session.SetPaused(true);
        Check(input.Released.SequenceEqual([CanonicalKey.Alt]), "Pause did not release owned keys.");
        session.SetPaused(false);
        Check(session.Receive(new TextCommit(id, 5, 2, "synthetic")) == InputResult.StaleFocus,
            "Pause reused the previous focus generation.");
        session.SetFocus(3, true);
        Check(session.Receive(new KeyChange(id, 6, 3, CanonicalKey.Shift, true)) == InputResult.Accepted,
            "Fresh focus was refused after resume.");
        clock.Now += SessionAuthorization.Lifetime;
        Check(!session.CheckAuthorization() && input.Released.SequenceEqual([CanonicalKey.Shift]),
            "Expiry did not release held keys.");
        Check(session.Receive(new Heartbeat(id, 7)) == InputResult.Disconnect,
            "Revoked session accepted traffic.");
    }

    private sealed class TestInput : ISessionInput
    {
        public int KeyCalls { get; private set; }
        public IReadOnlyList<CanonicalKey> Released { get; private set; } = [];
        public bool TryKey(long generation, CanonicalKey key, bool down)
        {
            KeyCalls++;
            return true;
        }
        public bool TryText(long generation, string text) =>
            throw new InvalidOperationException("Unexpected text reached input.");
        public void ReleaseKeys(IReadOnlyList<CanonicalKey> keys) => Released = keys;
    }

    private static void CheckPairingWindow()
    {
        var clock = new TestClock();
        var pairing = new PairingWindow(clock);
        Check(pairing.TryBegin() is null, "Pairing started without local consent.");
        var code = pairing.Arm();
        Check(code is { Length: 6 } && code.All(char.IsAsciiDigit), "Pairing code format is invalid.");
        using var active = pairing.TryBegin() ?? throw new InvalidOperationException("No pairing attempt admitted.");
        Check(active.Password.Span.SequenceEqual(code), "Local PAKE did not receive the code.");
        Check(pairing.TryBegin() is null, "Concurrent attempt was admitted.");
        Check(active.CompleteAuthenticated() && !active.CompleteAuthenticated(), "Code was reusable.");
        Check(active.Password.Span.SequenceEqual(new char[6]), "Password buffer survived confirmation.");
        Check(pairing.TryBegin() is null, "Pairing restarted after consuming the code.");

        pairing = new PairingWindow(clock);
        pairing.Arm();
        using var expired = pairing.TryBegin() ?? throw new InvalidOperationException("No pairing attempt admitted.");
        clock.Timestamp += PairingWindow.Lifetime.Ticks;
        Check(!expired.CompleteAuthenticated() && pairing.TryBegin() is null, "Expired window was accepted.");

        pairing = new PairingWindow(clock);
        for (var index = 0; index < PairingWindow.MaximumAttempts; index++)
        {
            Check(pairing.Arm() is not null, "Pairing window closed before the attempt limit.");
            using var failed = pairing.TryBegin() ?? throw new InvalidOperationException("No pairing attempt admitted.");
            failed.Dispose();
            Check(pairing.TryBegin() is null, "Global attempt throttle was bypassed.");
            pairing.Cancel();
            clock.Timestamp += TimeSpan.TicksPerSecond;
        }

        Check(pairing.Arm() is null && pairing.TryBegin() is null, "Rearming bypassed global cooldown.");
        clock.Timestamp += PairingWindow.Lifetime.Ticks;
        Check(pairing.Arm() is not null && pairing.TryBegin() is not null, "Global cooldown did not expire.");
        pairing.Cancel();
    }

    private static Task RejectAsync(string payload)
    {
        var bytes = Encoding.UTF8.GetBytes(payload);
        var frame = new byte[4 + bytes.Length];
        BinaryPrimitives.WriteInt32BigEndian(frame, bytes.Length);
        bytes.CopyTo(frame, 4);
        return RejectAsync(frame);
    }

    private static async Task RejectAsync(byte[] frame)
    {
        using var stream = new MemoryStream(frame);
        try
        {
            await FrameCodec.ReadAsync(stream);
        }
        catch (Exception error) when (error is InvalidDataException or EndOfStreamException)
        {
            return;
        }
        throw new InvalidOperationException("Malformed frame was accepted.");
    }

    private static void Check(bool condition, string failure)
    {
        if (!condition)
            throw new InvalidOperationException(failure);
    }

    private sealed class TestClock : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = new(2025, 1, 1, 0, 0, 0, TimeSpan.Zero);
        public long Timestamp { get; set; }
        public override DateTimeOffset GetUtcNow() => Now;
        public override long GetTimestamp() => Timestamp;
        public override long TimestampFrequency => TimeSpan.TicksPerSecond;
    }
}
