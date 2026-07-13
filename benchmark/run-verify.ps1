param(
  [string]$Name = "verify"
)

$ErrorActionPreference = "Stop"
$ResultDir = Join-Path $PSScriptRoot "results"
New-Item -ItemType Directory -Force $ResultDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = Join-Path $ResultDir "$stamp-$Name-mysql-verify.txt"
$redis = Join-Path $ResultDir "$stamp-$Name-redis.txt"
$queues = Join-Path $ResultDir "$stamp-$Name-rabbitmq-queues.txt"

Get-Content (Join-Path $PSScriptRoot "verify.sql") |
  docker exec -i mini-seckill-mysql mysql -uminiseckill -pminiseckill mini_seckill |
  Tee-Object -FilePath $out

docker exec mini-seckill-redis sh -c "redis-cli get seckill:stock:1:1001; redis-cli --scan --pattern 'seckill:stock:1:1001:bucket:*' | wc -l; redis-cli --scan --pattern 'seckill:token:*' | wc -l; redis-cli --scan --pattern 'seckill:user:1:*:sku:1001' | wc -l" |
  Tee-Object -FilePath $redis

docker exec mini-seckill-rabbitmq rabbitmqctl list_queues name messages messages_ready messages_unacknowledged |
  Tee-Object -FilePath $queues

Write-Host "mysql_verify=$out"
Write-Host "redis=$redis"
Write-Host "queues=$queues"
