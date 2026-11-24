param(
    [Parameter(Mandatory)] [string] $Serial,
    [string] $Adb = 'adb',
    [string] $Dotnet = 'dotnet'
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$endpoint = Join-Path $PSScriptRoot 'bin/Release/net10.0-windows/RemoteKeyboard.Windows.Spike.dll'
$namespace = 'keyflow-test-' + [guid]::NewGuid().ToString('N').Substring(0, 16)
$directory = Join-Path $root "artifacts/$namespace"
$runner = 'io.keyflow.remotekeyboard.test/io.keyflow.remotekeyboard.protocol.ProtocolInstrumentation'
if ($Serial -notmatch '^[A-Za-z0-9_.:-]+$') { throw 'Invalid ADB serial.' }

function Invoke-Device {
    $output = & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed ($($args[0]) $($args[1]))." }
    $output
}

function Start-Captured([string] $Executable, [string] $Arguments) {
    $settings = New-Object System.Diagnostics.ProcessStartInfo
    $settings.FileName = $Executable
    $settings.Arguments = $Arguments
    $settings.UseShellExecute = $false
    $settings.CreateNoWindow = $true
    $settings.RedirectStandardOutput = $true
    [System.Diagnostics.Process]::Start($settings)
}

function Read-Line($Process, [int] $Timeout) {
    $read = $Process.StandardOutput.ReadLineAsync()
    if (-not $read.Wait($Timeout) -or $null -eq $read.Result) { throw 'Test endpoint stopped responding.' }
    $read.Result
}

function Stop-Captured($Process) {
    if ($null -eq $Process) { return }
    if (-not $Process.HasExited) { $Process.Kill() }
    $Process.Dispose()
}

function Invoke-Scenario([string] $Mode, [string] $ServerMode = $Mode) {
    $process = Start-Captured $Dotnet ('"' + $endpoint + '" --trust-endpoint "' + $directory + '" ' + $ServerMode)
    $phone = $null
    $forwardedPort = $null
    $server = $null
    $client = $null
    try {
        if ((Read-Line $process 10000) -notmatch '^PORT (\d+)$') { throw 'Windows endpoint failed to start.' }
        $serverPort = [int]$Matches[1]
        $arguments = "-e trustMode $Mode -e trustNamespace $namespace"
        if ($ServerMode -in @('pair', 'no-ack', 'wrong-code', 'pair-wrong-key', 'role-substitution', 'malformed-pairing')) {
            if ((Read-Line $process 5000) -notmatch '^OTP (\d{6})$') { throw 'Pairing failed to arm.' }
            $otp = $Matches[1]
            if ($Mode -eq 'wrong-code') { $otp = (([int]$otp + 1) % 1000000).ToString('D6') }
            $arguments += " -e trustOtp $otp"
        }

        $phone = Start-Captured $Adb "-s $Serial shell am instrument -w -r $arguments $runner"
        do {
            $status = Read-Line $phone 12000
        } until ($status -match '^INSTRUMENTATION_STATUS: trustPort=(\d+)$')
        $devicePort = $Matches[1]
        $forwardedPort = Invoke-Device forward --no-rebind tcp:0 "tcp:$devicePort"
        if ($forwardedPort -notmatch '^\d+$') { throw 'ADB did not return a forward port.' }

        $server = [Net.Sockets.TcpClient]::new('127.0.0.1', $serverPort)
        $client = [Net.Sockets.TcpClient]::new('127.0.0.1', [int]$forwardedPort)
        $toPhone = $server.GetStream().CopyToAsync($client.GetStream())
        $toServer = $client.GetStream().CopyToAsync($server.GetStream())
        $deadline = [DateTime]::UtcNow.AddSeconds(35)
        $phoneClosed = $false
        $serverClosed = $false
        while ((!$phoneClosed -or !$serverClosed) -and [DateTime]::UtcNow -lt $deadline) {
            if (!$phoneClosed -and $toPhone.IsCompleted) {
                $client.Client.Shutdown([Net.Sockets.SocketShutdown]::Send)
                $phoneClosed = $true
            }
            if (!$serverClosed -and $toServer.IsCompleted) {
                $server.Client.Shutdown([Net.Sockets.SocketShutdown]::Send)
                $serverClosed = $true
            }
            if (!$phoneClosed -or !$serverClosed) { Start-Sleep -Milliseconds 25 }
        }
        if (!$phoneClosed -or !$serverClosed) { throw 'The test relay did not close.' }

        if (-not $phone.WaitForExit(5000)) { throw "Android timed out in scenario $Mode." }
        $result = $phone.StandardOutput.ReadToEnd()
        if ($phone.ExitCode -ne 0 -or $result -notmatch 'INSTRUMENTATION_CODE: -1') {
            $result | Write-Output
            throw "Android test failed in scenario $Mode."
        }
        if (-not $process.WaitForExit(5000) -or $process.ExitCode -ne 0) {
            throw "Windows test failed in scenario $Mode."
        }
        $expected = if ($Mode -in @('pair','reconnect','revoke-active','expire-active')) {
            'AUTHENTICATED'
        } else {
            'REJECTED'
        }
        if ($process.StandardOutput.ReadToEnd().Trim() -ne $expected) {
            throw "Windows returned an unexpected result in scenario $Mode."
        }
        "$Mode passed"
    } finally {
        if ($null -ne $client) { $client.Dispose() }
        if ($null -ne $server) { $server.Dispose() }
        Stop-Captured $phone
        Stop-Captured $process
        if ($forwardedPort -match '^\d+$') { Invoke-Device forward --remove "tcp:$forwardedPort" | Out-Null }
    }
}

try {
    Invoke-Device install -r (Join-Path $root 'android/app/build/outputs/apk/debug/app-debug.apk')
    Invoke-Device install -r -t (Join-Path $root 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    Invoke-Scenario no-ack
    Invoke-Scenario pair
    Invoke-Scenario wrong-code
    Invoke-Scenario pair-wrong-key
    Invoke-Scenario role-substitution
    Invoke-Scenario malformed-pairing
    Invoke-Scenario reconnect
    Invoke-Scenario wrong-identity
    Invoke-Scenario wrong-desktop
    Invoke-Scenario replay
    $corrupt = & $Dotnet $endpoint --trust-endpoint $directory corrupt-store
    if ($LASTEXITCODE -ne 0 -or $corrupt -ne 'REJECTED') { throw 'Windows accepted corruption.' }
    $integrity = Invoke-Device shell am instrument -w -r -e trustMode corrupt-store -e trustNamespace $namespace $runner
    if (-not ($integrity -match '^INSTRUMENTATION_CODE: -1$')) { throw 'Android accepted corruption.' }
    'Windows and Android ciphertext integrity passed'
    Invoke-Scenario rollback
    Invoke-Scenario expired
    Invoke-Scenario android-rollback wrong-identity
    Invoke-Scenario android-expired wrong-identity
    Invoke-Scenario expire-active
    Invoke-Scenario revoke-active
    Invoke-Scenario revoked
} finally {
    Invoke-Device shell am instrument -w -r -e trustMode clear -e trustNamespace $namespace $runner | Out-Null
    foreach ($file in 'identity.bin', 'peer.bin', 'other/identity.bin') {
        $path = Join-Path $directory $file
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }
    }
    $other = Join-Path $directory 'other'
    if ((Test-Path -LiteralPath $other) -and -not (Get-ChildItem -LiteralPath $other -Force)) {
        Remove-Item -LiteralPath $other
    }
    if ((Test-Path -LiteralPath $directory) -and -not (Get-ChildItem -LiteralPath $directory -Force)) {
        Remove-Item -LiteralPath $directory
    }
}
