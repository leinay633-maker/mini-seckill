param(
    [string] $BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"

Write-Host "Redis recovery status before rebuild:"
Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/recovery/redis/status" | ConvertTo-Json -Depth 8

Write-Host "Trigger Redis stock rebuild:"
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/recovery/redis" | ConvertTo-Json -Depth 8

Write-Host "Redis recovery status after rebuild:"
Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/recovery/redis/status" | ConvertTo-Json -Depth 8
