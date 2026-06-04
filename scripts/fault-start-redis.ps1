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
& $docker start mini-seckill-redis
Write-Host "Redis container started. Run scripts/recovery-check.ps1 to rebuild Redis stock."
