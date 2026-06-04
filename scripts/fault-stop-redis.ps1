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
& $docker stop mini-seckill-redis
Write-Host "Redis container stopped. Wait about 15 seconds for the app health check to enter recovery mode."
