using System.Buffers.Binary;
using System.Net.Security;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using RemoteKeyboard.Core.Transport;

namespace RemoteKeyboard.Core.Pairing;

/// <summary>Persist precisely one peer and the highest accepted wall-clock reading.</summary>
public sealed record TrustedPeer(string Pin, DateTimeOffset PairedAt, DateTimeOffset HighWater)
{
    public byte[] DecodePin()
    {
        if (Pin.Length != 64 || HighWater < PairedAt)
            throw new InvalidDataException("Invalid stored pairing record.");
        return Convert.FromHexString(Pin);
    }

    public bool IsValid(DateTimeOffset now) =>
        HighWater >= PairedAt && now >= HighWater && now - PairedAt < TimeSpan.FromDays(30);
}

public interface ITrustedPeerStore
{
    TrustedPeer? Load();
    void Save(TrustedPeer peer);
    void Clear();
}

/// <summary>Desktop pairing and reconnect coordinator for one explicitly selected local endpoint.</summary>
public sealed class DesktopTrust(ITrustedPeerStore store, PairingWindow window, TimeProvider clock)
{
    private readonly object gate = new();
    private CancellationTokenSource? live;
    private long revision;

    /// <summary>Revokes the stored peer and closes its active connection through the session cancellation token.</summary>
    public void Revoke()
    {
        lock (gate)
        {
            revision++;
            window.Cancel();
            CancelLive();
            store.Clear();
        }
    }

    public async Task<TrustedConnection> PairAsync(
        Stream stream, X509Certificate2 identity, CancellationToken cancellationToken = default)
    {
        using var attempt = window.TryBegin();
        if (attempt is null)
        {
            await stream.DisposeAsync().ConfigureAwait(false);
            throw new AuthenticationException("Pairing is unavailable.");
        }

        SslStream? tls = null;
        try
        {
            var local = PairingIdentity.Create(true, PinnedTls.ComputeSpkiPin(identity));
            var peer = await PairingExchange.ConfirmAsync(stream, local, true, attempt.Password, cancellationToken)
                .ConfigureAwait(false);
            tls = await PinnedTls.ConfirmPairingIdentityAsync(stream, identity, peer, clock, cancellationToken)
                .ConfigureAwait(false);
            using var certificate = new X509Certificate2(tls.RemoteCertificate!);
            var pin = Convert.ToHexStringLower(PinnedTls.ComputeSpkiPin(certificate));
            TrustedPeer record;
            long stamp;
            lock (gate)
            {
                var now = clock.GetUtcNow();
                var prior = store.Load();
                if ((prior is not null && now < prior.HighWater) || !attempt.CompleteAuthenticated())
                    throw new AuthenticationException("Pairing window or clock is invalid.");

                now = DateTimeOffset.FromUnixTimeMilliseconds(now.ToUnixTimeMilliseconds());
                record = new TrustedPeer(pin, now, now);
                stamp = revision;
            }

            // Offer the authenticated grant first. The phone acknowledges only after its
            // local write succeeds. Application traffic remains gated on the desktop commit.
            using var acknowledgementTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            acknowledgementTimeout.CancelAfter(TimeSpan.FromSeconds(10));
            var grant = new byte[8];
            BinaryPrimitives.WriteInt64BigEndian(grant, record.PairedAt.ToUnixTimeMilliseconds());
            await tls.WriteAsync(grant, acknowledgementTimeout.Token).ConfigureAwait(false);
            await tls.FlushAsync(acknowledgementTimeout.Token).ConfigureAwait(false);
            var acknowledgement = new byte[1];
            await tls.ReadExactlyAsync(acknowledgement, acknowledgementTimeout.Token).ConfigureAwait(false);
            if (acknowledgement[0] != 1)
                throw new AuthenticationException("Pairing acknowledgement was invalid.");

            lock (gate)
            {
                acknowledgementTimeout.Token.ThrowIfCancellationRequested();
                var now = clock.GetUtcNow();
                var prior = store.Load();
                if (stamp != revision || now < record.PairedAt || (prior is not null && now < prior.HighWater))
                    throw new AuthenticationException("Pairing changed before trust could be stored.");

                record = record with { HighWater = now };
                store.Save(record);
                revision++;
                CancelLive();
                var current = BeginLive(cancellationToken, record);
                return new TrustedConnection(tls, record, current.Token, clock);
            }
        }
        catch
        {
            if (tls is not null)
                await tls.DisposeAsync().ConfigureAwait(false);
            else
                await stream.DisposeAsync().ConfigureAwait(false);
            throw;
        }
    }

