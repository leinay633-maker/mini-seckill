param(
  [ValidateSet("unique", "duplicate")]
  [string]$Mode = "unique",
  [int]$Rate = 20,
  [string]$Duration = "30s",
  [int]$Vus = 100,
  [int]$MaxVus = 500,
  [int]$Stock = 100,
  [int]$DuplicateUsers = 100,
  [int]$BaseUserId = 90000000,
  [string]$BaseUrl = "http://localhost:8080",
  [switch]$UseHiddenPath,
  [string]$Name = "smoke"
)

$ErrorActionPreference = "Stop"

$suiteName = "$Name-$Mode-rate$Rate"

Write-Host "== reset environment =="
& (Join-Path $PSScriptRoot "reset-env.ps1") `
  -ActivityId 1 `
  -SkuId 1001 `
  -Stock $Stock `
  -BaseUrl $BaseUrl

Write-Host "== run k6 =="
& (Join-Path $PSScriptRoot "run-k6.ps1") `
  -Name $suiteName `
  -Mode $Mode `
  -Rate $Rate `
  -Duration $Duration `
  -Vus $Vus `
  -MaxVus $MaxVus `
  -Stock $Stock `
  -DuplicateUsers $DuplicateUsers `
  -BaseUserId $BaseUserId `
  -BaseUrl $BaseUrl `
  -UseHiddenPath:$UseHiddenPath

Write-Host "== verify =="
& (Join-Path $PSScriptRoot "run-verify.ps1") -Name "$suiteName-after"
