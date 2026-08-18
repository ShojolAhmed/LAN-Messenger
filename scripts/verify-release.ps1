<#
.SYNOPSIS
    Smoke-tests the packaged LAN Messenger distributions produced by
    scripts\build-release.ps1.

.DESCRIPTION
    Runs three checks against the packaged app-images, using each app's OWN bundled
    runtime (never the system JDK). To emulate a clean machine, JAVA_HOME is pointed
    at a non-existent path while the apps run, proving they are self-contained.

      1. Server   - launches the packaged server, waits for it to listen, performs a
                    real LOGIN handshake over TCP and checks for LOGIN_SUCCESS.
      2. Client   - launches the packaged client in its built-in layout self-test
                    (LANMSG_SMOKE=1), which loads JavaFX (incl. native libraries)
                    and exits by itself. A clean exit proves JavaFX works.
      3. SQLite   - compiles a tiny probe and runs it on the client's BUNDLED runtime
                    against the client's BUNDLED sqlite-jdbc jar, proving java.sql is
                    present and the SQLite native library loads from the package.

.PARAMETER Port
    TCP port used for the temporary server instance (default 5599).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\verify-release.ps1
#>
[CmdletBinding()]
param(
    [int]$Port = 5599,
    [string]$JdkHome
)

# 'Continue' (not 'Stop'): these checks run external tools (java/javac) that print
# to stderr; under 'Stop' a redirected/merged stderr would abort the script. We
# assert explicitly and 'throw' for genuine setup problems instead.
$ErrorActionPreference = 'Continue'

function Write-Step([string]$m) { Write-Host ""; Write-Host "==> $m" -ForegroundColor Cyan }
function Pass([string]$m) { Write-Host "  [PASS] $m" -ForegroundColor Green }
function Fail([string]$m) { Write-Host "  [FAIL] $m" -ForegroundColor Red }

function Resolve-JdkHome([string]$Explicit) {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($Explicit)      { $candidates.Add($Explicit) }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $candidates.Add($env:JAVA_HOME) }
    try {
        $line = (& java -XshowSettings:properties 2>&1 | Select-String 'java\.home')
        if ($line) { $candidates.Add((($line.Line -replace '.*java\.home\s*=\s*', '').Trim())) }
    } catch { }
    Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'jdk*' } | Sort-Object Name -Descending |
        ForEach-Object { $candidates.Add($_.FullName) }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c 'bin\javac.exe'))) { return (Resolve-Path $c).Path }
    }
    throw "Could not locate a JDK with javac (needed only to compile the SQLite probe)."
}

function Wait-Port([int]$p, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $t = New-Object System.Net.Sockets.TcpClient
            $t.Connect('127.0.0.1', $p); $t.Close(); return $true
        } catch { Start-Sleep -Milliseconds 300 }
    }
    return $false
}

# --------------------------------------------------------------------------
$root       = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$release    = Join-Path $root 'release'
$clientApp  = Join-Path $release 'client\LAN Messenger'
$serverApp  = Join-Path $release 'server\LAN Messenger Server'
$clientExe  = Join-Path $clientApp 'LAN Messenger.exe'
$serverExe  = Join-Path $serverApp 'LAN Messenger Server.exe'
$clientJava = Join-Path $clientApp 'runtime\bin\java.exe'

foreach ($p in @($clientExe, $serverExe, $clientJava)) {
    if (-not (Test-Path $p)) { throw "Not found: $p  (run scripts\build-release.ps1 first)" }
}

