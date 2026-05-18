$ErrorActionPreference = "Continue"
$transcriptFile = Join-Path $PSScriptRoot "rank-test-result.txt"
Start-Transcript -Path $transcriptFile -Force | Out-Null

$gateway = "http://localhost:8080/api"
$rankBase = "http://localhost:8086/rank"
$userBase = "http://localhost:8081/user"
$redisContainer = "biteblog-redis"
$rabbitContainer = "biteblog-rabbitmq"
$redisPassword = "redis123456"
$password = "12345678"
$today = Get-Date -Format "yyyy-MM-dd"
$script:dailyKey = "rank:daily:$today"
$script:failures = 0
$script:dockerAvailable = $null

function Write-Section {
    param([string]$Title)
    Write-Host ""
    Write-Host "===== $Title =====" -ForegroundColor Cyan
}

function Pass {
    param([string]$Message)
    Write-Host "  [PASS] $Message" -ForegroundColor Green
}

function Warn {
    param([string]$Message)
    Write-Host "  [WARN] $Message" -ForegroundColor Yellow
}

function Fail {
    param([string]$Message)
    $script:failures++
    Write-Host "  [FAIL] $Message" -ForegroundColor Red
}

function Invoke-JsonRequest {
    param(
        [string]$Uri,
        [string]$Method = "GET",
        [hashtable]$Headers = @{},
        $Body = $null
    )

    if ($null -ne $Body) {
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 8 -Compress }
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        return Invoke-RestMethod -Uri $Uri -Method $Method -Headers $Headers -ContentType "application/json; charset=utf-8" -Body $bytes -ErrorAction Stop
    }

    return Invoke-RestMethod -Uri $Uri -Method $Method -Headers $Headers -ErrorAction Stop
}

function Test-ContainerRunning {
    param([string]$Name)
    if ($null -eq $script:dockerAvailable) {
        $script:dockerAvailable = $null -ne (Get-Command docker -ErrorAction SilentlyContinue)
    }
    if (-not $script:dockerAvailable) {
        return $false
    }
    $names = & docker ps --format "{{.Names}}" 2>$null
    return @($names) -contains $Name
}

function Invoke-Redis {
    param([string[]]$RedisArgs)
    if (-not (Test-ContainerRunning $redisContainer)) {
        return @()
    }
    $dockerArgs = @("exec", "-e", "REDISCLI_AUTH=$redisPassword", $redisContainer, "redis-cli", "--raw") + $RedisArgs
    $output = & docker $dockerArgs 2>$null
    if ($LASTEXITCODE -ne 0) {
        return @()
    }
    return @($output | Where-Object { $null -ne $_ -and $_.ToString().Trim().Length -gt 0 })
}

function Get-DailyKeys {
    $keys = Invoke-Redis @("KEYS", "rank:daily:*")
    if ($keys.Count -eq 0) {
        return @($script:dailyKey)
    }
    return @($keys | Sort-Object -Descending)
}

function Get-RedisScore {
    param([long]$PostId)

    foreach ($key in (Get-DailyKeys)) {
        $raw = Invoke-Redis @("ZSCORE", $key, "$PostId")
        $scoreText = ($raw | Select-Object -First 1)
        if (-not [string]::IsNullOrWhiteSpace($scoreText)) {
            try {
                return [pscustomobject]@{
                    Key = $key
                    Score = [double]$scoreText
                }
            } catch {
                Warn "Redis score is not numeric. key=$key postId=$PostId raw=$scoreText"
            }
        }
    }
    return $null
}

function Wait-ScoreChange {
    param(
        [long]$PostId,
        [Nullable[Double]]$Before,
        [string]$ActionName
    )

    for ($i = 1; $i -le 16; $i++) {
        Start-Sleep -Milliseconds 500
        $probe = Get-RedisScore $PostId
        if ($null -eq $probe) {
            Write-Host "  try ${i}: score=null" -ForegroundColor DarkGray
            continue
        }

        $script:dailyKey = $probe.Key
        Write-Host "  try ${i}: key=$($probe.Key) score=$($probe.Score)" -ForegroundColor DarkGray
        if ($null -eq $Before -or $probe.Score -gt $Before) {
            Pass "$ActionName score increased: before=$Before after=$($probe.Score)"
            return $probe.Score
        }
    }

    Fail "$ActionName did not increase score within 8s"
    $last = Get-RedisScore $PostId
    if ($null -ne $last) {
        return $last.Score
    }
    return $Before
}

