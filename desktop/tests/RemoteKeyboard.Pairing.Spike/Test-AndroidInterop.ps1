param(
    [Parameter(Mandatory)] [string] $Serial,
    [string] $Adb = 'adb',
    [string] $Dotnet = 'dotnet'
)

$ErrorActionPreference = 'Stop'
$package = 'io.keyflow.remotekeyboard'
$assembly = Join-Path $PSScriptRoot 'bin/Release/net10.0/RemoteKeyboard.Pairing.Spike.dll'
$testFiles = 'files/pairing-in1 files/pairing-in2 files/pairing-in3 files/pairing-input.tmp files/pairing-out1 files/pairing-out2 files/pairing-out3 files/pairing-out1.tmp files/pairing-out2.tmp files/pairing-out3.tmp files/pairing-result files/pairing-result.tmp'

function Invoke-Device([string] $Command) {
    $output = & $Adb -s $Serial shell $Command
    if ($LASTEXITCODE -ne 0) { throw 'ADB probe command failed.' }
    $output
}

function Receive-Phone([string] $Name) {
    $file = "files/pairing-$Name"
    $limit = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $payload = Invoke-Device "run-as $package sh -c 'if test -f $file; then cat $file; fi'"
        if ($payload) { return $payload }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $limit)
    throw "Timed out waiting for Android probe output ($Name)."
}

function Receive-Windows($Process) {
    $line = $Process.StandardOutput.ReadLineAsync()
    if (-not $line.Wait(15000)) { throw 'Timed out waiting for the Windows probe.' }
    if (-not $line.Result) { throw 'Windows probe ended before returning a result.' }
    $line.Result
}

foreach ($mismatch in 'false', 'true') {
    Invoke-Device "am force-stop $package"
    Invoke-Device "run-as $package rm -f $testFiles"

    $settings = New-Object System.Diagnostics.ProcessStartInfo
    $settings.FileName = $Dotnet
    $settings.Arguments = '"' + $assembly + '"'
    $settings.UseShellExecute = $false
    $settings.CreateNoWindow = $true
    $settings.RedirectStandardInput = $true
    $settings.RedirectStandardOutput = $true
    $process = [System.Diagnostics.Process]::Start($settings)
    try {
        $started = Invoke-Device "am start -W -n $package/.PairingProbeActivity --ez mismatch $mismatch"
        if (-not ($started -match '^Status: ok')) { throw 'Android pairing probe did not launch.' }

        foreach ($round in 1..3) {
            $windows = Receive-Windows $process
            if ($windows -notmatch '^windows,[-0-9,]{1,8184}$') { throw 'Unexpected Windows round payload.' }
            Invoke-Device "run-as $package sh -c 'echo $windows > files/pairing-input.tmp && mv files/pairing-input.tmp files/pairing-in$round'"
            $android = Receive-Phone "out$round"
            if ($android -notmatch '^android,[-0-9,]{1,8184}$') { throw 'Unexpected Android round payload.' }
            $process.StandardInput.WriteLine($android)
        }

        $expected = if ($mismatch -eq 'true') { 'REJECTED' } else { 'CONFIRMED' }
        $windowsResult = Receive-Windows $process
        $androidResult = Receive-Phone 'result'
        if ($windowsResult -ne $expected -or $androidResult -ne $expected) {
            throw "Unexpected confirmation outcomes for mismatch=$mismatch."
        }
        if (-not $process.WaitForExit(5000) -or $process.ExitCode -ne 0) { throw 'Windows probe failed.' }
        "mismatch=$mismatch, both peers $expected"
    } finally {
        if (-not $process.HasExited) { $process.Kill() }
        $process.Dispose()
        Invoke-Device "am force-stop $package"
        Invoke-Device "run-as $package rm -f $testFiles"
    }
}
