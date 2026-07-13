param(
    [string]$PrometheusBaseUrl = "http://localhost:9090",
    [string]$GrafanaBaseUrl = "http://localhost:3000",
    [string]$OutputDir = (Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")).Path "benchmark\results")
)

$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force $OutputDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"

$targetsFile = Join-Path $OutputDir "$stamp-prometheus-targets.json"
$upFile = Join-Path $OutputDir "$stamp-prometheus-up.json"
$grafanaFile = Join-Path $OutputDir "$stamp-grafana-health.json"

Invoke-RestMethod -Uri "$PrometheusBaseUrl/api/v1/targets" |
    ConvertTo-Json -Depth 20 |
    Tee-Object -FilePath $targetsFile

Invoke-RestMethod -Uri "$PrometheusBaseUrl/api/v1/query?query=up" |
    ConvertTo-Json -Depth 20 |
    Tee-Object -FilePath $upFile

Invoke-RestMethod -Uri "$GrafanaBaseUrl/api/health" |
    ConvertTo-Json -Depth 20 |
    Tee-Object -FilePath $grafanaFile

Write-Host "prometheus_targets=$targetsFile"
Write-Host "prometheus_up=$upFile"
Write-Host "grafana_health=$grafanaFile"
