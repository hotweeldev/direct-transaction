<#
.SYNOPSIS
    Runs direct-transaction locally with the variables from .env.

.EXAMPLE
    .\run-local.ps1
    .\run-local.ps1 -Build

.NOTES
    Spring Boot does not read .env itself, so this script loads it into the
    process environment before starting the jar.
#>
[CmdletBinding()]
param(
    [switch]$Build,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$JavaArgs
)

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

if (-not (Test-Path '.env')) {
    Write-Error "No .env found. Create one first:  Copy-Item .env.example .env"
}

# Parse KEY=VALUE lines, skipping comments and blanks. Values are taken verbatim
# so the Oracle descriptor's parentheses and '=' signs survive intact.
Get-Content '.env' | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { return }

    $separator = $line.IndexOf('=')
    if ($separator -lt 1) { return }

    $name = $line.Substring(0, $separator).Trim()
    $value = $line.Substring($separator + 1).Trim()
    # Strip one layer of surrounding quotes if present.
    if ($value.Length -ge 2 -and
        (($value.StartsWith('"') -and $value.EndsWith('"')) -or
         ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        $value = $value.Substring(1, $value.Length - 2)
    }

    Set-Item -Path "Env:$name" -Value $value
}

if (-not $env:JAVA_HOME) {
    Write-Error "JAVA_HOME must point at a JDK 25 install, e.g. C:\Program Files\Zulu\zulu-25"
}

$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $java)) {
    Write-Error "No java.exe at $java"
}

if ($Build) {
    mvn clean package
    if ($LASTEXITCODE -ne 0) { Write-Error "Build failed" }
}

$jar = 'target\direct-transaction.jar'
if (-not (Test-Path $jar)) {
    Write-Error "$jar not found. Run '.\run-local.ps1 -Build' or 'mvn package' first."
}

$port = if ($env:SERVER_PORT) { $env:SERVER_PORT } else { '5244' }
$profileName = if ($env:SPRING_PROFILES_ACTIVE) { $env:SPRING_PROFILES_ACTIVE } else { 'dev' }

Write-Output "Starting direct-transaction on port $port (profile $profileName)"
Write-Output "  Swagger UI : http://localhost:$port/swagger-ui.html"
Write-Output "  Health     : http://localhost:$port/actuator/health"

& $java -jar $jar @JavaArgs
