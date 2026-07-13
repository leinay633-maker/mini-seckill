param(
  [string]$InstallRoot = "D:\后端项目文件\tools\k6",
  [string]$FallbackVersion = "v2.0.0"
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force $InstallRoot | Out-Null

$existing = Get-Command k6 -ErrorAction SilentlyContinue
if ($existing) {
  Write-Host "k6 already available: $($existing.Source)"
  & $existing.Source version
  exit 0
}

$assetName = $null
$assetUrl = $null
try {
  $release = Invoke-RestMethod -Uri "https://api.github.com/repos/grafana/k6/releases/latest"
  $asset = $release.assets | Where-Object { $_.name -match "windows-amd64\.zip$" } | Select-Object -First 1
  if ($asset) {
    $assetName = $asset.name
    $assetUrl = $asset.browser_download_url
  }
} catch {
  Write-Host "GitHub API lookup failed, using fallback version $FallbackVersion"
}

if (-not $assetUrl) {
  $assetName = "k6-$FallbackVersion-windows-amd64.zip"
  $assetUrl = "https://github.com/grafana/k6/releases/download/$FallbackVersion/k6-$FallbackVersion-windows-amd64.zip"
}

$zipPath = Join-Path $InstallRoot $assetName
Write-Host "Downloading $assetUrl to $zipPath"
try {
  Invoke-WebRequest -Uri $assetUrl -OutFile $zipPath
} catch {
  Write-Host "Invoke-WebRequest failed, retrying with curl.exe"
  if (Test-Path $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
  }
  curl.exe -L --retry 5 --retry-delay 3 --retry-all-errors -o $zipPath $assetUrl
  if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
  }
}

Expand-Archive -Path $zipPath -DestinationPath $InstallRoot -Force
$k6 = Get-ChildItem -Path $InstallRoot -Recurse -Filter k6.exe | Select-Object -First 1
if (-not $k6) {
  throw "Downloaded archive did not contain k6.exe"
}

Write-Host "k6 installed at $($k6.FullName)"
& $k6.FullName version
