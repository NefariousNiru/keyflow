using System.Globalization;
using System.Security.Cryptography;

namespace RemoteKeyboard.Core.Pairing;

/// <summary>Desktop-owned authorization to start a bounded OTP-authenticated exchange.</summary>
public sealed class PairingWindow(TimeProvider clock)
{
    public static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(2);
    public const int MaximumAttempts = 5;

    private static readonly TimeSpan MinimumAttemptInterval = TimeSpan.FromSeconds(1);
    private readonly object gate = new();
    private readonly TimeProvider clock = clock ?? throw new ArgumentNullException(nameof(clock));
    private readonly Queue<long> recentAttempts = new();
    private char[]? code;
    private long openedAt;
    private long? lastAttemptAt;
    private long? cooldownAt;
    private int attempts;
    private Attempt? activeAttempt;

    /// <summary>Call only in response to a local user's explicit request to pair.</summary>
    public string? Arm()
    {
        lock (gate)
        {
            var now = clock.GetTimestamp();
            Expire(now);
            if (activeAttempt is not null || InCooldown(now))
                return null;
            if (code is not null)
                return new string(code);

            code = RandomNumberGenerator.GetInt32(1_000_000).ToString("D6", CultureInfo.InvariantCulture).ToCharArray();
            openedAt = now;
            attempts = 0;
            return new string(code);
        }
    }

    /// <summary>Consumes an attempt before crypto work. The caller must dispose an abandoned attempt.</summary>
    public Attempt? TryBegin()
    {
        lock (gate)
        {
            var now = clock.GetTimestamp();
            Expire(now);
            if (code is null || activeAttempt is not null || InCooldown(now) ||
                lastAttemptAt is long previous && clock.GetElapsedTime(previous, now) < MinimumAttemptInterval)
                return null;

            while (recentAttempts.TryPeek(out var oldest) && clock.GetElapsedTime(oldest, now) >= Lifetime)
                recentAttempts.Dequeue();
            recentAttempts.Enqueue(now);
            lastAttemptAt = now;
            attempts++;
            if (recentAttempts.Count >= MaximumAttempts)
                cooldownAt = now;
            return activeAttempt = new Attempt(this, (char[])code.Clone());
        }
    }

    /// <summary>Cancel the window and invalidate any exchange in progress.</summary>
    public void Cancel()
    {
        lock (gate)
            Clear();
    }

    private bool Complete(Attempt attempt, bool authenticated)
    {
        lock (gate)
        {
            Expire(clock.GetTimestamp());
            if (!ReferenceEquals(attempt, activeAttempt))
                return false;
            activeAttempt = null;
            if (authenticated || attempts == MaximumAttempts)
                Clear();
            return authenticated;
        }
    }

    private void Expire(long now)
    {
        if (code is not null && clock.GetElapsedTime(openedAt, now) >= Lifetime)
            Clear();
    }

    private bool InCooldown(long now) =>
        cooldownAt is long start && clock.GetElapsedTime(start, now) < Lifetime;

    private void Clear()
    {
        if (code is not null)
            Array.Clear(code);
        code = null;
        activeAttempt?.ClearPassword();
        activeAttempt = null;
    }

    /// <summary>Single-use reservation for one PAKE exchange, with a disposable password buffer.</summary>
    public sealed class Attempt : IDisposable
    {
        private readonly PairingWindow owner;
        private readonly char[] password;

        internal Attempt(PairingWindow owner, char[] password)
        {
            this.owner = owner;
            this.password = password;
        }

        /// <summary>Supply this buffer only to the local PAKE implementation.</summary>
        public ReadOnlyMemory<char> Password => password;

        /// <summary>Consume the code after the PAKE and both identities have been verified.</summary>
        public bool CompleteAuthenticated()
        {
            try
            {
                return owner.Complete(this, authenticated: true);
            }
            finally
            {
                Array.Clear(password);
            }
        }

        internal void ClearPassword() => Array.Clear(password);

        public void Dispose()
        {
            owner.Complete(this, authenticated: false);
            Array.Clear(password);
        }
    }
}
