$ErrorActionPreference = "SilentlyContinue"
$baseUrl = "http://localhost:8081"

Write-Host "===== Init Test Data =====" -ForegroundColor Cyan

$users = @(
    @{ phone = "13800000001"; password = "12345678"; username = "user_foodie" },
    @{ phone = "13800000002"; password = "12345678"; username = "user_foodgod" },
    @{ phone = "13800000003"; password = "12345678"; username = "user_explorer" },
    @{ phone = "13800000004"; password = "12345678"; username = "user_hotpot" },
    @{ phone = "13800000005"; password = "12345678"; username = "user_dessert" },
    @{ phone = "13800000006"; password = "12345678"; username = "user_bbq" },
    @{ phone = "13800000007"; password = "12345678"; username = "user_japanese" },
    @{ phone = "13800000008"; password = "12345678"; username = "user_tea" },
    @{ phone = "13800000009"; password = "12345678"; username = "user_nightfood" },
    @{ phone = "13800000010"; password = "12345678"; username = "user_breakfast" }
)

$tokens = @()

foreach ($u in $users) {
    $json = $u | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    try {
        $resp = Invoke-RestMethod -Uri "$baseUrl/user/register" -Method POST -ContentType "application/json; charset=utf-8" -Body $bytes
        if ($resp.code -eq 200) {
            Write-Host "[OK] $($u.username) registered, userId=$($resp.data.userId)" -ForegroundColor Green
            $tokens += @{ userId = $resp.data.userId; token = $resp.data.token }
        } else {
            Write-Host "[SKIP] $($u.username): $($resp.msg)" -ForegroundColor Yellow
            $loginBytes = [System.Text.Encoding]::UTF8.GetBytes($json)
            $loginResp = Invoke-RestMethod -Uri "$baseUrl/user/login" -Method POST -ContentType "application/json; charset=utf-8" -Body $loginBytes
            if ($loginResp.code -eq 200) {
                $tokens += @{ userId = $loginResp.data.userId; token = $loginResp.data.token }
            }
        }
    } catch {
        Write-Host "[ERR] $($u.username): $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "===== Build Follow Relations =====" -ForegroundColor Cyan

if ($tokens.Count -ge 3) {
    $h1 = @{ "Authorization" = "Bearer $($tokens[0].token)"; "X-User-Id" = "$($tokens[0].userId)" }
    $h2 = @{ "Authorization" = "Bearer $($tokens[1].token)"; "X-User-Id" = "$($tokens[1].userId)" }
    $h3 = @{ "Authorization" = "Bearer $($tokens[2].token)"; "X-User-Id" = "$($tokens[2].userId)" }

    Invoke-RestMethod -Uri "$baseUrl/user/follow/$($tokens[1].userId)" -Method POST -Headers $h1 | Out-Null
    Write-Host "[Follow] $($users[0].username) -> $($users[1].username)"

    Invoke-RestMethod -Uri "$baseUrl/user/follow/$($tokens[2].userId)" -Method POST -Headers $h1 | Out-Null
    Write-Host "[Follow] $($users[0].username) -> $($users[2].username)"

    Invoke-RestMethod -Uri "$baseUrl/user/follow/$($tokens[0].userId)" -Method POST -Headers $h2 | Out-Null
    Write-Host "[Follow] $($users[1].username) -> $($users[0].username)"

    Invoke-RestMethod -Uri "$baseUrl/user/follow/$($tokens[0].userId)" -Method POST -Headers $h3 | Out-Null
    Write-Host "[Follow] $($users[2].username) -> $($users[0].username)"
}

Write-Host ""
Write-Host "===== Done =====" -ForegroundColor Cyan
Write-Host "Users: 13800000001 ~ 13800000010, Password: 12345678" -ForegroundColor White
