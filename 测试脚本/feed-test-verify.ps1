$ErrorActionPreference = "SilentlyContinue"
$transcriptFile = Join-Path $PSScriptRoot "feed-test-result.txt"
Start-Transcript -Path $transcriptFile -Force | Out-Null
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
    try { Invoke-RestMethod -Uri "$base/feed/timeline?size=20" -Headers $headers | Out-Null } catch { Write-Host "  请求失败: $($_.Exception.Message)" -ForegroundColor Red }
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
        if (-not $hasMore) { break }
    } catch {
        Write-Host "  第${page}页: 请求失败 - $($_.Exception.Message)" -ForegroundColor Red
    }
}
$dups = $allIds | Group-Object | Where-Object { $_.Count -gt 1 }
if ($dups) {
    Write-Host "  分页验证: 失败 (有重复: $($dups.Name -join ','))" -ForegroundColor Red
} else {
    Write-Host "  分页验证: 通过 (无重复)" -ForegroundColor Green
}

# 3. Fanout 延迟（真实MQ推送：发布者发帖，粉丝查feed）
Write-Host ""
Write-Host "===== 3. Fanout 延迟 (目标秒级) =====" -ForegroundColor Cyan

# 登录一个粉丝账号
$followerLogin = @{ phone = "13800000009"; password = "12345678" } | ConvertTo-Json -Compress
$followerBytes = [System.Text.Encoding]::UTF8.GetBytes($followerLogin)
$followerResp = Invoke-RestMethod -Uri "http://localhost:8081/user/login" -Method POST -ContentType "application/json; charset=utf-8" -Body $followerBytes
$followerId = $followerResp.data.userId
$followerToken = $followerResp.data.token
$followerHeaders = @{ "Authorization" = "Bearer $followerToken"; "X-User-Id" = "$followerId" }
Write-Host "  发布者: $($loginResp.data.username) (userId=$userId)" -ForegroundColor White
Write-Host "  粉丝: $($followerResp.data.username) (userId=$followerId)" -ForegroundColor White

# 先取关再关注，确保关注关系正确 (POST /api/user/follow/{targetUserId})
try {
    Invoke-RestMethod -Uri "$base/user/follow/$userId" -Method POST -Headers $followerHeaders | Out-Null
    Write-Host "  已确保粉丝关注了发布者" -ForegroundColor DarkGray
} catch { Write-Host "  关注失败: $($_.Exception.Message)" -ForegroundColor Red }

$testTitle = "Fanout延迟测试"
$publishBody = @{ title = $testTitle; content = "测试Fanout延迟"; shopName = "测试店铺"; address = "测试地址"; imageUrls = @() } | ConvertTo-Json -Depth 5 -Compress
$publishBytes = [System.Text.Encoding]::UTF8.GetBytes($publishBody)
$publishTime = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
Write-Host "  发布时间: $publishTime" -ForegroundColor DarkGray
try {
    $pubResp = Invoke-RestMethod -Uri "$base/post/publish" -Method POST -Headers $headers -ContentType "application/json; charset=utf-8" -Body $publishBytes
    $newNoteId = $pubResp.data.postId
    Write-Host "  发布成功, postId=$newNoteId" -ForegroundColor Green
} catch { Write-Host "  发布失败: $($_.Exception.Message)" -ForegroundColor Red }

$found = $false
for ($i = 1; $i -le 20; $i++) {
    Start-Sleep -Milliseconds 500
    try {
        $feedResp = Invoke-RestMethod -Uri "$base/feed/timeline?size=50" -Headers $followerHeaders
        $topIds = ($feedResp.data.list | Select-Object -First 3).postId -join ","
        Write-Host "  第${i}次: top3=[$topIds] listCount=$($feedResp.data.list.Count)" -ForegroundColor DarkGray
        $match = $feedResp.data.list | Where-Object { $_.postId -eq $newNoteId }
        if ($match) {
            $delay = $i * 500
            Write-Host "  Fanout 延迟: ${delay}ms (postId=$newNoteId 已出现在粉丝feed)" -ForegroundColor Green
            $found = $true
            break
        }
    } catch { Write-Host "  轮询请求失败: $($_.Exception.Message)" -ForegroundColor Red }
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

# 6. FC-1: Fanout 写入一致性 — 发布后检查粉丝 inbox
Write-Host ""
Write-Host "===== 6. FC-1: Fanout 写入一致性 =====" -ForegroundColor Cyan

# 获取发布者的粉丝列表
$fanSet = docker exec $redisContainer redis-cli -a $redisPassword SMEMBERS "fans:$userId" 2>$null
$fans = $fanSet -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ }
Write-Host "  发布者粉丝: [$($fans -join ',')]" -ForegroundColor DarkGray

