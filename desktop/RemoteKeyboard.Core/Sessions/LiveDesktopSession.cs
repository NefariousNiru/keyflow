using System.Threading.Channels;
using RemoteKeyboard.Core.Pairing;
using RemoteKeyboard.Core.Protocol;

namespace RemoteKeyboard.Core.Sessions;

/// <summary>Runs one authenticated desktop session, without carrying queued input across connections.</summary>
public static class LiveDesktopSession
{
    public static readonly TimeSpan HeartbeatInterval = TimeSpan.FromSeconds(10);
    public static readonly TimeSpan WatchdogInterval = TimeSpan.FromSeconds(35);

    public static async Task RunAsync(
        DesktopTrust trust, TrustedConnection connection, ISessionInput input, Func<bool> linkAllowed,
        bool syntheticFocus, CancellationToken cancellationToken, ChannelReader<bool>? focusEvents = null)
    {
        using var stopped = CancellationTokenSource.CreateLinkedTokenSource(connection.Cancellation, cancellationToken);
        var token = stopped.Token;
        var id = Guid.NewGuid();
        using var session = new DesktopInputSession(
            new SessionAuthorization(id, connection.Peer.PairedAt, connection.Peer.HighWater, TimeProvider.System), input);
        var sessionGate = new object();
        long focusGeneration = 1;
        session.SetFocus(focusGeneration, syntheticFocus);
        if (!Allowed())
            return;

        var stream = connection.Stream;
        long sequence = 1;
        await FrameCodec.WriteAsync(stream, new Hello(ProtocolCapabilities.Keys | ProtocolCapabilities.UnicodeText), token);
        await FrameCodec.WriteAsync(stream, new FocusState(id, sequence++, 1, syntheticFocus), token);
        await stream.FlushAsync(token);
        if (await ReadAsync(stream, token) is not Hello)
            return;

        using var heartbeat = new PeriodicTimer(HeartbeatInterval);
        Task writer = SendHeartbeatAsync();
        Task reader = ReadInputAsync();
        try
        {
            await Task.WhenAny(writer, reader).ConfigureAwait(false);
        }
        finally
        {
            await stopped.CancelAsync().ConfigureAwait(false);
            try
            {
                await Task.WhenAll(writer, reader).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested)
            {
                // The other direction ended and canceled this one.
            }
        }

        async Task SendHeartbeatAsync()
        {
            var tick = heartbeat.WaitForNextTickAsync(token).AsTask();
            var focus = focusEvents?.WaitToReadAsync(token).AsTask();
            while (true)
            {
                if (focus is not null && await Task.WhenAny(tick, focus).ConfigureAwait(false) == focus)
                {
                    if (!await focus.ConfigureAwait(false))
                    {
                        focus = null;
                        continue;
                    }
                    if (!Allowed())
                        return;
                    if (focusEvents!.TryRead(out bool active))
                    {
                        lock (sessionGate)
                            session.SetFocus(++focusGeneration, active);
                        await FrameCodec.WriteAsync(stream, new FocusState(id, sequence++, focusGeneration, active), token);
                        await stream.FlushAsync(token);
                    }
                    focus = focusEvents.WaitToReadAsync(token).AsTask();
                    continue;
                }

                if (!await tick.ConfigureAwait(false) || !Allowed())
                    return;
                await FrameCodec.WriteAsync(stream, new Heartbeat(id, sequence++), token).ConfigureAwait(false);
                await stream.FlushAsync(token).ConfigureAwait(false);
                tick = heartbeat.WaitForNextTickAsync(token).AsTask();
            }
        }

        async Task ReadInputAsync()
        {
            int received = 0;
            long started = TimeProvider.System.GetTimestamp();
            while (await ReadAsync(stream, token).ConfigureAwait(false) is SessionMessage message)
            {
                if (TimeProvider.System.GetElapsedTime(started) >= TimeSpan.FromSeconds(1))
                {
                    received = 0;
                    started = TimeProvider.System.GetTimestamp();
                }
                if (++received > 120 || !Allowed())
                    return;
                lock (sessionGate)
                    if (session.Receive(message) == InputResult.Disconnect)
                        return;
            }
        }

        bool Allowed() => linkAllowed() && trust.CheckAuthorization(connection);
    }

    private static async ValueTask<ProtocolMessage?> ReadAsync(Stream stream, CancellationToken token)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
        timeout.CancelAfter(WatchdogInterval);
        try
        {
            return await FrameCodec.ReadAsync(stream, timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!token.IsCancellationRequested)
        {
            throw new TimeoutException("The authenticated peer did not send a complete frame.");
        }
    }
}
