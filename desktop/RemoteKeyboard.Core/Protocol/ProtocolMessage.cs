namespace RemoteKeyboard.Core.Protocol;

/// <summary>Key identities shared by the peers. Native key codes belong to the OS adapter.</summary>
public enum CanonicalKey
{
    Escape = 1, Tab, Enter, Backspace, Delete, Control, Alt, Shift, Win,
    Home, End, PageUp, PageDown, Left, Right, Up, Down,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    Digit0, Digit1, Digit2, Digit3, Digit4, Digit5, Digit6, Digit7, Digit8, Digit9,
    Space, Minus, Equal, LeftBracket, RightBracket, Backslash, Semicolon,
    Apostrophe, Grave, Comma, Period, Slash
}

[Flags]
public enum ProtocolCapabilities
{
    Keys = 1,
    UnicodeText = 2
}

public enum ProtocolError
{
    UnexpectedMessage = 1,
    StaleFocus,
    InputUnavailable
}

/// <summary>Version 1 messages. These may travel only on an authenticated transport.</summary>
public abstract record ProtocolMessage;
public sealed record Hello(ProtocolCapabilities Capabilities) : ProtocolMessage;
public abstract record SessionMessage(Guid SessionId, long Sequence) : ProtocolMessage;
public sealed record FocusState(Guid SessionId, long Sequence, long Generation, bool Active)
    : SessionMessage(SessionId, Sequence);
public sealed record KeyChange(Guid SessionId, long Sequence, long Generation, CanonicalKey Key, bool Down)
    : SessionMessage(SessionId, Sequence);
public sealed record TextCommit(Guid SessionId, long Sequence, long Generation, string Text)
    : SessionMessage(SessionId, Sequence)
{
    public override string ToString() => nameof(TextCommit);
}
public sealed record ReleaseAll(Guid SessionId, long Sequence) : SessionMessage(SessionId, Sequence);
public sealed record Heartbeat(Guid SessionId, long Sequence) : SessionMessage(SessionId, Sequence);
public sealed record Pause(Guid SessionId, long Sequence, bool Paused) : SessionMessage(SessionId, Sequence);
public sealed record ErrorNotice(Guid SessionId, long Sequence, ProtocolError Error)
    : SessionMessage(SessionId, Sequence);
