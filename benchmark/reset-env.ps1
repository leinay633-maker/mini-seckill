param(
  [int]$Stock = 1000,
  [int]$ActivityId = 1,
  [int]$SkuId = 1001,
  [string]$BaseUrl = "http://localhost:8080",
  [switch]$SkipHttpInit
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$ResultDir = Join-Path $PSScriptRoot "results"
New-Item -ItemType Directory -Force $ResultDir | Out-Null

function Run-Step($Name, [scriptblock]$Block) {
  Write-Host "== $Name =="
  & $Block
}

Run-Step "reset mysql tables" {
  Get-Content (Join-Path $PSScriptRoot "reset.sql") | docker exec -i mini-seckill-mysql mysql -uminiseckill -pminiseckill mini_seckill
}

Run-Step "clear redis seckill keys" {
  docker exec mini-seckill-redis sh -c "redis-cli --scan --pattern 'seckill:*' | xargs -r redis-cli del"
}

Run-Step "purge rabbitmq queues" {
  docker exec mini-seckill-rabbitmq rabbitmqctl purge_queue mini.seckill.order.queue 2>$null
  docker exec mini-seckill-rabbitmq rabbitmqctl purge_queue mini.seckill.dead.queue 2>$null
}

if (-not $SkipHttpInit) {
  Run-Step "initialize stock through application" {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/seckill/init?activityId=$ActivityId&skuId=$SkuId&stock=$Stock" | ConvertTo-Json -Depth 6
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/seckill/warmup?activityId=$ActivityId&skuId=$SkuId" | ConvertTo-Json -Depth 6
    Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/seckill/stock?activityId=$ActivityId&skuId=$SkuId" | ConvertTo-Json -Depth 6
  }
}