if ($fans.Count -gt 0) {
    # 发布一条测试笔记
    $fc1Title = "FC1-Fanout一致性测试"
    $fc1Body = @{ title = $fc1Title; content = "测试fanout写入一致性"; shopName = "FC1店铺" } | ConvertTo-Json -Compress
    $fc1Bytes = [System.Text.Encoding]::UTF8.GetBytes($fc1Body)
    try {
        $fc1Resp = Invoke-RestMethod -Uri "$base/post/publish" -Method POST -Headers $headers -ContentType "application/json; charset=utf-8" -Body $fc1Bytes
        $fc1NoteId = $fc1Resp.data.postId
        Write-Host "  发布成功, postId=$fc1NoteId" -ForegroundColor Green
    } catch { Write-Host "  发布失败: $($_.Exception.Message)" -ForegroundColor Red; $fc1NoteId = $null }

    if ($fc1NoteId) {
        Start-Sleep -Seconds 1
        $allOk = $true
        foreach ($fan in $fans) {
            $zscore = docker exec $redisContainer redis-cli -a $redisPassword ZSCORE "feed:inbox:$fan" $fc1NoteId 2>$null
            if ($zscore) {
                Write-Host "  粉丝${fan} inbox: noteId=$fc1NoteId score=$zscore OK" -ForegroundColor DarkGray
            } else {
                Write-Host "  粉丝${fan} inbox: noteId=$fc1NoteId NOT FOUND" -ForegroundColor Red
                $allOk = $false
            }
        }
        if ($allOk) {
            Write-Host "  FC-1 Fanout写入一致性: 通过" -ForegroundColor Green
        } else {
            Write-Host "  FC-1 Fanout写入一致性: 失败 (部分粉丝未收到)" -ForegroundColor Red
        }
    }
} else {
    Write-Host "  FC-1: 无粉丝，跳过" -ForegroundColor Yellow
}

# 7. FC-2: 大V不Fanout — 验证大V的帖子不会推送到粉丝inbox
Write-Host ""
Write-Host "===== 7. FC-2: 大V不Fanout =====" -ForegroundColor Cyan

$bigVList = docker exec $redisContainer redis-cli -a $redisPassword SMEMBERS feed:bigv 2>$null
$bigVId = ($bigVList -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ } | Select-Object -First 1)
if ($bigVId) {
    Write-Host "  大V: userId=$bigVId" -ForegroundColor White
    # 找大V的一个粉丝
    $bigvFans = docker exec $redisContainer redis-cli -a $redisPassword SMEMBERS "fans:$bigVId" 2>$null
    $bigvFan = ($bigvFans -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ } | Select-Object -First 1)
    if ($bigvFan) {
        Write-Host "  大V粉丝: userId=$bigvFan" -ForegroundColor White
        # 检查粉丝inbox中是否包含大V的笔记（大V笔记不应在粉丝inbox中）
        $fanInbox = docker exec $redisContainer redis-cli -a $redisPassword ZRANGE "feed:inbox:$bigvFan" 0 -1 2>$null
        $bigvInbox = docker exec $redisContainer redis-cli -a $redisPassword ZRANGE "feed:inbox:$bigVId" 0 -1 2>$null
        $bigvNoteIds = ($bigvInbox -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
        $fanNoteIds = ($fanInbox -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })

        # 大V的笔记不应该通过fanout出现在粉丝inbox中
        $leaked = $bigvNoteIds | Where-Object { $_ -in $fanNoteIds }
        if ($leaked) {
            Write-Host "  FC-2 大V不Fanout: 失败 (大V笔记泄露到粉丝inbox: $($leaked -join ','))" -ForegroundColor Red
        } else {
            Write-Host "  FC-2 大V不Fanout: 通过 (粉丝inbox无大V笔记交集)" -ForegroundColor Green
        }
    } else {
        Write-Host "  FC-2: 大V无粉丝，跳过验证" -ForegroundColor Yellow
    }
} else {
    Write-Host "  FC-2: 无大V，跳过" -ForegroundColor Yellow
}

# 8. FC-3 + 可靠性: Inbox预热 / Redis降级MySQL — 清空inbox后验证降级并回填
Write-Host ""
Write-Host "===== 8. FC-3 + 可靠性: Inbox预热 / 降级MySQL =====" -ForegroundColor Cyan

# 先记录当前inbox内容
$inboxBefore = docker exec $redisContainer redis-cli -a $redisPassword ZCARD "feed:inbox:$userId" 2>$null
Write-Host "  清空前 inbox:$userId 条数: $inboxBefore" -ForegroundColor DarkGray

