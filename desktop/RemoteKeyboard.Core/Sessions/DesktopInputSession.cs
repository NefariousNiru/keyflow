using RemoteKeyboard.Core.Protocol;

namespace RemoteKeyboard.Core.Sessions;

/// <summary>Platform input boundary. Implementations revalidate the target at the moment of injection.</summary>
public interface ISessionInput
{
    bool TryKey(long generation, CanonicalKey key, bool down);
    bool TryText(long generation, string text);

    /// <summary>Release only these session-owned keys, subject to the OS safety policy.</summary>
    void ReleaseKeys(IReadOnlyList<CanonicalKey> keys);
}

public enum InputResult
{
    Accepted,
    StaleFocus,
    InputUnavailable,
    Disconnect
}

/// <summary>Serial session consumer for messages from the phone on the authenticated desktop connection.</summary>
public sealed class DesktopInputSession(SessionAuthorization authorization, ISessionInput input) : IDisposable
{
    private readonly HashSet<CanonicalKey> held = [];
    private long generation;
    private bool active;
    private bool paused;

    /// <summary>Sets the latest local focus generation. Every change must have a fresh generation.</summary>
    public void SetFocus(long nextGeneration, bool targetActive)
    {
        if (nextGeneration <= generation)
            throw new ArgumentOutOfRangeException(nameof(nextGeneration));
        active = false;
        ReleaseHeld();
        generation = nextGeneration;
        active = targetActive;
    }

    public void SetPaused(bool isPaused)
    {
        paused = isPaused;
        if (paused)
        {
            active = false;
            ReleaseHeld();
        }
    }

    /// <summary>Called during lock, revocation, disconnect or an authorization watchdog failure.</summary>
    public void Dispose()
    {
        authorization.Revoke();
        ReleaseHeld();
    }

    /// <summary>Returns false after revoking/expiring the session and releasing its input.</summary>
    public bool CheckAuthorization()
    {
        if (authorization.IsAuthorized())
            return true;
        Dispose();
        return false;
    }

    /// <summary>Consumes a decoded message from the authenticated transport.</summary>
    public InputResult Receive(SessionMessage message)
    {
        if (!authorization.TryAccept(message))
            return Disconnect();

        return message switch
        {
            Heartbeat => InputResult.Accepted,
            ReleaseAll => ReleaseAllInput(),
            KeyChange key => ApplyKey(key),
            TextCommit text => ApplyText(text),
            _ => Disconnect()
        };
    }

    private InputResult ApplyKey(KeyChange message)
    {
        if (!MatchesFocus(message.Generation))
            return InputResult.StaleFocus;
        if (!Enum.IsDefined(message.Key))
            return Disconnect();
        if (!message.Down && !held.Contains(message.Key))
            return InputResult.Accepted;

        if (!input.TryKey(generation, message.Key, message.Down))
            return InvalidateFocus();
        if (message.Down)
            held.Add(message.Key);
        else
            held.Remove(message.Key);
        return InputResult.Accepted;
    }

    private InputResult ApplyText(TextCommit message)
    {
        if (!MatchesFocus(message.Generation))
            return InputResult.StaleFocus;
        return input.TryText(generation, message.Text) ? InputResult.Accepted : InvalidateFocus();
    }

    private bool MatchesFocus(long requested) => active && !paused && requested == generation;

    private InputResult InvalidateFocus()
    {
        active = false;
        ReleaseHeld();
        return InputResult.InputUnavailable;
    }

    private InputResult ReleaseAllInput()
    {
        ReleaseHeld();
        return InputResult.Accepted;
    }

    private InputResult Disconnect()
    {
        Dispose();
        return InputResult.Disconnect;
    }

    private void ReleaseHeld()
    {
        if (held.Count == 0)
            return;
        var keys = held.ToArray();
        held.Clear();
        input.ReleaseKeys(keys);
    }
}
