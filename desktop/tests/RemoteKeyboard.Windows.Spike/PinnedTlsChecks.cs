using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using RemoteKeyboard.Core.Protocol;
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
