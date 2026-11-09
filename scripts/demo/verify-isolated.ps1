[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$url = $env:DEMO_DB_URL
if ([string]::IsNullOrWhiteSpace($url) -or $url -notmatch '(?i)banking_ax_(?:demo_|t2_verify)' -or $url -match '(?i)(?:/|:)banking_ax(?:\?|$)') {
    throw 'Refusing unsafe database target. DEMO_DB_URL must name banking_ax_demo_* or banking_ax_t2_verify.'
}
Write-Host 'Static verification checklist:'
@(
    'Flyway V14: C02ClarificationLocalMySqlIT and VisitReservationManualLocalMySqlIT require V14 tables/statuses.',
    'C-02 owner/revision/file integrity: C02DocumentReviewLocalMySqlIT.',
    'clarification/statement retention and recheck: C02ClarificationLocalMySqlIT.',
    'manual assignment/change/expiry/concurrency: VisitReservationManualLocalMySqlIT.',
    'policy v2/v3 approval history: PolicyChangeLocalMySqlIT.'
) | ForEach-Object { Write-Host " - $_" }
Write-Host 'Run seed-isolated.ps1 -Mode BuildAndVerify to execute this checklist against the guarded schema.'