function Show-RedisDailyTop {
    Write-Host "  daily keys found:" -ForegroundColor White
    foreach ($key in (Get-DailyKeys | Select-Object -First 5)) {
        $count = (Invoke-Redis @("ZCARD", $key) | Select-Object -First 1)
        if ([string]::IsNullOrWhiteSpace($count)) {
            $count = "0"
        }
        Write-Host "    $key count=$count" -ForegroundColor White
        $rows = Invoke-Redis @("ZREVRANGE", $key, "0", "9", "WITHSCORES")
        if ($rows.Count -eq 0) {
            Write-Host "      <empty>" -ForegroundColor DarkGray
            continue
        }
        for ($i = 0; $i -lt $rows.Count; $i += 2) {
            $member = $rows[$i]
            $score = if ($i + 1 -lt $rows.Count) { $rows[$i + 1] } else { "" }
            Write-Host "      member=$member score=$score" -ForegroundColor DarkGray
        }
    }
}

function Show-RabbitDiagnostics {
    if (-not (Test-ContainerRunning $rabbitContainer)) {
        Warn "RabbitMQ container $rabbitContainer is not running"
        return
    }

    Write-Host "  RabbitMQ queues:" -ForegroundColor White
    $queues = & docker exec $rabbitContainer rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers 2>$null
    @($queues | Where-Object { $_ -match "rank|interaction|note|name" }) |
        ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }

    Write-Host "  RabbitMQ bindings:" -ForegroundColor White
    $bindings = & docker exec $rabbitContainer rabbitmqctl list_bindings source_name destination_name routing_key 2>$null
    @($bindings | Where-Object { $_ -match "biteblog|rank|interaction|note|source_name" }) |
        ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
}

function Login-TestUser {
    param([string]$Phone)

    $resp = Invoke-JsonRequest -Uri "$userBase/login" -Method POST -Body @{ phone = $Phone; password = $password }
    return @{
        phone = $Phone
        token = $resp.data.token
        userId = $resp.data.userId
        username = $resp.data.username
    }
}

Write-Host "===== Rank Service Verification =====" -ForegroundColor Cyan
Write-Host "Expected daily key: $script:dailyKey" -ForegroundColor White

Write-Section "0. Container Precheck"
if (Test-ContainerRunning $redisContainer) {
    Pass "Redis container is running: $redisContainer"
} else {
    Fail "Redis container is not running: $redisContainer"
}
if (Test-ContainerRunning $rabbitContainer) {
    Pass "RabbitMQ container is running: $rabbitContainer"
} else {
    Warn "RabbitMQ container is not running: $rabbitContainer"
}

try {
    $author = Login-TestUser "13800000001"
    $actor = Login-TestUser "13800000004"
    $authorHeaders = @{ "Authorization" = "Bearer $($author.token)" }
    $actorHeaders = @{ "Authorization" = "Bearer $($actor.token)" }
    Pass "Author login userId=$($author.userId) phone=$($author.phone) username=$($author.username)"
    Pass "Actor login userId=$($actor.userId) phone=$($actor.phone) username=$($actor.username)"
} catch {
    Fail "Login failed. Run sql/init-data.ps1 and start User/Gateway services first: $($_.Exception.Message)"
    Stop-Transcript | Out-Null
    exit 1
}

Write-Section "1. Health Check"
try {
    $health = Invoke-JsonRequest -Uri "$rankBase/health"
    if ($health.data.status -eq "UP") {
        Pass "rank-service status=UP"
    } else {
        Fail "rank-service returned unexpected status=$($health.data.status)"
    }
} catch {
    Fail "health check failed: $($_.Exception.Message)"
}

Write-Section "2. Rebuild daily/weekly/all"
foreach ($type in @("daily", "weekly", "all")) {
    try {
        $resp = Invoke-JsonRequest -Uri "$rankBase/rebuild?type=$type" -Method POST
        if ($resp.data.rebuilt -eq $true) {
            Pass "rebuild $type"
        } else {
            Fail "rebuild $type returned rebuilt=$($resp.data.rebuilt)"
        }
    } catch {
        Fail "rebuild $type failed: $($_.Exception.Message)"
    }
}

