$ErrorActionPreference = "Stop"

function Resolve-Docker {
    $dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
    if ($dockerCommand) {
        return $dockerCommand.Source
    }
    $dockerDesktopCli = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
    if (Test-Path $dockerDesktopCli) {
        return $dockerDesktopCli
    }
    throw "docker executable not found. Please start Docker Desktop first."
}

$docker = Resolve-Docker

Write-Host "Redis Cluster info:"
& $docker exec mini-seckill-redis-cluster-7000 redis-cli -p 7000 cluster info

Write-Host "Redis Cluster nodes:"
& $docker exec mini-seckill-redis-cluster-7000 redis-cli -p 7000 cluster nodes

Write-Host "Cluster app recovery status:"
Invoke-RestMethod -Method Get -Uri "http://localhost:8090/api/recovery/redis/status" | ConvertTo-Json -Depth 8
