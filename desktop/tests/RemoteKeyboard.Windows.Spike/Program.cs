using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

if (args is ["--interactive"])
{
    NativeInputCheck.Run();
    return;
}

using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
var request = new CertificateRequest("CN=localhost", key, HashAlgorithmName.SHA256);
var names = new SubjectAlternativeNameBuilder();
names.AddDnsName("localhost");
request.CertificateExtensions.Add(names.Build());
using var generated = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-5),
    DateTimeOffset.UtcNow.AddMinutes(5));

// Schannel needs an OS-backed private key. The temporary user key is deleted on disposal.
var pfx = generated.Export(X509ContentType.Pkcs12);
using var certificate = X509CertificateLoader.LoadPkcs12(pfx, null, X509KeyStorageFlags.UserKeySet);
CryptographicOperations.ZeroMemory(pfx);

await CheckTlsAsync(SslProtocols.Tls12);
if (OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000))
    await CheckTlsAsync(SslProtocols.Tls13);

async Task CheckTlsAsync(SslProtocols protocol)
{
    using var listener = new TcpListener(IPAddress.Loopback, 0);
    listener.Start();
    using var client = new TcpClient();
    await client.ConnectAsync((IPEndPoint)listener.LocalEndpoint, timeout.Token);
    using var server = await listener.AcceptTcpClientAsync(timeout.Token);
    using var clientTls = new SslStream(client.GetStream());
    using var serverTls = new SslStream(server.GetStream());

    var chain = new X509ChainPolicy
    {
        TrustMode = X509ChainTrustMode.CustomRootTrust,
        RevocationMode = X509RevocationMode.NoCheck
    };
    chain.CustomTrustStore.Add(certificate);

    await Task.WhenAll(
        serverTls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
        {
            ServerCertificate = certificate,
            EnabledSslProtocols = protocol
        }, timeout.Token),
        clientTls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
        {
            TargetHost = "localhost",
            EnabledSslProtocols = protocol,
            CertificateChainPolicy = chain
        }, timeout.Token));

    var suite = clientTls.NegotiatedCipherSuite;
    var allowed = protocol == SslProtocols.Tls12
        ? suite is TlsCipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
            or TlsCipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
        : suite is TlsCipherSuite.TLS_AES_128_GCM_SHA256
            or TlsCipherSuite.TLS_AES_256_GCM_SHA384
            or TlsCipherSuite.TLS_CHACHA20_POLY1305_SHA256;
    if (clientTls.SslProtocol != protocol || !allowed || serverTls.NegotiatedCipherSuite != suite)
        throw new InvalidOperationException($"Unexpected TLS negotiation: {clientTls.SslProtocol}, {suite}");

    Console.WriteLine($"Loopback negotiated {protocol} with {suite}.");
}
