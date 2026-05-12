$ErrorActionPreference = "SilentlyContinue"

$userBase = "http://localhost:8081"
$postBase = "http://localhost:8082"
$notifyBase = "http://localhost:8087"

Write-Host "===== Init Notify Service Test Data =====" -ForegroundColor Cyan
Write-Host "依赖: user(8081) post(8082) notify(8087) + RabbitMQ + MySQL(init.sql)" -ForegroundColor DarkGray
Write-Host ""

$users = @(
    @{ phone = "13900004001"; password = "12345678"; username = "notify_demo_author" },
    @{ phone = "13900004002"; password = "12345678"; username = "notify_demo_fan" }
)

$accounts = @()

foreach ($u in $users) {
    $json = $u | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    try {
        $resp = Invoke-RestMethod -Uri "$userBase/user/register" -Method POST -ContentType "application/json; charset=utf-8" -Body $bytes
        if ($resp.code -eq 200) {
            Write-Host "[OK] registered $($u.username), userId=$($resp.data.userId)" -ForegroundColor Green
            $accounts += @{ userId = $resp.data.userId; token = $resp.data.token; username = $u.username }
            continue
        }
    } catch {}

    try {
        $loginResp = Invoke-RestMethod -Uri "$userBase/user/login" -Method POST -ContentType "application/json; charset=utf-8" -Body $bytes
        if ($loginResp.code -eq 200) {
            Write-Host "[OK] login $($u.username), userId=$($loginResp.data.userId)" -ForegroundColor Green
            $accounts += @{ userId = $loginResp.data.userId; token = $loginResp.data.token; username = $u.username }
        }
    } catch {
        Write-Host "[ERR] user init failed: $($u.username), $($_.Exception.Message)" -ForegroundColor Red
    }
}

if ($accounts.Count -lt 2) {
    Write-Host "[STOP] 需要至少 2 个用户。请先启动 user-service。" -ForegroundColor Red
    exit 1
}

$author = $accounts[0]
$fan = $accounts[1]

