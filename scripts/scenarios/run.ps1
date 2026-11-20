<#
.SYNOPSIS
  Pick and run demo scenarios one at a time. For the full end-to-end run use scripts/demo-smoke.ps1.

.EXAMPLE
  .\scripts\scenarios\run.ps1              # menu
  .\scripts\scenarios\run.ps1 5            # one scenario by number
  .\scripts\scenarios\run.ps1 2 3 5        # several, in order
  .\scripts\scenarios\run.ps1 all          # 1-9 in order
#>
param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $Pick)

$scripts = Get-ChildItem $PSScriptRoot -Filter "*.ps1" | Where-Object { $_.Name -match '^\d\d-' } | Sort-Object Name
function Describe($f) { ((Get-Content $f.FullName -TotalCount 1) -replace '^#\s*', '') }

if (-not $Pick) {
    Write-Host "`nDemo scenarios:" -ForegroundColor Cyan
    foreach ($f in $scripts) { Write-Host ("  {0}  {1}" -f $f.Name.Substring(0, 2), (Describe $f)) }
    $answer = Read-Host "`nScenario number(s), space separated, or 'all'"
    $Pick = $answer -split '\s+' | Where-Object { $_ }
}
if ($Pick -contains "all") { $Pick = $scripts | ForEach-Object { $_.Name.Substring(0, 2) } }

$failed = @()
foreach ($p in $Pick) {
    $f = $scripts | Where-Object { $_.Name.StartsWith(("{0:D2}" -f [int]$p)) } | Select-Object -First 1
    if (-not $f) { Write-Host "No scenario $p" -ForegroundColor Red; $failed += $p; continue }
    & powershell -NoProfile -ExecutionPolicy Bypass -File $f.FullName
    if ($LASTEXITCODE -ne 0) { $failed += $f.Name }
}
if ($failed) { Write-Host "`nFailed: $($failed -join ', ')" -ForegroundColor Red; exit 1 }
Write-Host "`nAll selected scenarios passed." -ForegroundColor Green
