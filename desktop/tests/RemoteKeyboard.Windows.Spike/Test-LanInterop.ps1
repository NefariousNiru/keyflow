param(
    [Parameter(Mandatory)] [string]$Serial,
    [string]$Adb = 'adb',
    [string]$PublicPhoneAddress,
    [string]$Harness = "$PSScriptRoot/../../../artifacts/m2-windows/RemoteKeyboard.Windows.Spike.exe"
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$binary = (Resolve-Path -LiteralPath $Harness).Path
$namespace = 'keyflow-test-' + [guid]::NewGuid().ToString('N').Substring(0, 16)
$directory = Join-Path $root "artifacts/$namespace"
$package = 'io.keyflow.remotekeyboard'
$runner = "$package.test/io.keyflow.remotekeyboard.protocol.ProtocolInstrumentation"
if ($Serial -notmatch '^[A-Za-z0-9_.:-]+$') { throw 'Invalid ADB serial.' }

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
function Run-Phone([string]$Mode, [string[]]$Extra = @()) {
    $result = Device shell am instrument -w -r -e lanMode $Mode -e trustNamespace $namespace -e lanAddress $address @Extra $runner
    if (-not ($result -match '^INSTRUMENTATION_CODE: -1$')) {
        $result | Write-Output
        throw "Phone case $Mode failed."
    }
    "$Mode passed"
}
function Expect-Status($Process, [string]$Expected) {
    do {
        $status = Line $Process 45000
    } until ($status -eq "INSTRUMENTATION_STATUS: lanStatus=$Expected")
}
function Verify-DesktopInput($Process) {
    if ((Line $Process 5000) -ne 'AUTHENTICATED') { throw 'The desktop did not accept synthetic authenticated input.' }
}

$endpointArguments = '--lan-endpoint "' + $directory + '" pair'
if ($PublicPhoneAddress) {
    if ($PublicPhoneAddress -notmatch '^(\d{1,3}\.){3}\d{1,3}$') { throw 'Invalid phone IPv4 address.' }
    $endpointArguments += " $PublicPhoneAddress"
}
$desktop = Start-Captured $binary $endpointArguments
$phone = $null
$wifiDisabled = $false
$grants = @()
try {
    $first = Line $desktop 10000
    if ($first -eq 'WIFI_SELECTION_REQUIRED') { throw 'No eligible Wi-Fi profile and phone address.' }
    if ($first -notmatch '^ADDRESS ([0-9.]+)$') { throw 'The LAN listener did not start.' }
    $address = $Matches[1]
    if ((Line $desktop 5000) -notmatch '^OTP ([0-9]{6})$') { throw 'The pairing window did not arm.' }
    $otp = $Matches[1]

    Device install -r (Join-Path $root 'android/app/build/outputs/apk/debug/app-debug.apk')
    Device install -r -t (Join-Path $root 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    $features = Device shell dumpsys package $package
    foreach ($permission in 'ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION') {
        if (-not ($features -match "android.permission.${permission}: granted=true")) {
            Device shell pm grant $package "android.permission.$permission"
            $grants += $permission
        }
    }

    Run-Phone policy
    Run-Phone pair @('-e', 'lanOtp', $otp)
    Verify-DesktopInput $desktop
    if (-not $PublicPhoneAddress) {
        Run-Phone discover
        Verify-DesktopInput $desktop
    }

    $phone = Start-Captured $Adb "-s $Serial shell am instrument -w -r -e lanMode recovery -e trustNamespace $namespace -e lanAddress $address $runner"
    Expect-Status $phone CONNECTED
    Verify-DesktopInput $desktop
    Device shell svc wifi disable
    $wifiDisabled = $true
    Expect-Status $phone UNTRUSTED_WIFI
    Device shell svc wifi enable
    $wifiDisabled = $false
    Expect-Status $phone CONNECTED
    Verify-DesktopInput $desktop
    if (-not $phone.WaitForExit(5000) -or $phone.StandardOutput.ReadToEnd() -notmatch 'INSTRUMENTATION_CODE: -1') {
        throw 'Automatic recovery test failed.'
    }
    'Wi-Fi loss and authenticated recovery passed'
} finally {
    if ($wifiDisabled) { Device shell svc wifi enable }
    if ($null -ne $phone) {
        if (-not $phone.HasExited) { $phone.Kill() }
        $phone.Dispose()
    }
    if (-not $desktop.HasExited) {
        $desktop.StandardInput.WriteLine()
        if (-not $desktop.WaitForExit(9000)) { $desktop.Kill() }
    }
    $desktop.Dispose()

    if (-not [string]::IsNullOrEmpty($address)) {
        Device shell am instrument -w -r -e lanMode clear -e trustNamespace $namespace $runner | Out-Null
    }
    foreach ($permission in $grants) { Device shell pm revoke $package "android.permission.$permission" }
    foreach ($name in 'identity.bin','peer.bin') {
        $file = Join-Path $directory $name
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file }
    }
    if ((Test-Path -LiteralPath $directory) -and -not (Get-ChildItem -LiteralPath $directory -Force)) {
        Remove-Item -LiteralPath $directory
    }
}
