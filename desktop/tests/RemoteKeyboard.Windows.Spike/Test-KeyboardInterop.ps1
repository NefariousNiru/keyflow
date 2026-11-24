param(
    [Parameter(Mandatory)] [string]$Serial,
    [string]$Adb = 'adb',
    [string]$PublicPhoneAddress,
    [switch]$ForceStopOnly,
    [string]$Harness = "$PSScriptRoot/../../../artifacts/m2-windows/RemoteKeyboard.Windows.Spike.exe"
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$binary = (Resolve-Path -LiteralPath $Harness).Path
$namespace = 'keyflow-test-' + [guid]::NewGuid().ToString('N').Substring(0, 16)
$directory = Join-Path $root "artifacts/$namespace"
$package = 'io.keyflow.remotekeyboard'
$runner = "$package.test/io.keyflow.remotekeyboard.protocol.ProtocolInstrumentation"
if ($Serial -notmatch '^[A-Za-z0-9_.:-]+$') { throw 'Invalid device serial.' }
if ($PublicPhoneAddress -and $PublicPhoneAddress -notmatch '^(\d{1,3}\.){3}\d{1,3}$') {
    throw 'Invalid phone IPv4 address.'
}

function Device {
    $output = & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed ($($args[0]) $($args[1]))." }
    $output
}
function Start-Captured([string]$Executable, [string]$Arguments) {
    $options = [Diagnostics.ProcessStartInfo]::new($Executable, $Arguments)
    $options.UseShellExecute = $false
    $options.CreateNoWindow = $true
    $options.RedirectStandardInput = $true
    $options.RedirectStandardOutput = $true
    [Diagnostics.Process]::Start($options)
}
function Line($Process, [int]$Milliseconds) {
    $read = $Process.StandardOutput.ReadLineAsync()
    if (-not $read.Wait($Milliseconds) -or $null -eq $read.Result) { throw 'Test endpoint stopped responding.' }
    $read.Result
}
function Expect-Status($Process, [string]$Expected) {
    $deadline = [DateTime]::UtcNow.AddSeconds(35)
    do {
        $remaining = [int]($deadline - [DateTime]::UtcNow).TotalMilliseconds
        if ($remaining -le 0) { throw "Missing Android status $Expected." }
        $status = Line $Process $remaining
        if ($status -match '^INSTRUMENTATION_RESULT:') { Write-Host $status }
        if ($status -match '^INSTRUMENTATION_CODE:') { throw "Android ended before $Expected." }
    } until ($status -eq "INSTRUMENTATION_STATUS: keyboardStatus=$Expected")
}

$desktop = Start-Captured $binary ('--lan-endpoint "' + $directory + '" keyboard ' + $PublicPhoneAddress)
$phone = $null
$grants = @()
try {
    if ((Line $desktop 10000) -notmatch '^ADDRESS ([0-9.]+)$') { throw 'LAN harness did not bind.' }
    $address = $Matches[1]
    if ((Line $desktop 5000) -notmatch '^OTP ([0-9]{6})$') { throw 'Pairing request was not armed.' }
    $otp = $Matches[1]

    Device install -r (Join-Path $root 'android/app/build/outputs/apk/debug/app-debug.apk')
    Device install -r -t (Join-Path $root 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    $features = Device shell dumpsys package $package
    foreach ($permission in 'ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION', 'POST_NOTIFICATIONS') {
        if (-not ($features -match ('android.permission.' + $permission + ': granted=true'))) {
            Device shell pm grant $package "android.permission.$permission"
            $grants += $permission
        }
    }

    $mode = if ($ForceStopOnly) { 'beforeForceStop' } else { 'keyboard' }
    $phone = Start-Captured $Adb "-s $Serial shell am instrument -w -r -e m3Mode $mode -e trustNamespace $namespace -e lanAddress $address -e lanOtp $otp $runner"
    Expect-Status $phone M3_CONNECTED
    if ($ForceStopOnly) {
        Device shell am force-stop $package
        if (-not $phone.WaitForExit(10000)) { throw 'Android instrumentation survived force-stop.' }
        $phone.Dispose()
        $phone = $null
        Start-Sleep -Seconds 3
        if (Device shell dumpsys activity services $package | Select-String 'ServiceRecord') {
            throw 'The keyboard service returned after force-stop.'
        }
        $phone = Start-Captured $Adb "-s $Serial shell am instrument -w -r -e m3Mode afterForceStop -e trustNamespace $namespace -e lanAddress $address $runner"
        Expect-Status $phone M3_CONNECTED
        if (-not $phone.WaitForExit(8000) -or $phone.StandardOutput.ReadToEnd() -notmatch 'INSTRUMENTATION_CODE: -1') {
            throw 'Restart validation did not complete.'
        }
        'Force-stop left the service stopped. An explicit foreground restart reauthenticated without a new OTP.'
        return
    }
    $desktop.StandardInput.WriteLine('focus')
    Expect-Status $phone M3_DISMISSED
    Expect-Status $phone M3_BACKGROUND
    $desktop.StandardInput.WriteLine('blur')
    $desktop.StandardInput.WriteLine('focus')
    Expect-Status $phone M3_FALLBACK_OK
    Expect-Status $phone M3_RECOVERED
    Expect-Status $phone M3_SCREEN_OFF
    $desktop.StandardInput.WriteLine('focus')
    Expect-Status $phone M3_DOZE_OK
    $completed = $phone.WaitForExit(16000)
    if ($completed) {
        $result = $phone.StandardOutput.ReadToEnd()
        if ($result -match '(?m)^INSTRUMENTATION_STATUS: keyboardStatus=M3_KEYGUARD_OK\r?$') {
            'Secure keyguard stayed locked and rejected keyboard input.'
        }
    }
    if (-not $completed -or $result -notmatch 'INSTRUMENTATION_CODE: -1') {
        throw "Android keyboard validation did not complete. $result"
    }

    $desktop.StandardInput.WriteLine()
    if (-not $desktop.WaitForExit(8000)) { throw 'Desktop harness did not finish.' }
    $received = @($desktop.StandardOutput.ReadToEnd() -split '\r?\n' | Where-Object { $_ })
    $expected = @('KEY Q DOWN','KEY Q UP','KEY Control DOWN','KEY C DOWN','KEY C UP','KEY Control UP','KEY F12 DOWN','KEY F12 UP')
    if ($desktop.ExitCode -ne 0 -or $received.Count -lt 14 -or
        ($received[0..7] -join ';') -ne ($expected -join ';') -or
        @($received -eq 'KEY Backspace DOWN').Count -lt 2 -or
        @($received -eq 'KEY Backspace DOWN').Count -ne @($received -eq 'KEY Backspace UP').Count -or
        $received[-2] -ne 'UNICODE_OK' -or $received[-1] -ne 'SUPPLEMENTARY_OK') {
        throw 'The Windows keyboard event order did not match the synthetic gestures.'
    }
    'Physical Wi-Fi keyboard, repeat, rotation, dismissal, notification fallback, reconnection, screen off, and forced Doze passed.'
} finally {
    if ($null -ne $phone) {
        if (-not $phone.HasExited) { $phone.Kill() }
        $phone.Dispose()
    }
    if (-not $desktop.HasExited) {
        $desktop.StandardInput.WriteLine()
        if (-not $desktop.WaitForExit(9000)) { $desktop.Kill() }
    }
    $desktop.Dispose()
    foreach ($permission in $grants) { Device shell pm revoke $package "android.permission.$permission" }
    foreach ($name in 'identity.bin','peer.bin') {
        $file = Join-Path $directory $name
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file }
    }
    if ((Test-Path -LiteralPath $directory) -and -not (Get-ChildItem -LiteralPath $directory -Force)) {
        Remove-Item -LiteralPath $directory
    }
}
