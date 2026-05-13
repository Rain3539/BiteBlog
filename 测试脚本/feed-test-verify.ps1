$ErrorActionPreference = "SilentlyContinue"
$base = "http://localhost:8080/api"
$redisContainer = "biteblog-redis"
$redisPassword = "redis123456"

# 登录获取 token
$loginJson = @{ phone = "13800000006"; password = "12345678" } | ConvertTo-Json -Compress
$loginBytes = [System.Text.Encoding]::UTF8.GetBytes($loginJson)
$loginResp = Invoke-RestMethod -Uri "http://localhost:8081/user/login" -Method POST -ContentType "application/json; charset=utf-8" -Body $loginBytes
$token = $loginResp.data.token
$userId = $loginResp.data.userId
$headers = @{ "Authorization" = "Bearer $token"; "X-User-Id" = "$userId" }

Write-Host "===== Feed Service 非功能验证 =====" -ForegroundColor Cyan
Write-Host "用户: $($loginResp.data.username) (userId=$userId)" -ForegroundColor White

# 1. Feed 流响应时间
Write-Host ""
Write-Host "===== 1. Feed 流响应时间 (目标 < 300ms) =====" -ForegroundColor Cyan
$times = @()
for ($i = 1; $i -le 10; $i++) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try { Invoke-RestMethod -Uri "$base/feed/timeline?size=20" -Headers $headers | Out-Null } catch {}
    $sw.Stop()
    $times += $sw.ElapsedMilliseconds
    Write-Host "  第${i}次: $($sw.ElapsedMilliseconds)ms" -ForegroundColor DarkGray
}
$avg = ($times | Measure-Object -Average).Average
$color = if ($avg -lt 300) { "Green" } else { "Yellow" }
Write-Host "  平均: $([math]::Round($avg))ms (目标 <300ms)" -ForegroundColor $color

# 2. 游标分页一致性
Write-Host ""
Write-Host "===== 2. 游标分页一致性 =====" -ForegroundColor Cyan
$cursor = $null
$allIds = @()
for ($page = 1; $page -le 5; $page++) {
    $url = if ($cursor) { "$base/feed/timeline?cursor=$cursor&size=3" } else { "$base/feed/timeline?size=3" }
    try {
        $resp = Invoke-RestMethod -Uri $url -Headers $headers
        $ids = $resp.data.list | ForEach-Object { $_.postId }
        $allIds += $ids
        $cursor = $resp.data.cursor
        $hasMore = $resp.data.hasMore
        Write-Host "  第${page}页: postIds=[$($ids -join ',')] hasMore=$hasMore" -ForegroundColor DarkGray
    } catch {
        Write-Host "  第${page}页: 请求失败" -ForegroundColor Red
    }
}
$dups = $allIds | Group-Object | Where-Object { $_.Count -gt 1 }
if ($dups) {
    Write-Host "  分页验证: 失败 (有重复: $($dups.Name -join ','))" -ForegroundColor Red
} else {
    Write-Host "  分页验证: 通过 (无重复)" -ForegroundColor Green
}

# 3. Fanout 延迟
Write-Host ""
Write-Host "===== 3. Fanout 延迟 (目标秒级) =====" -ForegroundColor Cyan
$publishBody = @{ title = "Fanout延迟测试"; content = "测试Fanout延迟"; shopName = "测试店铺"; address = "测试地址"; imageUrls = @() } | ConvertTo-Json -Depth 5 -Compress
$publishBytes = [System.Text.Encoding]::UTF8.GetBytes($publishBody)
$publishTime = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
Write-Host "  发布时间: $publishTime" -ForegroundColor DarkGray
try { Invoke-RestMethod -Uri "$base/post/publish" -Method POST -Headers $headers -ContentType "application/json; charset=utf-8" -Body $publishBytes | Out-Null } catch {}

$found = $false
for ($i = 1; $i -le 20; $i++) {
    Start-Sleep -Milliseconds 500
    try {
        $feedResp = Invoke-RestMethod -Uri "$base/feed/timeline?size=50" -Headers $headers
        $match = $feedResp.data.list | Where-Object { $_.title -like "*Fanout延迟测试*" }
        if ($match) {
            $delay = $i * 500
            Write-Host "  Fanout 延迟: ${delay}ms" -ForegroundColor Green
            $found = $true
            break
        }
    } catch {}
}
if (-not $found) {
    Write-Host "  Fanout 延迟: 超过 10s" -ForegroundColor Yellow
}

# 4. Redis inbox 空间验证
Write-Host ""
Write-Host "===== 4. Redis inbox 空间验证 =====" -ForegroundColor Cyan
$inboxCount = docker exec $redisContainer redis-cli -a $redisPassword ZCARD "feed:inbox:$userId" 2>$null
Write-Host "  inbox:$userId 条数: $inboxCount (建议上限 500)" -ForegroundColor White

# 5. 大V验证
Write-Host ""
Write-Host "===== 5. 大V inbox 验证 =====" -ForegroundColor Cyan
$bigVs = docker exec $redisContainer redis-cli -a $redisPassword SMEMBERS feed:bigv 2>$null
Write-Host "  feed:bigv: $bigVs" -ForegroundColor White
foreach ($bvid in $bigVs -split "`n") {
    $bvid = $bvid.Trim()
    if ($bvid) {
        $count = docker exec $redisContainer redis-cli -a $redisPassword ZCARD "feed:inbox:$bvid" 2>$null
        Write-Host "  inbox:$bvid 条数: $count" -ForegroundColor DarkGray
    }
}

Write-Host ""
Write-Host "===== JMeter 压测 =====" -ForegroundColor Cyan
Write-Host '运行: & "D:\from_browser\apache-jmeter-5.6.3\bin\jmeter.bat" -n -t jmeter\feed-service-test.jmx -l jmeter\feed-result.jtl -e -o jmeter\feedservice-report' -ForegroundColor White

Write-Host ""
Write-Host "===== 完成 =====" -ForegroundColor Cyan
