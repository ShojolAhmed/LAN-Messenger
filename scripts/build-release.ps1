<#
.SYNOPSIS
    Builds self-contained Windows distributions of the LAN Messenger client and
    server using Maven + jlink + jpackage.

.DESCRIPTION
    Produces two independent "app-image" distributions (portable folders that each
    bundle their own trimmed Java runtime, plus JavaFX and SQLite for the client),
    and writes them under the release\ directory:

        release\client\LAN Messenger\LAN Messenger.exe
        release\server\LAN Messenger Server\LAN Messenger Server.exe
        release\<name>-1.0-win-x64.zip        (zipped copies for distribution)
        release\README.md                     (deployment guide, copied in)

    No functionality is changed. WiX is not required because we build app-images
    (portable), not MSI/EXE installers.

.PARAMETER SkipTests
    Pass -DskipTests to the Maven build (faster; not recommended for a real release).

.PARAMETER SkipZip
    Do not create the .zip archives (leave only the app-image folders).

.PARAMETER JdkHome
    Explicit path to a JDK 17+ that contains bin\jpackage.exe and bin\jlink.exe.
    If omitted, the script auto-detects one (JAVA_HOME, the active java launcher,
    then C:\Program Files\Java\jdk*).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\build-release.ps1
#>
[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$SkipZip,
    [string]$JdkHome
)

$ErrorActionPreference = 'Stop'

# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------
function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Assert-ExitCode([string]$What) {
    if ($LASTEXITCODE -ne 0) {
        throw "$What failed with exit code $LASTEXITCODE"
    }
}

function Test-JdkHome([string]$Path) {
    return $Path -and
           (Test-Path (Join-Path $Path 'bin\jpackage.exe')) -and
           (Test-Path (Join-Path $Path 'bin\jlink.exe'))
}

function Resolve-JdkHome([string]$Explicit) {
    # Local to this function: native tools (java) print to stderr, and under the
    # script's global 'Stop' preference a redirected stderr would be promoted to a
    # terminating error and hide a perfectly good result. 'Continue' keeps probing
    # resilient without affecting the caller.
    $ErrorActionPreference = 'Continue'

    # 1) Explicit override.
    if (Test-JdkHome $Explicit) { return (Resolve-Path $Explicit).Path }

    # 2) The JDK the active 'java' launcher resolves to. Preferred so the packaged
    #    runtime matches the JDK Maven uses to compile and test. java prints its
    #    settings to stderr, so capture stderr via a temp file (not the pipeline).
    try {
        $probe = [System.IO.Path]::GetTempFileName()
        try {
            & java -XshowSettings:properties -version 2>$probe | Out-Null
            $out = Get-Content $probe -Raw
        } finally {
            Remove-Item $probe -Force -ErrorAction SilentlyContinue
        }
        $m = [regex]::Match($out, 'java\.home\s*=\s*(.+)')
        if ($m.Success) {
            $javaHome = $m.Groups[1].Value.Trim()
            if (Test-JdkHome $javaHome) { return (Resolve-Path $javaHome).Path }
        }
    } catch { }

    # 3) JAVA_HOME, if usable.
    if (Test-JdkHome $env:JAVA_HOME) { return (Resolve-Path $env:JAVA_HOME).Path }

    # 4) Last resort: scan the usual install location, newest first.
    $scanned = Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'jdk*' } | Sort-Object Name -Descending
    foreach ($d in $scanned) { if (Test-JdkHome $d.FullName) { return $d.FullName } }

    throw "Could not find a JDK containing jpackage.exe and jlink.exe. " +
          "Install a JDK 17+ and pass -JdkHome, or set JAVA_HOME."
}

function New-DistZip([string]$SourceDir, [string]$ZipPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
    # Retry briefly: a freshly written runtime DLL can be momentarily locked by
    # antivirus/Windows, which would otherwise fail the archive step.
    for ($attempt = 1; $attempt -le 6; $attempt++) {
        try {
            if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
            [System.IO.Compression.ZipFile]::CreateFromDirectory($SourceDir, $ZipPath)
            return
        } catch {
            if ($attempt -eq 6) { throw }
            Write-Host "    archive busy, retrying in 3s ($attempt/6)..." -ForegroundColor Yellow
            Start-Sleep -Seconds 3
        }
    }
}

# --------------------------------------------------------------------------
# Setup
# --------------------------------------------------------------------------
$root    = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$jdk     = Resolve-JdkHome $JdkHome
$jpackage = Join-Path $jdk 'bin\jpackage.exe'
$jlink    = Join-Path $jdk 'bin\jlink.exe'
$jmods    = Join-Path $jdk 'jmods'

$appVersion = '1.0'
$vendor     = 'LAN Messenger'
$release    = Join-Path $root 'release'
$work       = Join-Path $root 'target\release-work'

Write-Step "Toolchain"
Write-Host "  Project : $root"
Write-Host "  JDK     : $jdk"
& $jpackage --version | ForEach-Object { Write-Host "  jpackage: $_" }

