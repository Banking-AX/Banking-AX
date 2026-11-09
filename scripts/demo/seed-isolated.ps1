[CmdletBinding()]
param(
    [ValidateSet('DryRun','BuildAndVerify')]
    [string]$Mode = 'DryRun'
)

$ErrorActionPreference = 'Stop'
$url = $env:DEMO_DB_URL
if ([string]::IsNullOrWhiteSpace($url)) { throw 'DEMO_DB_URL is required.' }
if ($url -match '(?i)(?:/|:)banking_ax(?:\?|$)' -or $url -notmatch '(?i)banking_ax_(?:demo_|t2_verify)') {
    throw 'Refusing unsafe database target. DEMO_DB_URL must name banking_ax_demo_* or banking_ax_t2_verify.'
}
if ([string]::IsNullOrWhiteSpace($env:APP_DB_USERNAME) -or [string]::IsNullOrWhiteSpace($env:APP_DB_PASSWORD)) {
    throw 'APP_DB_USERNAME and APP_DB_PASSWORD are required; values are intentionally not printed.'
}

Write-Host "Isolated target accepted: $($url -replace ':[^:@/]+@', ':***@')"
Write-Host 'Shared banking_ax, running services, and external providers are not used.'
if ($Mode -eq 'DryRun') {
    Write-Host 'Dry run complete. No database command was executed.'
    exit 0
}

$env:T2_VERIFY_DB_URL = $url
Push-Location (Join-Path $PSScriptRoot '..\..\backend')
try {
    # These classes use real Spring services/JDBC/Flyway. They may truncate only the guarded demo schema.
    $maven = if (Get-Command mvn -ErrorAction SilentlyContinue) { 'mvn' }
             elseif (Test-Path 'C:\Temp\apache-maven-3.9.9\bin\mvn.cmd') { 'C:\Temp\apache-maven-3.9.9\bin\mvn.cmd' }
             else { throw 'Maven was not found. Install Maven or set it on PATH; no database change was made.' }
    & $maven '-Dtest=C02ClarificationLocalMySqlIT,C02DocumentReviewLocalMySqlIT,C02DraftAndBriefLocalMySqlIT,VisitReservationManualLocalMySqlIT,PolicyChangeLocalMySqlIT' test
    if ($LASTEXITCODE -ne 0) { throw "Isolated fixture verification failed ($LASTEXITCODE)." }
} finally {
    Pop-Location
}

Write-Host 'PASS: isolated service-backed fixtures and verification tests completed.'
Write-Host 'Note: tests reset their own fixture state. Use fixtures/demo-scenarios.json and DEMO_RUNBOOK.md to create a durable browser-demo set only after a dedicated API-backed seeder is approved.'
