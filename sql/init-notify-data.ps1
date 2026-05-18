$ErrorActionPreference = "SilentlyContinue"
$resultFile = Join-Path $PSScriptRoot "notify-init-result.txt"

$gateway    = "http://localhost:8080/api"
$notifyBase = "http://localhost:8087"
$userBase   = "http://localhost:8081"

$authorPhone = "13800000001"   # bb_bigv_01 — 笔记作者，通知接收方
$fanPhone    = "13800000004"   # bb_user_04 — 互动粉丝，通知触发方
$password    = "12345678"

function Invoke-Json($uri, $method, $body = $null, $headers = $null) {
    if ($body) {
        $json  = $body | ConvertTo-Json -Depth 8 -Compress
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        if ($headers) {
            return Invoke-RestMethod -Uri $uri -Method $method -Headers $headers `
                -ContentType "application/json; charset=utf-8" -Body $bytes -ErrorAction Stop
        }
        return Invoke-RestMethod -Uri $uri -Method $method `
            -ContentType "application/json; charset=utf-8" -Body $bytes -ErrorAction Stop
    }
    if ($headers) { return Invoke-RestMethod -Uri $uri -Method $method -Headers $headers -ErrorAction Stop }
    return Invoke-RestMethod -Uri $uri -Method $method -ErrorAction Stop
}

$output = [System.Collections.Generic.List[string]]::new()
function Log($msg, $color = "White") {
    Write-Host $msg -ForegroundColor $color
    $output.Add($msg)
}

Log "===== Notify Service Init Data =====" "Cyan"
Log "  author : $authorPhone (bb_bigv_01)"
Log "  fan    : $fanPhone (bb_user_04)"
Log ""

Log "===== 1. Login =====" "Cyan"
try {
    $r = Invoke-Json "$userBase/user/login" "POST" @{ phone=$authorPhone; password=$password }
    $author = @{ userId=$r.data.userId; token=$r.data.token; username=$r.data.username }
    Log "[OK] author: $($author.username) userId=$($author.userId)" "Green"
} catch {
    Log "[ERR] author login failed: $($_.Exception.Message)" "Red"
    Log "Please run sql/init-data.ps1 first" "Yellow"
    $output | Out-File $resultFile -Encoding utf8; exit 1
}

try {
    $r2 = Invoke-Json "$userBase/user/login" "POST" @{ phone=$fanPhone; password=$password }
    $fan = @{ userId=$r2.data.userId; token=$r2.data.token; username=$r2.data.username }
    Log "[OK] fan   : $($fan.username) userId=$($fan.userId)" "Green"
} catch {
    Log "[ERR] fan login failed: $($_.Exception.Message)" "Red"
    $output | Out-File $resultFile -Encoding utf8; exit 1
}

$authorHdr = @{ "Authorization" = "Bearer $($author.token)" }
$fanHdr    = @{ "Authorization" = "Bearer $($fan.token)" }

Log ""
Log "===== 2. Publish Test Note (author) =====" "Cyan"
$noteTitle = "Notify联调测试-$(Get-Date -Format 'MMddHHmm')"
$noteBody  = @{
    title      = $noteTitle
    content    = "Notify 服务联调用笔记：由 bb_user_04 点赞/收藏/评论后应产生通知。"
    shopName   = "通知测试店"
    address    = "武汉市洪山区"
    longitude  = 114.366
    latitude   = 30.537
    scoreColor = 5; scoreSmell = 4; scoreTaste = 5
    imageUrls  = @()
}
try {
    $pub = Invoke-Json "$gateway/post/publish" "POST" $noteBody $authorHdr
    if ($pub.code -eq 200) {
        $postId = [long]$pub.data.postId
        Log "[OK] published postId=$postId title=$noteTitle" "Green"
    } else {
        Log "[ERR] publish failed: code=$($pub.code) msg=$($pub.msg)" "Red"
        $output | Out-File $resultFile -Encoding utf8; exit 1
    }
} catch {
    Log "[ERR] publish exception: $($_.Exception.Message)" "Red"
    $output | Out-File $resultFile -Encoding utf8; exit 1
}

Log ""
Log "===== 3. Fan Interactions -> MQ -> Notify =====" "Cyan"

try {
    $lr = Invoke-Json "$gateway/post/$postId/like" "POST" $null $fanHdr
    Log "[OK] like     liked=$($lr.data.liked)" "Green"
} catch { Log "[WARN] like: $($_.Exception.Message)" "Yellow" }

try {
    $fr = Invoke-Json "$gateway/post/$postId/favorite" "POST" $null $fanHdr
    Log "[OK] favorite favorited=$($fr.data.favorited)" "Green"
} catch { Log "[WARN] favorite: $($_.Exception.Message)" "Yellow" }

try {
    Invoke-Json "$gateway/post/$postId/comment" "POST" `
        @{ content="Notify联调测试评论 from $($fan.username)"; parentId=$null } $fanHdr | Out-Null
    Log "[OK] comment  posted" "Green"
} catch { Log "[WARN] comment: $($_.Exception.Message)" "Yellow" }

Log ""
Log "===== 4. Self-interaction (author likes own note) =====" "Cyan"
try {
    Invoke-Json "$gateway/post/$postId/like" "POST" $null $authorHdr | Out-Null
    Log "[OK] author liked own note (Notify 应过滤，不写通知)" "DarkGray"
} catch { Log "[WARN] self-like: $($_.Exception.Message)" "Yellow" }

Log ""
Log "===== 5. Waiting for MQ consumption (up to 10s)... =====" "Cyan"
$waited = 0
$found  = $false
while ($waited -lt 20) {
    Start-Sleep -Milliseconds 500
    $waited++
    try {
        $uc = Invoke-Json "$notifyBase/notify/unread-count" "GET" $null @{ "X-User-Id"="$($author.userId)" }
        if ($uc.data.unreadCount -ge 3) { $found = $true; break }
    } catch {}
}
if ($found) { Log "  MQ consumed: unreadCount=$($uc.data.unreadCount)" "Green" }
else        { Log "  [WARN] MQ may not have consumed within 10s; check RabbitMQ Unacked" "Yellow" }

Log ""
Log "===== 6. Verify Notify API (direct :8087) =====" "Cyan"
$directHdr = @{ "X-User-Id" = "$($author.userId)" }

try {
    $list = Invoke-Json "$notifyBase/notify/list?page=1&size=20" "GET" $null $directHdr
    $total = $list.data.total
    if ($total -ge 3) {
        Log "[OK] /notify/list  total=$total" "Green"
        $list.data.list | Select-Object -First 6 notificationId, senderId, senderUsername, type, content, readStatus |
            Format-Table -AutoSize | Out-String | ForEach-Object { Log $_ }
    } else {
        Log "[WARN] /notify/list total=$total (expected >= 3; check RabbitMQ and notify logs)" "Yellow"
    }
} catch { Log "[ERR] list: $($_.Exception.Message)" "Red" }

try {
    $uc2 = Invoke-Json "$notifyBase/notify/unread-count" "GET" $null $directHdr
    Log "[OK] /notify/unread-count unreadCount=$($uc2.data.unreadCount)" "Green"
} catch { Log "[ERR] unread-count: $($_.Exception.Message)" "Red" }

Log ""
Log "===== 7. Gateway verify tips =====" "Cyan"
Log '  $body = "{\"phone\":\"13800000001\",\"password\":\"12345678\"}"'
Log '  $r = Invoke-RestMethod -Uri "http://localhost:8080/api/user/login" -Method POST -Body $body -ContentType "application/json; charset=utf-8"'
Log '  $token = $r.data.token'
Log '  Invoke-RestMethod -Uri "http://localhost:8080/api/notify/list?page=1&size=20" -Headers @{ Authorization="Bearer $token" }'
Log '  Invoke-RestMethod -Uri "http://localhost:8080/api/notify/unread-count" -Headers @{ Authorization="Bearer $token" }'

Log ""
Log "===== Done =====" "Cyan"
Log "  author : 13800000001 / 12345678"
Log "  fan    : 13800000004 / 12345678"

$output | Out-File $resultFile -Encoding utf8
Write-Host "Result saved to: $resultFile" -ForegroundColor Cyan
