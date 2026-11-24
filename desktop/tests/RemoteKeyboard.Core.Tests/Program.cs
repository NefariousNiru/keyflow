using RemoteKeyboard.Core;

var cases = new (string Name, FocusMetadata Metadata, bool Expected)[]
{
    ("Editable control", new(true, true, false, false), true),
    ("Non-editable control", new(false, true, false, false), false),
    ("Disabled control", new(true, false, false, false), false),
    ("Read-only control", new(true, true, true, false), false),
    ("Password control", new(true, true, false, true), false),
    ("Unknown control", default, false),
};

foreach (var (name, metadata, expected) in cases)
{
    if (metadata.AllowsAutomaticActivation != expected)
        throw new InvalidOperationException($"Unexpected activation decision for {name}.");
}

Console.WriteLine($"Passed {cases.Length} synthetic focus policy cases.");
await ProtocolChecks.RunAsync();
