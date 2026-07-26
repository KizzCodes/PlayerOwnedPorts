# -----------------------------------------------------------------------------
#  Player-Owned Ports - direct build (no Gradle / no install required)
#
#  Compiles with the JDK 20 that ships inside the BotWithUs client, packages the
#  classes + script.ini into PlayerOwnedPorts.jar, and drops it into the client's
#  local-scripts folder. Reload scripts in the client to pick it up.
#
#  Usage:   powershell -ExecutionPolicy Bypass -File build.ps1
# -----------------------------------------------------------------------------
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

# 1. Locate the client's bundled JDK (override with $env:BWU_JDK). The client
#    installs itself into a randomly-named dot-folder under the user profile,
#    so probe for the one that actually contains a javac.
$jdk = $env:BWU_JDK
if ([string]::IsNullOrWhiteSpace($jdk)) {
    $jdk = Get-ChildItem -Path $env:USERPROFILE -Directory -Force -Filter ".*" -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName "jre" } |
        Where-Object { Test-Path (Join-Path $_ "bin\javac.exe") } |
        Select-Object -First 1
}
if ([string]::IsNullOrWhiteSpace($jdk)) {
    throw "Could not find the BotWithUs client's bundled JDK. Set `$env:BWU_JDK to a JDK 20 home (the folder containing bin\javac.exe)."
}
$javac = Join-Path $jdk "bin\javac.exe"
if (-not (Test-Path $javac)) {
    throw "javac not found at $javac. Set `$env:BWU_JDK to a JDK 20 home (the folder containing bin\javac.exe)."
}
Write-Host "Using javac: $javac"

# 2. Paths
$srcDir   = Join-Path $root "src\main\java"
$resDir   = Join-Path $root "src\main\resources"
$libsDir  = Join-Path $root "libs"
$outDir   = Join-Path $root "build\classes"
$jarPath  = Join-Path $root "build\PlayerOwnedPorts.jar"
$localDir = Join-Path $env:USERPROFILE "BotWithUs\scripts\local"

# 3. Clean + compile
if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$sources = Get-ChildItem -Recurse -Path $srcDir -Filter *.java | ForEach-Object { $_.FullName }
Write-Host ("Compiling {0} source file(s)..." -f $sources.Count)
& $javac --enable-preview --release 20 -cp "$libsDir\*" -d $outDir @sources
if ($LASTEXITCODE -ne 0) { throw "Compilation failed." }

# 4. Copy resources (script.ini etc.) alongside the classes
if (Test-Path $resDir) {
    Copy-Item -Path (Join-Path $resDir "*") -Destination $outDir -Recurse -Force
}

# 4b. Bundle the xapi.public API (net.botwithus.api.*) INTO the jar. The client
#     provides the core rs3 API but NOT xapi.public to local scripts, so classes
#     like Dialog/Traverse/Lodestone must ship inside our jar (as every reference
#     script does via includeInJar). xapi-public.jar contains only net.botwithus.api.*.
$xapi = Join-Path $libsDir "xapi-public.jar"
if (Test-Path $xapi) {
    $zin = [System.IO.Compression.ZipFile]::OpenRead($xapi)
    try {
        foreach ($entry in $zin.Entries) {
            if ($entry.FullName.StartsWith("net/botwithus/api/") -and $entry.FullName.EndsWith(".class")) {
                $dest = Join-Path $outDir ($entry.FullName -replace '/', '\')
                New-Item -ItemType Directory -Force -Path (Split-Path $dest -Parent) | Out-Null
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
            }
        }
    } finally { $zin.Dispose() }
    Write-Host "Bundled xapi.public API classes into the jar"
} else {
    Write-Host "WARNING: $xapi not found - Dialog/Traverse/Lodestone will be missing at runtime!"
}

# 5. Package build\classes into a jar (a jar is just a zip). Build entries
#    manually so paths use forward slashes (required by the jar/zip spec - the
#    classloader will not find classes stored with backslash separators).
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
$zip = [System.IO.Compression.ZipFile]::Open($jarPath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    $base = (Resolve-Path $outDir).Path.TrimEnd('\') + '\'
    Get-ChildItem -Recurse -File -Path $outDir | ForEach-Object {
        $entryName = $_.FullName.Substring($base.Length).Replace('\', '/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $entryName) | Out-Null
    }
} finally {
    $zip.Dispose()
}
Write-Host "Built: $jarPath"

# 6. Deploy to the client's local-scripts folder
New-Item -ItemType Directory -Force -Path $localDir | Out-Null
Copy-Item -Path $jarPath -Destination $localDir -Force
Write-Host "Deployed to: $localDir"
Write-Host "Done. Reload scripts in the BotWithUs client to see 'Player-Owned Ports'."