# 删除inbox key模拟缓存失效
docker exec $redisContainer redis-cli -a $redisPassword DEL "feed:inbox:$userId" 2>$null | Out-Null
$inboxAfterDel = docker exec $redisContainer redis-cli -a $redisPassword ZCARD "feed:inbox:$userId" 2>$null
Write-Host "  删除后 inbox 条数: $inboxAfterDel" -ForegroundColor DarkGray

# 请求feed（应触发MySQL降级 + 回填）
try {
    $fallbackResp = Invoke-RestMethod -Uri "$base/feed/timeline?size=20" -Headers $headers
    $fallbackCount = $fallbackResp.data.list.Count
    Write-Host "  降级查询返回: ${fallbackCount}条" -ForegroundColor White

    # 验证inbox被回填
    Start-Sleep -Milliseconds 500
    $inboxAfterFill = docker exec $redisContainer redis-cli -a $redisPassword ZCARD "feed:inbox:$userId" 2>$null
    Write-Host "  回填后 inbox 条数: $inboxAfterFill" -ForegroundColor White

    if ($fallbackCount -gt 0 -and [int]$inboxAfterFill -gt 0) {
        Write-Host "  FC-3 + 降级验证: 通过 (MySQL降级成功并回填inbox)" -ForegroundColor Green
    } elseif ($fallbackCount -gt 0) {
        Write-Host "  FC-3 + 降级验证: 部分通过 (降级成功但inbox未回填)" -ForegroundColor Yellow
    } else {
        Write-Host "  FC-3 + 降级验证: 失败" -ForegroundColor Red
    }
} catch {
    Write-Host "  FC-3 + 降级验证: 失败 ($($_.Exception.Message))" -ForegroundColor Red
}

# 9. FC-4: 删除笔记清理 — 删除后检查 feed:deleted + feed过滤
Write-Host ""
Write-Host "===== 9. FC-4: 删除笔记清理 =====" -ForegroundColor Cyan

# 发布一条临时笔记用于删除
$fc4Title = "FC4-删除测试-$(Get-Date -Format 'HHmmss')"
$fc4Body = @{ title = $fc4Title; content = "测试删除清理"; shopName = "FC4店铺" } | ConvertTo-Json -Compress
$fc4Bytes = [System.Text.Encoding]::UTF8.GetBytes($fc4Body)
try {
    $fc4Resp = Invoke-RestMethod -Uri "$base/post/publish" -Method POST -Headers $headers -ContentType "application/json; charset=utf-8" -Body $fc4Bytes
    $fc4NoteId = $fc4Resp.data.postId
    Write-Host "  发布测试笔记, postId=$fc4NoteId" -ForegroundColor Green
} catch { Write-Host "  发布失败: $($_.Exception.Message)" -ForegroundColor Red; $fc4NoteId = $null }

if ($fc4NoteId) {
    Start-Sleep -Seconds 1
    # 删除笔记
    try {
        Invoke-RestMethod -Uri "$base/post/$fc4NoteId" -Method DELETE -Headers $headers | Out-Null
        Write-Host "  删除成功" -ForegroundColor Green
    } catch { Write-Host "  删除失败: $($_.Exception.Message)" -ForegroundColor Red }

    Start-Sleep -Seconds 1
    # 检查 feed:deleted 集合
    $inDeleted = docker exec $redisContainer redis-cli -a $redisPassword SISMEMBER feed:deleted $fc4NoteId 2>$null
    if ($inDeleted -eq 1) {
        Write-Host "  删除清理: feed:deleted 包含 noteId=$fc4NoteId" -ForegroundColor Green
    } else {
        Write-Host "  删除清理: feed:deleted 不包含 noteId=$fc4NoteId" -ForegroundColor Yellow
    }

    # 请求feed，验证该笔记被过滤
    try {
        $feedAfterDel = Invoke-RestMethod -Uri "$base/feed/timeline?size=50" -Headers $headers
        $foundDeleted = $feedAfterDel.data.list | Where-Object { $_.postId -eq $fc4NoteId }
        if ($foundDeleted) {
            Write-Host "  FC-4 删除清理: 失败 (feed仍返回已删除笔记)" -ForegroundColor Red
        } else {
            Write-Host "  FC-4 删除清理: 通过 (feed已过滤删除笔记)" -ForegroundColor Green
        }
    } catch { Write-Host "  FC-4: feed请求失败 - $($_.Exception.Message)" -ForegroundColor Red }
} else {
    Write-Host "  FC-4: 跳过 (发布失败)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "===== 完成 =====" -ForegroundColor Cyan
Stop-Transcript | Out-Null
Write-Host "测试结果已保存: $transcriptFile" -ForegroundColor Green