$jdk   = Resolve-JdkHome $JdkHome
$javac = Join-Path $jdk 'bin\javac.exe'
$tmp   = Join-Path ([System.IO.Path]::GetTempPath()) ("lanmsg-verify-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null

$results = [ordered]@{ Server = $false; Client = $false; SQLite = $false }

# Emulate a clean machine: no usable JAVA_HOME. The packaged apps must ignore it.
$savedJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = 'C:\lanmsg-no-such-jdk'

try {
    # ---------------------------------------------------------------- Server
    Write-Step "Test 1/3: packaged server accepts a client and logs it in (port $Port)"
    $srvOut = Join-Path $tmp 'server-out.log'
    $srvErr = Join-Path $tmp 'server-err.log'
    $proc = Start-Process -FilePath $serverExe -ArgumentList "$Port" -PassThru -NoNewWindow `
        -RedirectStandardOutput $srvOut -RedirectStandardError $srvErr
    try {
        if (-not (Wait-Port $Port 30)) { throw "server did not start listening on port $Port" }
        Pass "server is listening on port $Port"

        $tcp = New-Object System.Net.Sockets.TcpClient
        $tcp.Connect('127.0.0.1', $Port)
        $stream = $tcp.GetStream()
        $stream.ReadTimeout = 5000
        $enc = New-Object System.Text.UTF8Encoding($false)
        $writer = New-Object System.IO.StreamWriter($stream, $enc)
        $writer.NewLine = "`n"; $writer.AutoFlush = $true
        $reader = New-Object System.IO.StreamReader($stream, $enc)

        $writer.WriteLine('LOGIN|verifyuser||')
        $line = $reader.ReadLine()
        if ($line -and $line.StartsWith('LOGIN_SUCCESS')) {
            Pass "received: $line"
            $results.Server = $true
        } else {
            Fail "unexpected login reply: '$line'"
        }
        try { $writer.WriteLine('DISCONNECT|||') } catch { }
        $reader.Dispose(); $writer.Dispose(); $tcp.Close()
    }
    finally {
        if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
    }

    # ---------------------------------------------------------------- Client
    Write-Step "Test 2/3: packaged client loads JavaFX and runs its layout self-test"
    $cliOut = Join-Path $tmp 'client-out.log'
    $cliErr = Join-Path $tmp 'client-err.log'
    $savedSmoke = $env:LANMSG_SMOKE
    $env:LANMSG_SMOKE = '1'
    try {
        # Drive the client's built-in layout self-test on the app's OWN bundled
        # runtime and bundled jars (java.exe gives a reliable exit code and stdout).
        # LANMSG_SMOKE=1 cycles both screens through JavaFX, then calls Platform.exit().
        & $clientJava '--enable-native-access=ALL-UNNAMED' '-cp' "$clientApp\app\*" `
            'com.lanmessenger.client.Launcher' 1>$cliOut 2>$cliErr
        $code = $LASTEXITCODE
        $smoke = (Test-Path $cliOut) -and ((Get-Content $cliOut -Raw) -match 'Smoke:')
        if ($code -eq 0) {
            Pass ("client loaded JavaFX and exited cleanly (exit 0)" +
                  $(if ($smoke) { "; layout self-test cycles observed" } else { "" }))
            $results.Client = $true
        } else {
            Fail "client exited with code $code"
            if (Test-Path $cliErr) { Get-Content $cliErr -Tail 20 | ForEach-Object { Write-Host "      $_" } }
        }
    }
    finally { $env:LANMSG_SMOKE = $savedSmoke }

    # ---------------------------------------------------------------- SQLite
    Write-Step "Test 3/3: SQLite works on the client's bundled runtime + bundled driver"
    $sqliteJar = Get-ChildItem (Join-Path $clientApp 'app\sqlite-jdbc*.jar') | Select-Object -First 1
    if (-not $sqliteJar) { throw "sqlite-jdbc jar not found in the packaged client app\ folder" }

    $probe = Join-Path $tmp 'SqliteCheck.java'
    $probeSrc = @'
import java.sql.*;
public class SqliteCheck {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:sqlite:" + args[0];
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)");
            s.executeUpdate("INSERT INTO t(v) VALUES('hello')");
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM t")) {
                rs.next();
                System.out.println("SQLITE_OK rows=" + rs.getInt(1)
                        + " driver=" + c.getMetaData().getDriverVersion());
            }
        }
    }
}
'@
    # Write BOM-less UTF-8: Windows PowerShell's Set-Content -Encoding UTF8 adds a
    # BOM that javac rejects ("illegal character: '\ufeff'").
    [System.IO.File]::WriteAllText($probe, $probeSrc, (New-Object System.Text.UTF8Encoding($false)))

    & $javac -cp $sqliteJar.FullName -d $tmp $probe
    if ($LASTEXITCODE -ne 0) { throw "failed to compile the SQLite probe" }

    $dbPath = Join-Path $tmp 'probe.db'
    $cp = "$tmp;$clientApp\app\*"
    $out = & $clientJava '--enable-native-access=ALL-UNNAMED' '-cp' $cp 'SqliteCheck' $dbPath 2>&1
    $outText = ($out | Out-String)
    if ($outText -match 'SQLITE_OK') {
        Pass ("SQLite OK -> " + ($outText.Trim() -split "`n" | Where-Object { $_ -match 'SQLITE_OK' } | Select-Object -First 1))
        $results.SQLite = $true
    } else {
        Fail "SQLite probe did not succeed"
        $outText.Trim() -split "`n" | Select-Object -Last 15 | ForEach-Object { Write-Host "      $_" }
    }
}
finally {
    $env:JAVA_HOME = $savedJavaHome
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

# --------------------------------------------------------------------------
Write-Step "Verification summary"
$allOk = $true
foreach ($k in $results.Keys) {
    if ($results[$k]) { Pass $k } else { Fail $k; $allOk = $false }
}
if ($allOk) {
    Write-Host "`nAll packaged-application checks passed." -ForegroundColor Green
    exit 0
} else {
    Write-Host "`nOne or more checks failed." -ForegroundColor Red
    exit 1
}