    public async Task<TrustedConnection> ReconnectAsync(
        Stream stream, X509Certificate2 identity, CancellationToken cancellationToken = default)
    {
        SslStream? tls = null;
        try
        {
            TrustedPeer peer;
            lock (gate)
                peer = RequirePeer();
            tls = await PinnedTls.AuthenticateAsync(stream, true, identity, peer.DecodePin(), "keyflow",
                clock, cancellationToken).ConfigureAwait(false);
            byte[] grant;
            CancellationToken token;
            lock (gate)
            {
                var current = RequirePeer();
                if (current.Pin != peer.Pin || current.PairedAt != peer.PairedAt)
                    throw new AuthenticationException("The pairing changed during reconnect.");

                peer = current with { HighWater = clock.GetUtcNow() };
                if (!peer.IsValid(peer.HighWater) || peer.HighWater < current.HighWater)
                    throw new AuthenticationException("Pairing expired during reconnect.");
                store.Save(peer);
                CancelLive();
                token = BeginLive(cancellationToken, peer).Token;
                grant = new byte[8];
                BinaryPrimitives.WriteInt64BigEndian(grant, peer.PairedAt.ToUnixTimeMilliseconds());
            }
            await tls.WriteAsync(grant, token).ConfigureAwait(false);
            await tls.FlushAsync(token).ConfigureAwait(false);
            return new TrustedConnection(tls, peer, token, clock);
        }
        catch
        {
            if (tls is not null)
                await tls.DisposeAsync().ConfigureAwait(false);
            else
                await stream.DisposeAsync().ConfigureAwait(false);
            throw;
        }
    }

    /// <summary>Call from the serial session loop before dispatching messages and on watchdog ticks.</summary>
    public bool CheckAuthorization(TrustedConnection connection)
    {
        lock (gate)
        {
            var current = store.Load();
            var now = clock.GetUtcNow();
            if (connection.Cancellation.IsCancellationRequested || current is null ||
                current.Pin != connection.Peer.Pin || current.PairedAt != connection.Peer.PairedAt ||
                !current.IsValid(now) || !connection.IsInsideLifetime(clock))
            {
                if (live is not null && connection.Cancellation == live.Token)
                    CancelLive();
                return false;
            }
            if (current.HighWater != now)
                store.Save(current with { HighWater = now });
            return true;
        }
    }

    private TrustedPeer RequirePeer()
    {
        var peer = store.Load();
        if (peer is null || !peer.IsValid(clock.GetUtcNow()))
            throw new AuthenticationException("No active pairing.");
        return peer;
    }

    private CancellationTokenSource BeginLive(CancellationToken token, TrustedPeer peer)
    {
        var source = CancellationTokenSource.CreateLinkedTokenSource(token);
        var remaining = peer.PairedAt.AddDays(30) - clock.GetUtcNow();
        source.CancelAfter(remaining > TimeSpan.Zero ? remaining : TimeSpan.Zero);
        return live = source;
    }

    private void CancelLive()
    {
        if (live is null)
            return;
        live.Cancel();
        live.Dispose();
        live = null;
    }
}

public sealed class TrustedConnection : IAsyncDisposable
{
    private readonly long start;
    private readonly TimeSpan remaining;
    private readonly CancellationTokenRegistration closeOnCancel;

    internal TrustedConnection(SslStream stream, TrustedPeer peer, CancellationToken cancellation, TimeProvider clock)
    {
        Stream = stream;
        Peer = peer;
        Cancellation = cancellation;
        start = clock.GetTimestamp();
        remaining = peer.PairedAt.AddDays(30) - clock.GetUtcNow();
        closeOnCancel = cancellation.Register(static stream => ((SslStream)stream!).Dispose(), stream);
    }

    public SslStream Stream { get; }
    public TrustedPeer Peer { get; }
    public CancellationToken Cancellation { get; }

    internal bool IsInsideLifetime(TimeProvider clock) => clock.GetElapsedTime(start) < remaining;
    public async ValueTask DisposeAsync()
    {
        await closeOnCancel.DisposeAsync().ConfigureAwait(false);
        await Stream.DisposeAsync().ConfigureAwait(false);
    }
}
