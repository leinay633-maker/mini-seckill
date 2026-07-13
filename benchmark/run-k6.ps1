param(
  [ValidateSet("unique", "duplicate")]
  [string]$Mode = "unique",
  [int]$Rate = 1000,
  [string]$Duration = "30s",
  [int]$Vus = 1000,
  [int]$MaxVus = 5000,
  [int]$Stock = 1000,
  [int]$DuplicateUsers = 1000,
  [int]$UserBase = 10000000,
  [int]$BaseUserId = 0,
  [int]$TotalUsers = 0,
  [string]$Name = "",
  [switch]$UseHiddenPath,
  [string]$BaseUrl = "http://localhost:8080",
  [string]$UserJwt = "",
  [string]$K6Path = "k6"
)

$ErrorActionPreference = "Stop"
$ResultDir = Join-Path $PSScriptRoot "results"
New-Item -ItemType Directory -Force $ResultDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if ($BaseUserId -gt 0) {
  $UserBase = $BaseUserId
}
if ([string]::IsNullOrWhiteSpace($Name)) {
  $name = "$stamp-$Mode-rate$Rate"
} else {
  $name = "$stamp-$Name"
}
$summary = Join-Path $ResultDir "$name-summary.json"
$stdout = Join-Path $ResultDir "$name-output.txt"

$env:BASE_URL = $BaseUrl
$env:ACTIVITY_ID = "1"
$env:SKU_ID = "1001"
$env:MODE = $Mode
$env:RATE = "$Rate"
$env:DURATION = $Duration
$env:VUS = "$Vus"
$env:MAX_VUS = "$MaxVus"
$env:STOCK = "$Stock"
$env:DUPLICATE_USERS = "$DuplicateUsers"
$env:USER_BASE = "$UserBase"
$env:TOTAL_USERS = "$TotalUsers"
$env:USE_HIDDEN_PATH = $(if ($UseHiddenPath) { "true" } else { $env:USE_HIDDEN_PATH })
$env:USER_JWT = $UserJwt
$env:SUMMARY_JSON = $summary

Write-Host "Running k6: name=$name mode=$Mode rate=$Rate duration=$Duration hiddenPath=$($env:USE_HIDDEN_PATH) summary=$summary"
$oldErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $K6Path run (Join-Path $PSScriptRoot "k6-order.js") 2>&1 | Tee-Object -FilePath $stdout
$exitCode = $LASTEXITCODE
$ErrorActionPreference = $oldErrorActionPreference
Write-Host "stdout=$stdout"
Write-Host "summary=$summary"
if ($exitCode -ne 0) {
  Write-Host "k6 exitCode=$exitCode"
  exit $exitCode
}
