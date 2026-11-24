using RemoteKeyboard.Core.Pairing;
using System.Net.Security;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace RemoteKeyboard.Core.Transport;

/// <summary>Authenticates an already-paired peer using its stored SHA-256 SPKI pin.</summary>
public static class PinnedTls
{
    /// <summary>
    /// Wraps a connected stream and takes ownership after validating the arguments. The caller supplies only credentials
    /// obtained through authenticated pairing and enforces interface and endpoint policy before connecting.
    /// </summary>
    public static Task<SslStream> AuthenticateAsync(
        Stream connectedStream, bool server, X509Certificate2 localCertificate,
        ReadOnlyMemory<byte> peerSpkiPin, string targetHost, TimeProvider clock,
        CancellationToken cancellationToken = default)
    {
        if (peerSpkiPin.Length != SHA256.HashSizeInBytes)
            throw new ArgumentException("TLS requires a SHA-256 peer pin.");
        var pin = peerSpkiPin.ToArray();
        return AuthenticateCoreAsync(connectedStream, server, localCertificate,
            actual => CryptographicOperations.FixedTimeEquals(pin, actual), targetHost, clock, cancellationToken);
    }

    internal static Task<SslStream> ConfirmPairingIdentityAsync(
        Stream stream, X509Certificate2 localCertificate, PairingIdentity peer, TimeProvider clock,
        CancellationToken cancellationToken) =>
        AuthenticateCoreAsync(stream, true, localCertificate, actual => peer.Matches(actual),
            "keyflow", clock, cancellationToken);

    private static async Task<SslStream> AuthenticateCoreAsync(
        Stream connectedStream, bool server, X509Certificate2 localCertificate,
        Func<byte[], bool> verifyPin, string targetHost, TimeProvider clock, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(connectedStream);
        ArgumentNullException.ThrowIfNull(localCertificate);
        ArgumentNullException.ThrowIfNull(clock);
        ArgumentException.ThrowIfNullOrWhiteSpace(targetHost);
        if (!localCertificate.HasPrivateKey)
            throw new ArgumentException("TLS requires a local private key.");

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(10));
        var protocols = SslProtocols.Tls12;
        if (OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000))
            protocols |= SslProtocols.Tls13;

        var tls = new SslStream(connectedStream, leaveInnerStreamOpen: false,
            (_, peer, _, _) => MatchesPin(peer, verifyPin, clock.GetUtcNow()));
        try
        {
            if (server)
            {
                await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
                {
                    ServerCertificate = localCertificate,
                    ClientCertificateRequired = true,
                    EnabledSslProtocols = protocols,
                    AllowRenegotiation = false,
                    AllowTlsResume = false
                }, timeout.Token).ConfigureAwait(false);
            }
            else
            {
                await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
                {
                    TargetHost = targetHost,
                    ClientCertificates = new X509CertificateCollection { localCertificate },
                    EnabledSslProtocols = protocols,
                    AllowRenegotiation = false,
                    AllowTlsResume = false
                }, timeout.Token).ConfigureAwait(false);
            }

            if (!tls.IsAuthenticated || !tls.IsEncrypted || !tls.IsMutuallyAuthenticated ||
                !AllowedCipher(tls.SslProtocol, tls.NegotiatedCipherSuite))
                throw new AuthenticationException("Keyflow TLS policy rejected the connection.");

            return tls;
        }
        catch
        {
            await tls.DisposeAsync().ConfigureAwait(false);
            throw;
        }
    }

    /// <summary>Computes the interoperable pin of a leaf certificate public key.</summary>
    public static byte[] ComputeSpkiPin(X509Certificate2 certificate) =>
        SHA256.HashData(certificate.PublicKey.ExportSubjectPublicKeyInfo());

    private static bool MatchesPin(X509Certificate? peer, Func<byte[], bool> verifyPin, DateTimeOffset now)
    {
        if (peer is null)
            return false;
        using var certificate = new X509Certificate2(peer);
        // The pairing pin is the identity authority, including for self-signed certificates.
        return now >= certificate.NotBefore.ToUniversalTime() && now <= certificate.NotAfter.ToUniversalTime() &&
            verifyPin(ComputeSpkiPin(certificate));
    }

    private static bool AllowedCipher(SslProtocols protocol, TlsCipherSuite suite) => protocol switch
    {
        SslProtocols.Tls12 => suite is TlsCipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
            or TlsCipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
            or TlsCipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
            or TlsCipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
            or TlsCipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
            or TlsCipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        SslProtocols.Tls13 => suite is TlsCipherSuite.TLS_AES_128_GCM_SHA256
            or TlsCipherSuite.TLS_AES_256_GCM_SHA384
            or TlsCipherSuite.TLS_CHACHA20_POLY1305_SHA256,
        _ => false
    };
}
