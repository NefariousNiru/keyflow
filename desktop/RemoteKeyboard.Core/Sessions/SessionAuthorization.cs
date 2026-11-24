using RemoteKeyboard.Core.Protocol;

namespace RemoteKeyboard.Core.Sessions;

/// <summary>
/// Authorization for one authenticated connection. Serialize calls on the session loop and
/// supply the persisted UTC high-water mark before processing any peer messages.
/// </summary>
public sealed class SessionAuthorization
{
    public static readonly TimeSpan Lifetime = TimeSpan.FromDays(30);

    private readonly TimeProvider clock;
    private readonly Guid sessionId;
    private readonly DateTimeOffset expiresAt;
    private readonly long startedAt;
    private readonly TimeSpan remainingAtStart;
    private long lastSequence;
    private bool revoked;

    public SessionAuthorization(
        Guid sessionId, DateTimeOffset pairedAt, DateTimeOffset persistedHighWater, TimeProvider clock)
    {
        ArgumentNullException.ThrowIfNull(clock);
        ArgumentOutOfRangeException.ThrowIfEqual(sessionId, Guid.Empty);
        this.clock = clock;
        this.sessionId = sessionId;
        expiresAt = pairedAt.Add(Lifetime);
        startedAt = clock.GetTimestamp();
        LastObservedUtc = persistedHighWater > pairedAt ? persistedHighWater : pairedAt;
        var now = clock.GetUtcNow();
        remainingAtStart = expiresAt - now;
        revoked = now < LastObservedUtc || remainingAtStart <= TimeSpan.Zero;
        if (!revoked)
            LastObservedUtc = now;
    }

    /// <summary>Latest accepted wall-clock reading, for persistence by the secure store.</summary>
    public DateTimeOffset LastObservedUtc { get; private set; }

    /// <summary>Immediately and permanently invalidates the connection.</summary>
    public void Revoke() => revoked = true;

    /// <summary>Checks authorization independently of message traffic for use by a session watchdog.</summary>
    public bool IsAuthorized()
    {
        if (revoked)
            return false;

        var now = clock.GetUtcNow();
        if (now < LastObservedUtc || now >= expiresAt ||
            clock.GetElapsedTime(startedAt) >= remainingAtStart)
        {
            Revoke();
            return false;
        }

        LastObservedUtc = now;
        return true;
    }

    /// <summary>
    /// Accepts only the next sequence for this session. Any sequencing or session mismatch
    /// revokes authorization. The transport must disconnect and release input on failure.
    /// </summary>
    public bool TryAccept(SessionMessage message)
    {
        if (!IsAuthorized())
            return false;
        if (message.SessionId != sessionId || lastSequence == long.MaxValue ||
            message.Sequence != lastSequence + 1)
        {
            Revoke();
            return false;
        }

        lastSequence = message.Sequence;
        return true;
    }
}
