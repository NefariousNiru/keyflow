using System.IO;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using RemoteKeyboard.Core.Pairing;

namespace RemoteKeyboard.Windows;

/// <summary>Per-user identity and pairing files protected by Windows DPAPI CurrentUser.</summary>
public sealed class WindowsTrustStore(string directory) : ITrustedPeerStore
{
    private readonly string root = Path.GetFullPath(directory);

    public X509Certificate2 LoadOrCreateIdentity()
    {
        const string name = "identity.bin";
        var pfx = Read(name);
        try
        {
            if (pfx is null)
            {
                using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
                var request = new CertificateRequest("CN=Keyflow desktop", key, HashAlgorithmName.SHA256);
                request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
                using var certificate = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-10),
                    DateTimeOffset.UtcNow.AddYears(5));
                pfx = certificate.Export(X509ContentType.Pkcs12);
                Write(name, pfx);
            }

            // Schannel requires a user-backed private key handle. The imported key is deleted on disposal.
            return X509CertificateLoader.LoadPkcs12(pfx, null, X509KeyStorageFlags.UserKeySet);
        }
        finally
        {
            if (pfx is not null)
                CryptographicOperations.ZeroMemory(pfx);
        }
    }

    public TrustedPeer? Load()
    {
        var bytes = Read("peer.bin");
        if (bytes is null)
            return null;
        try
        {
            var peer = JsonSerializer.Deserialize<TrustedPeer>(bytes)
                ?? throw new InvalidDataException("Missing pairing record.");
            peer.DecodePin();
            return peer;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(bytes);
        }
    }

    public void Save(TrustedPeer peer)
    {
        peer.DecodePin();
        var bytes = JsonSerializer.SerializeToUtf8Bytes(peer);
        try
        {
            Write("peer.bin", bytes);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(bytes);
        }
    }

    public void Clear() => File.Delete(Path.Combine(root, "peer.bin"));

    private byte[]? Read(string name)
    {
        try
        {
            using var stream = File.OpenRead(Path.Combine(root, name));
            if (stream.Length is < 1 or > 32768)
                throw new InvalidDataException("Invalid protected record length.");
            var data = new byte[(int)stream.Length];
            stream.ReadExactly(data);
            return ProtectedData.Unprotect(data, null, DataProtectionScope.CurrentUser);
        }
        catch (FileNotFoundException)
        {
            return null;
        }
        catch (DirectoryNotFoundException)
        {
            return null;
        }
    }

    private void Write(string name, byte[] data)
    {
        Directory.CreateDirectory(root);
        var encrypted = ProtectedData.Protect(data, null, DataProtectionScope.CurrentUser);
        var scratch = Path.Combine(root, Guid.NewGuid().ToString("N") + ".tmp");
        try
        {
            using (var output = File.Open(scratch, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                output.Write(encrypted);
                output.Flush(flushToDisk: true);
            }
            File.Move(scratch, Path.Combine(root, name), overwrite: true);
        }
        finally
        {
            File.Delete(scratch);
        }
    }
}