Write-Section "3. Top10 Whitelist Latency"
$times = @()
for ($i = 1; $i -le 20; $i++) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        Invoke-JsonRequest -Uri "$gateway/rank/top10?type=daily" | Out-Null
        $sw.Stop()
        $times += $sw.ElapsedMilliseconds
        Write-Host "  try ${i}: $($sw.ElapsedMilliseconds)ms" -ForegroundColor DarkGray
    } catch {
        $sw.Stop()
        Fail "top10 try ${i} failed: $($_.Exception.Message)"
    }
}
if ($times.Count -gt 0) {
    $avg = ($times | Measure-Object -Average).Average
    if ($avg -lt 100) {
        Pass "average latency $([math]::Round($avg, 2))ms (target <100ms)"
    } else {
        Warn "average latency $([math]::Round($avg, 2))ms (target <100ms)"
    }
}

Write-Section "4. Redis Daily ZSet Snapshot"
Show-RedisDailyTop

Write-Section "5. Publish Note -> Rank Event"
$testTitle = "RankEventTest-" + (Get-Date -Format "HHmmss")
$publishBody = @{
    title = $testTitle
    content = "Verify note.published event enters Rank Service and writes Redis Sorted Set."
    shopName = "Rank Test Shop"
    address = "Rank Test Address"
    longitude = 114.30
    latitude = 30.50
    scoreColor = 5
    scoreSmell = 5
    scoreTaste = 5
    imageUrls = @()
}

$postId = $null
try {
    $pub = Invoke-JsonRequest -Uri "$gateway/post/publish" -Method POST -Headers $authorHeaders -Body $publishBody
    $postId = [long]$pub.data.postId
    Pass "published postId=$postId title=$testTitle"
} catch {
    Fail "publish failed: $($_.Exception.Message)"
}

if ($postId) {
    $initialScore = Wait-ScoreChange $postId $null "note.published"
    if ($null -eq $initialScore) {
        Show-RabbitDiagnostics
        Show-RedisDailyTop
    }
}

if ($postId) {
    Write-Section "6. Like/Favorite/Comment -> Rank Score Refresh"

    $before = $initialScore
    try {
        Invoke-JsonRequest -Uri "$gateway/post/$postId/like" -Method POST -Headers $actorHeaders | Out-Null
        Pass "like request sent"
        $afterLike = Wait-ScoreChange $postId $before "like"
    } catch {
        Fail "like failed: $($_.Exception.Message)"
        $afterLike = $before
    }

    try {
        Invoke-JsonRequest -Uri "$gateway/post/$postId/favorite" -Method POST -Headers $actorHeaders | Out-Null
        Pass "favorite request sent"
        $afterFavorite = Wait-ScoreChange $postId $afterLike "favorite"
    } catch {
        Fail "favorite failed: $($_.Exception.Message)"
        $afterFavorite = $afterLike
    }

    try {
        $commentBody = @{ content = "Rank event test comment"; parentId = $null }
        Invoke-JsonRequest -Uri "$gateway/post/$postId/comment" -Method POST -Headers $actorHeaders -Body $commentBody | Out-Null
        Pass "comment request sent"
        $afterComment = Wait-ScoreChange $postId $afterFavorite "comment"
    } catch {
        Fail "comment failed: $($_.Exception.Message)"
    }
}

Write-Section "7. Rank List API"
try {
    $list = Invoke-JsonRequest -Uri "$gateway/rank/list?type=daily&page=1&size=50" -Headers $authorHeaders
    Pass "page=$($list.data.page) size=$($list.data.size) total=$($list.data.total)"
    $found = $false
    foreach ($item in ($list.data.list | Select-Object -First 10)) {
        Write-Host "  #$($item.rankNo) postId=$($item.postId) hotScore=$($item.hotScore) title=$($item.title)" -ForegroundColor DarkGray
        if ($postId -and [long]$item.postId -eq $postId) {
            $found = $true
        }
    }
    if ($postId -and $found) {
        Pass "published post is present in rank list"
    } elseif ($postId) {
        Warn "published postId=$postId is not in the first returned page; Redis score checks above already verified the event path"
    }
} catch {
    Fail "rank list failed: $($_.Exception.Message)"
}

Write-Section "8. Diagnostics"
Show-RedisDailyTop
Show-RabbitDiagnostics

Write-Section "Done"
if ($script:failures -eq 0) {
    Pass "Rank verification passed"
    Write-Host "Result saved to: $transcriptFile" -ForegroundColor Green
    Stop-Transcript | Out-Null
    exit 0
}

Write-Host "  [FAIL] Rank verification finished with $script:failures failure(s)" -ForegroundColor Red
Write-Host "Result saved to: $transcriptFile" -ForegroundColor Yellow
Stop-Transcript | Out-Null
exit 1
