namespace RemoteKeyboard.Core;

/// <summary>Metadata for deciding whether a focused control can request automatic activation.</summary>
public readonly record struct FocusMetadata(
    bool IsEditable, bool IsEnabled, bool IsReadOnly, bool IsPassword)
{
    /// <summary>Evaluates the default automatic activation policy.</summary>
    public bool AllowsAutomaticActivation =>
        IsEditable && IsEnabled && !IsReadOnly && !IsPassword;
}
