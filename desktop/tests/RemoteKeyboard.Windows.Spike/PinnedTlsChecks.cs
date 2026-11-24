using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using RemoteKeyboard.Core.Pairing;
using RemoteKeyboard.Core.Protocol;
using RemoteKeyboard.Core.Sessions;
using RemoteKeyboard.Core.Transport;

internal static class PinnedTlsChecks
{
    public static async Task RunAsync()
    {
        using var clientCertificate = CreateCertificate("Keyflow test client");
        using var serverCertificate = CreateCertificate("Keyflow test server");
        using var stranger = CreateCertificate("Keyflow test unpaired peer");

        await ExchangeAsync(clientCertificate, serverCertificate, serverCertificate, clientCertificate, true);
        await ExchangeAsync(clientCertificate, serverCertificate, stranger, clientCertificate, false);
        await ExchangeAsync(clientCertificate, serverCertificate, serverCertificate, stranger, false);
        Console.WriteLine("Pinned TLS accepted paired identities and rejected incorrect pins on each side.");
        await LinkPolicyChecksAsync(clientCertificate, serverCertificate, watchdog: false);
        await LinkPolicyChecksAsync(clientCertificate, serverCertificate, watchdog: true);
    }

    private static async Task ExchangeAsync(
        X509Certificate2 client, X509Certificate2 server, X509Certificate2 expectedServer,
        X509Certificate2 expectedClient, bool shouldSucceed)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        using var clientConnection = new TcpClient();
        await clientConnection.ConnectAsync((IPEndPoint)listener.LocalEndpoint, timeout.Token);
        using var serverConnection = await listener.AcceptTcpClientAsync(timeout.Token);

