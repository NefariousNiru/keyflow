using System.IO;
using System.Net;
using System.Net.Sockets;
using RemoteKeyboard.Core.Pairing;
using RemoteKeyboard.Core.Protocol;
using RemoteKeyboard.Core.Sessions;
using RemoteKeyboard.Windows;

internal static class TrustEndpoint
{
    public static async Task RunAsync(string directory, string mode)
    {
        if (!System.Text.RegularExpressions.Regex.IsMatch(Path.GetFileName(directory), "^keyflow-test-[0-9a-f]{16}$"))
            throw new ArgumentException("A dedicated trust-test directory is required.", nameof(directory));

        var clock = new EndpointClock();
        var store = new WindowsTrustStore(directory);
        var window = new PairingWindow(clock);
        var trust = new DesktopTrust(store, window, clock);
        if (mode == "revoke-before")
            trust.Revoke();
        if (mode == "rollback")
            clock.Offset = TimeSpan.FromMinutes(-1);
        if (mode == "expired")
            clock.Offset = TimeSpan.FromDays(30);

        if (mode == "corrupt-store")
        {
            var path = Path.Combine(directory, "peer.bin");
            var data = File.ReadAllBytes(path);
            var modified = (byte[])data.Clone();
            modified[modified.Length / 2] ^= 1;
            File.WriteAllBytes(path, modified);
            try
            {
                _ = store.Load();
                throw new InvalidOperationException("DPAPI accepted modified trust data.");
            }
            catch (System.Security.Cryptography.CryptographicException)
            {
                Console.WriteLine("REJECTED");
                return;
            }
            finally
            {
                File.WriteAllBytes(path, data);
            }
        }

        using var identity = (mode == "wrong-desktop"
            ? new WindowsTrustStore(Path.Combine(directory, "other")) : store).LoadOrCreateIdentity();
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start(1);
        Console.WriteLine($"PORT {((IPEndPoint)listener.LocalEndpoint).Port}");
        bool pairing = mode is "pair" or "no-ack" or "wrong-code" or "pair-wrong-key" or "role-substitution" or "malformed-pairing";
        if (pairing)
            Console.WriteLine($"OTP {window.Arm()}");

        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(35));
        using var client = await listener.AcceptTcpClientAsync(deadline.Token);
        listener.Stop();
        if (mode is "no-ack" or "wrong-code" or "pair-wrong-key" or "role-substitution" or "malformed-pairing" or "revoked" or "revoke-before" or "expired" or "rollback" or "wrong-identity" or "wrong-desktop")
        {
            var prior = store.Load();
            try
            {
                await using var unexpected = await OpenAsync();
                throw new InvalidOperationException("An unauthorized client connected.");
            }
            catch (Exception error) when (error is InvalidDataException or IOException or System.Security.Authentication.AuthenticationException)
            {
                if (store.Load() != prior)
                    throw new InvalidOperationException("A failed authentication changed desktop trust.");
                Console.WriteLine("REJECTED");
                return;
            }
        }

        await using var connection = await OpenAsync();
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(deadline.Token, connection.Cancellation);
        var token = linked.Token;
        Console.Error.WriteLine($"Test transport negotiated {connection.Stream.SslProtocol} with {connection.Stream.NegotiatedCipherSuite}.");
        var sessionId = Guid.NewGuid();
        using var active = new DesktopInputSession(
            new SessionAuthorization(sessionId, connection.Peer.PairedAt, connection.Peer.HighWater, clock),
            new EndpointInput());
        active.SetFocus(1, true);
        await FrameCodec.WriteAsync(connection.Stream, new Hello(ProtocolCapabilities.UnicodeText), token);
        await FrameCodec.WriteAsync(connection.Stream, new FocusState(sessionId, 1, 1, true), token);
        if (await FrameCodec.ReadAsync(connection.Stream, token) is not Hello { Capabilities: ProtocolCapabilities.UnicodeText } ||
            await FrameCodec.ReadAsync(connection.Stream, token) is not TextCommit text ||
            !trust.CheckAuthorization(connection) || active.Receive(text) != InputResult.Accepted)
            throw new InvalidOperationException("Authenticated application flow failed.");
        await FrameCodec.WriteAsync(connection.Stream, new Heartbeat(sessionId, 2), token);
        if (mode == "replay")
        {
            if (await FrameCodec.ReadAsync(connection.Stream, token) is not TextCommit replay ||
                active.Receive(replay) != InputResult.Disconnect)
                throw new InvalidOperationException("Replayed application sequence was accepted.");
            Console.WriteLine("REJECTED");
            return;
        }
        if (mode == "expire-active")
        {
            clock.Offset = TimeSpan.FromDays(30);
            if (trust.CheckAuthorization(connection) || !connection.Cancellation.IsCancellationRequested)
                throw new InvalidOperationException("Active authorization survived expiry.");
        }
        if (mode == "revoke-active")
        {
            trust.Revoke();
            if (trust.CheckAuthorization(connection) || !connection.Cancellation.IsCancellationRequested)
                throw new InvalidOperationException("Active authorization survived revocation.");
            await connection.Stream.DisposeAsync();
        }
        Console.WriteLine("AUTHENTICATED");

        Task<TrustedConnection> OpenAsync() => pairing
            ? trust.PairAsync(client.GetStream(), identity, deadline.Token)
            : trust.ReconnectAsync(client.GetStream(), identity, deadline.Token);
    }

    private sealed class EndpointInput : ISessionInput
    {
        public bool TryKey(long generation, CanonicalKey key, bool down) => false;
        public bool TryText(long generation, string text) => generation == 1 && text == "synthetic";
        public void ReleaseKeys(IReadOnlyList<CanonicalKey> keys) { }
    }

    private sealed class EndpointClock : TimeProvider
    {
        public TimeSpan Offset { get; set; }
        public override DateTimeOffset GetUtcNow() => DateTimeOffset.UtcNow + Offset;
    }
}
