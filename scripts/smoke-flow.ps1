param(
  [switch]$UseJwt,
  [switch]$UseCaptcha,
  [switch]$UseHiddenPath,
  [string]$BaseUrl = "http://localhost:8080",
  [string]$Username = "demo",
  [string]$Password = "demo123456",
  [long]$ActivityId = 1,
  [long]$SkuId = 1001,
  [long]$UserId = 10001,
  [int]$PollAttempts = 10,
  [int]$PollDelaySeconds = 1,
  [string]$OutputDir = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path $PSScriptRoot -Parent
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
  $OutputDir = Join-Path $RepoRoot "benchmark\results"
} elseif (-not [System.IO.Path]::IsPathRooted($OutputDir)) {
  $OutputDir = Join-Path $RepoRoot $OutputDir
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$BaseUrl = $BaseUrl.TrimEnd("/")
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$EvidencePath = Join-Path $OutputDir "$Stamp-smoke-flow-evidence.json"

$script:Evidence = [ordered]@{
  status = "running"
  generatedAt = (Get-Date).ToString("o")
  baseUrl = $BaseUrl
  parameters = [ordered]@{
    useJwt = [bool]$UseJwt
    useCaptcha = [bool]$UseCaptcha
    useHiddenPath = [bool]$UseHiddenPath
    username = $Username
    activityId = $ActivityId
    skuId = $SkuId
    userId = $UserId
    pollAttempts = $PollAttempts
    pollDelaySeconds = $PollDelaySeconds
  }
  steps = @()
  failure = $null
  result = $null
}

function Write-Evidence {
  $script:Evidence["updatedAt"] = (Get-Date).ToString("o")
  $script:Evidence | ConvertTo-Json -Depth 60 | Set-Content -Path $EvidencePath -Encoding UTF8
}

function Add-Step {
  param([System.Collections.IDictionary]$Step)
  $script:Evidence["steps"] += @($Step)
  Write-Evidence
}

function Fail-Flow {
  param(
    [string]$StepName,
    [string]$Message,
    [object]$Details = $null
  )

  $script:Evidence["status"] = "failed"
  $script:Evidence["failure"] = [ordered]@{
    step = $StepName
    message = $Message
    details = $Details
  }
  Write-Evidence
  [Console]::Error.WriteLine("smoke-flow failed at step '$StepName': $Message. evidence=$EvidencePath")
  exit 1
}

function ConvertTo-QueryString {
  param([System.Collections.IDictionary]$Query)

  $pairs = @()
  foreach ($key in $Query.Keys) {
    $value = $Query[$key]
    if ($null -eq $value) {
      continue
    }
    $text = [string]$value
    if ([string]::IsNullOrWhiteSpace($text)) {
      continue
    }
    $encodedKey = [System.Net.WebUtility]::UrlEncode([string]$key)
    $encodedValue = [System.Net.WebUtility]::UrlEncode($text)
    $pairs += "$encodedKey=$encodedValue"
  }

  if ($pairs.Count -eq 0) {
    return ""
  }
  return "?" + ($pairs -join "&")
}

function New-ApiUrl {
  param(
    [string]$Path,
    [System.Collections.IDictionary]$Query = @{}
  )

  return "$BaseUrl$Path$(ConvertTo-QueryString -Query $Query)"
}

function ConvertFrom-JsonOrNull {
  param([string]$Content)

  if ([string]::IsNullOrWhiteSpace($Content)) {
    return $null
  }
  try {
    return $Content | ConvertFrom-Json
  } catch {
    return $null
  }
}

function Read-ErrorResponseBody {
  param([object]$Response)

  if ($null -eq $Response) {
    return ""
  }

  try {
    if ($Response.PSObject.Properties.Name -contains "Content") {
      $contentValue = $Response.Content
      if ($null -ne $contentValue) {
        if ($contentValue -is [string]) {
          return $contentValue
        }
        $asyncResult = $contentValue.ReadAsStringAsync()
        $asyncResult.Wait()
        return $asyncResult.Result
      }
    }
  } catch {
  }

  try {
    $stream = $Response.GetResponseStream()
    if ($null -ne $stream) {
      $reader = New-Object System.IO.StreamReader($stream)
      return $reader.ReadToEnd()
    }
  } catch {
  }

  return ""
}

function Invoke-ApiStep {
  param(
    [string]$StepName,
    [ValidateSet("GET", "POST")]
    [string]$Method,
    [string]$Url,
    [object]$Body = $null,
    [hashtable]$Headers = @{},
    [object]$RequestForEvidence = $null
  )

  $started = Get-Date
  $bodyJson = $null
  if ($null -ne $Body) {
    $bodyJson = $Body | ConvertTo-Json -Depth 20 -Compress
  }

  $invokeParams = @{
    Uri = $Url
    Method = $Method
    Headers = $Headers
  }
  $commandParams = (Get-Command Invoke-WebRequest).Parameters
  if ($commandParams.ContainsKey("UseBasicParsing")) {
    $invokeParams.UseBasicParsing = $true
  }
  if ($commandParams.ContainsKey("SkipHttpErrorCheck")) {
    $invokeParams.SkipHttpErrorCheck = $true
  }
  if ($null -ne $bodyJson) {
    $invokeParams.Body = $bodyJson
    $invokeParams.ContentType = "application/json"
  }

  try {
    $response = Invoke-WebRequest @invokeParams
    $content = [string]$response.Content
    $statusCode = [int]$response.StatusCode
    $json = ConvertFrom-JsonOrNull -Content $content
    $elapsedMs = [int]((Get-Date) - $started).TotalMilliseconds
    $step = [ordered]@{
      step = $StepName
      method = $Method
      url = $Url
      request = $RequestForEvidence
      httpStatus = $statusCode
      elapsedMs = $elapsedMs
      response = [ordered]@{
        raw = $content
        json = $json
      }
    }
    Add-Step -Step $step

    if ($statusCode -lt 200 -or $statusCode -ge 300) {
      Fail-Flow -StepName $StepName -Message "HTTP status $statusCode" -Details $step
    }

    return $step
  } catch {
    $response = $_.Exception.Response
    $content = Read-ErrorResponseBody -Response $response
    $statusCode = $null
    try {
      if ($null -ne $response -and ($response.PSObject.Properties.Name -contains "StatusCode")) {
        $statusCode = [int]$response.StatusCode
      }
    } catch {
      $statusCode = $null
    }
    $elapsedMs = [int]((Get-Date) - $started).TotalMilliseconds
    $step = [ordered]@{
      step = $StepName
      method = $Method
      url = $Url
      request = $RequestForEvidence
      httpStatus = $statusCode
      elapsedMs = $elapsedMs
      exception = $_.Exception.Message
      response = [ordered]@{
        raw = $content
        json = (ConvertFrom-JsonOrNull -Content $content)
      }
    }
    Add-Step -Step $step
    Fail-Flow -StepName $StepName -Message $_.Exception.Message -Details $step
  }
}

function Assert-ResultOk {
  param(
    [string]$StepName,
    [System.Collections.IDictionary]$Step
  )

  $json = $Step["response"]["json"]
  if ($null -eq $json) {
    Fail-Flow -StepName $StepName -Message "response is not JSON" -Details $Step
  }
  if (-not ($json.PSObject.Properties.Name -contains "code")) {
    Fail-Flow -StepName $StepName -Message "response JSON does not contain code" -Details $Step
  }
  if ([int]$json.code -ne 0) {
    $message = $json.message
    if ([string]::IsNullOrWhiteSpace($message)) {
      $message = "API result code $($json.code)"
    }
    Fail-Flow -StepName $StepName -Message $message -Details $Step
  }
  return $json.data
}

function Resolve-MathCaptchaAnswer {
  param([string]$Question)

  if ($Question -match "(\d+)\s*\+\s*(\d+)") {
    return [string]([int]$matches[1] + [int]$matches[2])
  }
  Fail-Flow -StepName "captcha_answer" -Message "unsupported captcha question: $Question"
}

if ($PollAttempts -lt 1) {
  Fail-Flow -StepName "validate_parameters" -Message "PollAttempts must be greater than 0"
}
if ($PollDelaySeconds -lt 0) {
  Fail-Flow -StepName "validate_parameters" -Message "PollDelaySeconds must be greater than or equal to 0"
}

Write-Evidence

$headers = @{}
if ($UseJwt) {
  $loginRequest = [ordered]@{
    username = $Username
    password = $Password
  }
  $loginEvidenceRequest = [ordered]@{
    username = $Username
    password = "***"
  }
  $loginStep = Invoke-ApiStep `
    -StepName "login" `
    -Method "POST" `
    -Url (New-ApiUrl -Path "/api/auth/login") `
    -Body $loginRequest `
    -Headers $headers `
    -RequestForEvidence $loginEvidenceRequest
  $loginData = Assert-ResultOk -StepName "login" -Step $loginStep
  if ($null -eq $loginData -or [string]::IsNullOrWhiteSpace($loginData.accessToken)) {
    Fail-Flow -StepName "login" -Message "login response does not contain accessToken" -Details $loginStep
  }
  $headers["Authorization"] = "$($loginData.tokenType) $($loginData.accessToken)"
}

$captchaId = $null
$captchaAnswer = $null
if ($UseCaptcha) {
  $captchaQuery = [ordered]@{
    activityId = $ActivityId
    userId = $UserId
    skuId = $SkuId
  }
  $captchaStep = Invoke-ApiStep `
    -StepName "captcha" `
    -Method "GET" `
    -Url (New-ApiUrl -Path "/api/captcha/math" -Query $captchaQuery) `
    -Headers $headers `
    -RequestForEvidence $captchaQuery
  $captchaData = Assert-ResultOk -StepName "captcha" -Step $captchaStep
  if ($null -eq $captchaData -or [string]::IsNullOrWhiteSpace($captchaData.captchaId)) {
    Fail-Flow -StepName "captcha" -Message "captcha response does not contain captchaId" -Details $captchaStep
  }
  $captchaId = $captchaData.captchaId
  $captchaAnswer = Resolve-MathCaptchaAnswer -Question $captchaData.question
}

$tokenQuery = [ordered]@{
  activityId = $ActivityId
  userId = $UserId
  skuId = $SkuId
  captchaId = $captchaId
  captchaAnswer = $captchaAnswer
}
$tokenStep = Invoke-ApiStep `
  -StepName "get_token" `
  -Method "GET" `
  -Url (New-ApiUrl -Path "/api/seckill/token" -Query $tokenQuery) `
  -Headers $headers `
  -RequestForEvidence $tokenQuery
$tokenData = Assert-ResultOk -StepName "get_token" -Step $tokenStep
if ($null -eq $tokenData -or [string]::IsNullOrWhiteSpace($tokenData.token)) {
  Fail-Flow -StepName "get_token" -Message "token response does not contain token" -Details $tokenStep
}

$orderPath = $tokenData.orderPath
$orderEndpoint = "/api/seckill/order"
if ($UseHiddenPath) {
  if ([string]::IsNullOrWhiteSpace($orderPath)) {
    Fail-Flow -StepName "place_order" -Message "UseHiddenPath was set, but token response did not contain orderPath" -Details $tokenStep
  }
  $orderEndpoint = "/api/seckill/order/$([System.Uri]::EscapeDataString([string]$orderPath))"
}

$orderRequest = [ordered]@{
  activityId = $ActivityId
  userId = $UserId
  skuId = $SkuId
  token = $tokenData.token
}
$orderEvidenceRequest = [ordered]@{
  activityId = $ActivityId
  userId = $UserId
  skuId = $SkuId
  token = "***"
  orderPath = $(if ($UseHiddenPath) { $orderPath } else { $null })
}
$orderStep = Invoke-ApiStep `
  -StepName "place_order" `
  -Method "POST" `
  -Url (New-ApiUrl -Path $orderEndpoint) `
  -Body $orderRequest `
  -Headers $headers `
  -RequestForEvidence $orderEvidenceRequest
Assert-ResultOk -StepName "place_order" -Step $orderStep | Out-Null

$finalOrder = $null
for ($attempt = 1; $attempt -le $PollAttempts; $attempt++) {
  $query = [ordered]@{
    activityId = $ActivityId
    userId = $UserId
    skuId = $SkuId
  }
  $pollStepName = "poll_order_$attempt"
  $pollStep = Invoke-ApiStep `
    -StepName $pollStepName `
    -Method "GET" `
    -Url (New-ApiUrl -Path "/api/order/query" -Query $query) `
    -Headers $headers `
    -RequestForEvidence $query
  $orderData = Assert-ResultOk -StepName $pollStepName -Step $pollStep
  $finalOrder = $orderData
  if ($null -eq $orderData -or -not ($orderData.PSObject.Properties.Name -contains "status")) {
    Fail-Flow -StepName $pollStepName -Message "order query response does not contain status" -Details $pollStep
  }

  $status = [int]$orderData.status
  if ($status -eq 2) {
    $script:Evidence["status"] = "succeeded"
    $script:Evidence["result"] = [ordered]@{
      evidencePath = $EvidencePath
      tokenIssued = $true
      hiddenPathUsed = [bool]$UseHiddenPath
      order = $orderData
    }
    Write-Evidence
    Write-Host "smoke-flow succeeded. evidence=$EvidencePath"
    exit 0
  }

  if ($status -in @(3, 4, 5)) {
    Fail-Flow -StepName $pollStepName -Message "order reached non-success terminal status $status ($($orderData.statusText))" -Details $pollStep
  }

  if ($attempt -lt $PollAttempts -and $PollDelaySeconds -gt 0) {
    Start-Sleep -Seconds $PollDelaySeconds
  }
}

Fail-Flow `
  -StepName "poll_order" `
  -Message "order did not reach success after $PollAttempts attempts" `
  -Details $finalOrder