        var serverTask = PinnedTls.AuthenticateAsync(serverConnection.GetStream(), true, server,
            PinnedTls.ComputeSpkiPin(expectedClient), "localhost", TimeProvider.System, timeout.Token);
        var clientTask = PinnedTls.AuthenticateAsync(clientConnection.GetStream(), false, client,
            PinnedTls.ComputeSpkiPin(expectedServer), "localhost", TimeProvider.System, timeout.Token);
        try
        {
            await Task.WhenAll(serverTask, clientTask);
            if (!shouldSucceed)
                throw new InvalidOperationException("Incorrect TLS pin was accepted.");

            var hello = new Hello(ProtocolCapabilities.Keys);
            await FrameCodec.WriteAsync(clientTask.Result, hello, timeout.Token);
            if (!hello.Equals(await FrameCodec.ReadAsync(serverTask.Result, timeout.Token)))
                throw new InvalidOperationException("Authenticated TLS frame did not round trip.");
            Console.WriteLine($"Production transport negotiated {clientTask.Result.SslProtocol} with {clientTask.Result.NegotiatedCipherSuite}.");
        }
        catch (Exception error) when (!shouldSucceed && (error is AuthenticationException or IOException) &&
            (clientTask.Exception?.InnerException is AuthenticationException ||
             serverTask.Exception?.InnerException is AuthenticationException))
        {
            // A peer can surface an alert as an I/O error. At least one side must reject authentication.
        }
        finally
        {
            if (clientTask.IsCompletedSuccessfully)
                await clientTask.Result.DisposeAsync();
            if (serverTask.IsCompletedSuccessfully)
                await serverTask.Result.DisposeAsync();
        }
    }

    private static async Task LinkPolicyChecksAsync(X509Certificate2 client, X509Certificate2 server, bool watchdog)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(watchdog ? 45 : 10));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        using var phone = new TcpClient();
        await phone.ConnectAsync((IPEndPoint)listener.LocalEndpoint, timeout.Token);
        using var accepted = await listener.AcceptTcpClientAsync(timeout.Token);

        var now = DateTimeOffset.UtcNow;
        var trust = new DesktopTrust(
            new TestStore(new TrustedPeer(Convert.ToHexStringLower(PinnedTls.ComputeSpkiPin(client)), now, now)),
            new PairingWindow(TimeProvider.System), TimeProvider.System);
        var desktopTask = trust.ReconnectAsync(accepted.GetStream(), server, timeout.Token);
        await using var phoneTls = await PinnedTls.AuthenticateAsync(phone.GetStream(), false, client,
            PinnedTls.ComputeSpkiPin(server), "localhost", TimeProvider.System, timeout.Token);
        await phoneTls.ReadExactlyAsync(new byte[8], timeout.Token);
        await using var desktop = await desktopTask;
        var input = new TestInput();
        var route = 1;
        var session = LiveDesktopSession.RunAsync(trust, desktop, input,
            () => Volatile.Read(ref route) == 1, syntheticFocus: true, timeout.Token);
        if (await FrameCodec.ReadAsync(phoneTls, timeout.Token) is not Hello ||
            await FrameCodec.ReadAsync(phoneTls, timeout.Token) is not FocusState focus)
            throw new InvalidOperationException("Missing authenticated focus.");

        await FrameCodec.WriteAsync(phoneTls, new Hello(ProtocolCapabilities.Keys), timeout.Token);
        await FrameCodec.WriteAsync(phoneTls,
            new KeyChange(focus.SessionId, 1, focus.Generation, CanonicalKey.Control, true), timeout.Token);
        await phoneTls.FlushAsync(timeout.Token);
        await input.KeyDown.Task.WaitAsync(timeout.Token);
        if (watchdog)
        {
            if (await FrameCodec.ReadAsync(phoneTls, timeout.Token) is not Heartbeat { Sequence: 2 })
                throw new InvalidOperationException("The desktop did not send a heartbeat.");
            try
            {
                await session.WaitAsync(timeout.Token);
                throw new InvalidOperationException("A silent peer did not time out.");
            }
            catch (TimeoutException)
            {
                if (!input.Released.SequenceEqual([CanonicalKey.Control]))
                    throw new InvalidOperationException("The watchdog did not release the session's held key.");
                Console.WriteLine("Heartbeat and watchdog timeout passed.");
            }
            return;
        }
        Volatile.Write(ref route, 0);
        await FrameCodec.WriteAsync(phoneTls,
            new TextCommit(focus.SessionId, 2, focus.Generation, "synthetic"), timeout.Token);
        await phoneTls.FlushAsync(timeout.Token);
        await session.WaitAsync(timeout.Token);
        if (!input.Released.SequenceEqual([CanonicalKey.Control]))
            throw new InvalidOperationException("Route loss did not release the session's held key.");
        Console.WriteLine("Authenticated link-policy loss blocked input and released its held key.");
    }

    private sealed class TestStore(TrustedPeer peer) : ITrustedPeerStore
    {
        private TrustedPeer? current = peer;
        public TrustedPeer? Load() => current;
        public void Save(TrustedPeer record) => current = record;
        public void Clear() => current = null;
    }

    private sealed class TestInput : ISessionInput
    {
        public TaskCompletionSource KeyDown { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public IReadOnlyList<CanonicalKey> Released { get; private set; } = [];
        public bool TryKey(long generation, CanonicalKey key, bool down)
        {
            KeyDown.TrySetResult();
            return true;
        }
        public bool TryText(long generation, string text) =>
            throw new InvalidOperationException("A blocked route reached text input.");
        public void ReleaseKeys(IReadOnlyList<CanonicalKey> keys) => Released = keys;
    }

    private static X509Certificate2 CreateCertificate(string commonName)
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest($"CN={commonName}", key, HashAlgorithmName.SHA256);
        using var generated = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-1),
            DateTimeOffset.UtcNow.AddMinutes(5));
        // Schannel requires an OS-backed key. UserKeySet without PersistKeySet deletes it on disposal.
        var pfx = generated.Export(X509ContentType.Pkcs12);
        try
        {
            return X509CertificateLoader.LoadPkcs12(pfx, null, X509KeyStorageFlags.UserKeySet);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(pfx);
        }
    }
}
