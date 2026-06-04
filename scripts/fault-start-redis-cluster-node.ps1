$ErrorActionPreference = "Stop"

$docker = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
if (-not (Test-Path $docker)) {
  $docker = "docker"
}

$node = $args[0]
if ([string]::IsNullOrWhiteSpace($node)) {
  $node = "mini-seckill-redis-cluster-7000"
}

Write-Host "Starting Redis Cluster node: $node"
& $docker start $node

Write-Host "Waiting for node to rejoin..."
Start-Sleep -Seconds 10

Write-Host "Cluster info from redis-cluster-7001:"
& $docker exec mini-seckill-redis-cluster-7001 redis-cli -p 7001 cluster info

Write-Host "Cluster nodes from redis-cluster-7001:"
& $docker exec mini-seckill-redis-cluster-7001 redis-cli -p 7001 cluster nodes