if ($author.userId -eq $fan.userId) {
    Write-Host "[STOP] 作者与互动方 userId 相同，无法测跨用户通知。" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "===== Publish Notify Test Note (author) =====" -ForegroundColor Cyan

$note = @{
    title       = "Notify Test 探店笔记"
    content     = "Notify 服务联调用：由 notify_demo_fan 点赞/收藏/评论后应产生通知。"
    shopName    = "Notify 测试店"
    address     = "武汉市洪山区"
    longitude   = 114.3660
    latitude    = 30.5370
    scoreColor  = 5
    scoreSmell  = 4
    scoreTaste  = 5
    imageUrls   = @()
}

$authorHeaders = @{
    "Authorization" = "Bearer $($author.token)"
    "X-User-Id"     = "$($author.userId)"
}

$postId = $null
try {
    $noteJson = $note | ConvertTo-Json -Depth 5 -Compress
    $noteBytes = [System.Text.Encoding]::UTF8.GetBytes($noteJson)
    $pub = Invoke-RestMethod -Uri "$postBase/post/publish" -Method POST -Headers $authorHeaders -ContentType "application/json; charset=utf-8" -Body $noteBytes
    if ($pub.code -eq 200) {
        $postId = $pub.data.postId
        Write-Host "[OK] published postId=$postId (receiver for notify = author userId=$($author.userId))" -ForegroundColor Green
    } else {
        Write-Host "[ERR] publish code=$($pub.code) msg=$($pub.msg)" -ForegroundColor Red
    }
} catch {
    Write-Host "[ERR] publish failed: $($_.Exception.Message)" -ForegroundColor Red
}

if (-not $postId) {
    Write-Host "[STOP] 未拿到 postId。请先启动 post-service。" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "===== Interactions (fan -> author note) -> MQ -> Notify =====" -ForegroundColor Cyan

$fanHeaders = @{
    "Authorization" = "Bearer $($fan.token)"
    "X-User-Id"     = "$($fan.userId)"
}

try {
    $likeResp = Invoke-RestMethod -Uri "$postBase/post/$postId/like" -Method POST -Headers $fanHeaders
    Write-Host "[OK] like liked=$($likeResp.data.liked)" -ForegroundColor Green
} catch {
    Write-Host "[ERR] like: $($_.Exception.Message)" -ForegroundColor Red
}

try {
    $favResp = Invoke-RestMethod -Uri "$postBase/post/$postId/favorite" -Method POST -Headers $fanHeaders
    Write-Host "[OK] favorite favorited=$($favResp.data.favorited)" -ForegroundColor Green
} catch {
    Write-Host "[ERR] favorite: $($_.Exception.Message)" -ForegroundColor Red
}

$commentBody = @{ content = "notify 初始化评论 from $($fan.username)"; parentId = $null } | ConvertTo-Json -Compress
$commentBytes = [System.Text.Encoding]::UTF8.GetBytes($commentBody)
try {
    Invoke-RestMethod -Uri "$postBase/post/$postId/comment" -Method POST -Headers $fanHeaders -ContentType "application/json; charset=utf-8" -Body $commentBytes | Out-Null
    Write-Host "[OK] comment posted" -ForegroundColor Green
} catch {
    Write-Host "[ERR] comment: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "===== Self-interaction (author likes own note) =====" -ForegroundColor Cyan
try {
    Invoke-RestMethod -Uri "$postBase/post/$postId/like" -Method POST -Headers $authorHeaders | Out-Null
    Write-Host "[OK] author liked own note (Post 会发 MQ；Notify 因 authorId=userId 不写 notification，不应增加「他人通知」条数)" -ForegroundColor DarkGray
} catch {
    Write-Host "[WARN] author self-like: $($_.Exception.Message)" -ForegroundColor Yellow
}

Start-Sleep -Seconds 2

Write-Host ""
Write-Host "===== Verify Notify API (notify-service :8087) =====" -ForegroundColor Cyan

$notifyHeaders = @{
    "X-User-Id" = "$($author.userId)"
}

try {
    $list = Invoke-RestMethod -Uri "$notifyBase/notify/list?page=1&size=20" -Method GET -Headers $notifyHeaders
    if ($list.code -eq 200) {
        $total = $list.data.total
        Write-Host "[OK] /notify/list total=$total" -ForegroundColor Green
        if ($list.data.list -and $list.data.list.Count -gt 0) {
            $list.data.list | Select-Object -First 8 notificationId, senderId, senderUsername, type, content, readStatus | Format-Table -AutoSize
        }
        if ($total -lt 3) {
            Write-Host "[WARN] 期望至少 3 条（赞/藏/评）。若为 0：查 RabbitMQ 队列 notify.interaction.queue 与 notify 日志。" -ForegroundColor Yellow
        }
    } else {
        Write-Host "[ERR] list code=$($list.code) msg=$($list.msg)" -ForegroundColor Red
    }
} catch {
    Write-Host "[ERR] notify list: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "      请确认 notify-service 已启动且端口 8087 可访问。" -ForegroundColor Yellow
}

try {
    $uc = Invoke-RestMethod -Uri "$notifyBase/notify/unread-count" -Method GET -Headers $notifyHeaders
    if ($uc.code -eq 200) {
        Write-Host "[OK] /notify/unread-count unreadCount=$($uc.data.unreadCount)" -ForegroundColor Green
    }
} catch {
    Write-Host "[ERR] unread-count: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "===== 登录信息（网关 /api 联调可用）=====" -ForegroundColor Cyan
Write-Host "  作者 notify_demo_author : 13900004001 / 12345678" -ForegroundColor White
Write-Host "  粉丝 notify_demo_fan   : 13900004002 / 12345678" -ForegroundColor White
Write-Host ""
Write-Host "===== 经网关验证 notify（PowerShell 正确写法）=====" -ForegroundColor Cyan
Write-Host '  登录：$body = ''{"phone":"13900004001","password":"12345678"}''' -ForegroundColor DarkGray
Write-Host '        $r = Invoke-RestMethod -Uri "http://localhost:8080/api/user/login" -Method POST -Body $body -ContentType "application/json; charset=utf-8"' -ForegroundColor DarkGray
Write-Host '        $token = $r.data.token' -ForegroundColor DarkGray
Write-Host '  列表：Invoke-RestMethod -Uri "http://localhost:8080/api/notify/list?page=1&size=20" -Headers @{ Authorization = "Bearer $token" }' -ForegroundColor DarkGray
Write-Host '  未读：Invoke-RestMethod -Uri "http://localhost:8080/api/notify/unread-count" -Headers @{ Authorization = "Bearer $token" }' -ForegroundColor DarkGray
Write-Host "  说明：code=200 且 total=0 表示鉴权已通过，但库中无通知（请查 RabbitMQ 与 notify 消费）。" -ForegroundColor DarkGray
Write-Host ""
Write-Host "===== Done =====" -ForegroundColor Cyan