# Application definitions. Runtime module sets are deliberately explicit so the
# bundled runtimes are small but complete:
#   - client runs JavaFX from the class path (needs java.desktop, jdk.unsupported,
#     java.xml, java.scripting) and SQLite via JDBC (needs java.sql).
#   - server only uses sockets (java.base) and java.util.logging (java.logging).
$apps = @(
    @{
        Id          = 'client'
        Name        = 'LAN Messenger'
        MainClass   = 'com.lanmessenger.client.Launcher'
        WinConsole  = $false
        JavaOptions = @('--enable-native-access=ALL-UNNAMED')
        Modules     = 'java.base,java.desktop,java.logging,java.naming,java.scripting,java.sql,java.xml,jdk.unsupported'
        Description = 'LAN Messenger desktop client (JavaFX)'
    },
    @{
        Id          = 'server'
        Name        = 'LAN Messenger Server'
        MainClass   = 'com.lanmessenger.server.ServerApplication'
        WinConsole  = $true
        JavaOptions = @()
        Modules     = 'java.base,java.logging'
        Description = 'LAN Messenger central TCP server'
    }
)

# --------------------------------------------------------------------------
# Clean
# --------------------------------------------------------------------------
Write-Step "Cleaning previous release output"
if (Test-Path $release) { Remove-Item $release -Recurse -Force }
if (Test-Path $work)    { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Path $release | Out-Null
New-Item -ItemType Directory -Path $work | Out-Null

# --------------------------------------------------------------------------
# Build (Maven stages runtime jars into each module's target\dist\lib)
# --------------------------------------------------------------------------
Write-Step "Building modules with Maven (mvn -Pdist clean package)"
$mvnArgs = @('-Pdist', 'clean', 'package')
if ($SkipTests) { $mvnArgs += '-DskipTests' }
& mvn @mvnArgs
Assert-ExitCode 'Maven build'

# --------------------------------------------------------------------------
# Package each application
# --------------------------------------------------------------------------
foreach ($app in $apps) {
    $id = $app.Id
    Write-Step "Packaging '$($app.Name)' [$id]"

    $moduleTarget = Join-Path $root "$id\target"
    $staging      = Join-Path $moduleTarget 'dist\lib'
    if (-not (Test-Path $staging)) {
        throw "Staging directory not found: $staging (the 'dist' profile did not run)."
    }

    # Add the module's own jar to the flat class-path directory jpackage bundles.
    $mainJarFile = Get-ChildItem (Join-Path $moduleTarget "$id-*.jar") |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
        Select-Object -First 1
    if (-not $mainJarFile) { throw "Built jar for '$id' not found under $moduleTarget" }
    Copy-Item $mainJarFile.FullName -Destination $staging -Force
    $mainJar = $mainJarFile.Name

    # 1) Build a trimmed, self-contained Java runtime for this app.
    $runtime = Join-Path $work "runtime\$id"
    Write-Host "  jlink   : $runtime"
    & $jlink `
        --module-path $jmods `
        --add-modules $app.Modules `
        --output $runtime `
        --strip-debug --no-header-files --no-man-pages
    Assert-ExitCode "jlink ($id)"

    # 2) Assemble the app-image with jpackage using that runtime.
    $destDir = Join-Path $release $id
    New-Item -ItemType Directory -Path $destDir -Force | Out-Null

    $jpArgs = @(
        '--type', 'app-image',
        '--name', $app.Name,
        '--app-version', $appVersion,
        '--vendor', $vendor,
        '--description', $app.Description,
        '--input', $staging,
        '--main-jar', $mainJar,
        '--main-class', $app.MainClass,
        '--runtime-image', $runtime,
        '--dest', $destDir
    )
    foreach ($opt in $app.JavaOptions) { $jpArgs += @('--java-options', $opt) }
    if ($app.WinConsole) { $jpArgs += '--win-console' }

    Write-Host "  jpackage: $destDir\$($app.Name)"
    & $jpackage @jpArgs
    Assert-ExitCode "jpackage ($id)"
}

# --------------------------------------------------------------------------
# Copy the deployment guide into the release directory
# --------------------------------------------------------------------------
$deploySrc = Join-Path $root 'packaging\DEPLOYMENT.md'
if (Test-Path $deploySrc) {
    Copy-Item $deploySrc -Destination (Join-Path $release 'README.md') -Force
    Write-Step "Copied deployment guide -> release\README.md"
}

# --------------------------------------------------------------------------
# Zip the app-images for distribution
# --------------------------------------------------------------------------
if (-not $SkipZip) {
    Write-Step "Creating distributable archives"
    foreach ($app in $apps) {
        # Each release\<id> folder contains exactly one app-image folder, so the
        # zip's top-level entry is the friendly app name (e.g. 'LAN Messenger\...').
        $sourceDir = Join-Path $release $app.Id
        $zipName   = ('{0}-{1}-win-x64.zip' -f ($app.Name -replace ' ', '-'), $appVersion)
        $zip       = Join-Path $release $zipName
        New-DistZip -SourceDir $sourceDir -ZipPath $zip
        Write-Host "  $zip"
    }
}

# --------------------------------------------------------------------------
# Summary
# --------------------------------------------------------------------------
Write-Step "Release complete"
foreach ($app in $apps) {
    $exe = Join-Path $release "$($app.Id)\$($app.Name)\$($app.Name).exe"
    $dir = Join-Path $release "$($app.Id)\$($app.Name)"
    $sizeMb = if (Test-Path $dir) {
        [math]::Round((Get-ChildItem $dir -Recurse | Measure-Object Length -Sum).Sum / 1MB, 1)
    } else { 0 }
    Write-Host ("  {0,-22} exe: {1,-5} size: {2} MB" -f $app.Name, (Test-Path $exe), $sizeMb)
}
Write-Host ""
Write-Host "Output: $release"
Write-Host "Next  : run scripts\verify-release.ps1 to smoke-test the packaged apps."
