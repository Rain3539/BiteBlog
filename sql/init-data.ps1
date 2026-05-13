
$ErrorActionPreference = "SilentlyContinue"
$baseUrl = "http://localhost:8081"

Write-Host "===== Init 60 Users =====" -ForegroundColor Cyan

$users = @()
$bigVNames = @("bigv_foodking", "bigv_cheflife", "bigv_tastehunter")
for ($i = 0; $i -lt 3; $i++) {
    $users += @{ phone = "138000000$(($i+1).ToString('D2'))"; password = "12345678"; username = $bigVNames[$i]; isBigV = $true }
}
$foodTypes = @("hotpot","bbq","dessert","tea","sushi","noodle","steak","pizza","dumpling","seafood",
               "snack","cake","salad","ramen","taco","burger","dimsum","curry","friedrice","wonton",
               "tofu","porridge","kebab","pancake","icecream","latte","sashimi","paella","risotto","croissant",
               "bagel","mochi","bingsu","tiramisu","fondue","churros","pretzel","falafel","gyoza","bibimbap",
               "pho","ceviche","bruschetta","gnocchi","poutine","baklava","tempura","edamame","tikka","schnitzel",
               "waffle","macaron","cannoli","focaccia","naan","hummus","tandoori","kimchi","bao","teriyaki",
               "poke","jambalaya")
for ($i = 3; $i -lt 60; $i++) {
    $idx = $i - 3
    $users += @{
        phone = "138000000$(($i+1).ToString('D2'))"
        password = "12345678"
        username = "user_$($foodTypes[$idx])"
        isBigV = $false
    }
}

$tokens = @()

foreach ($u in $users) {
    $regJson = @{ phone = $u.phone; password = $u.password; username = $u.username } | ConvertTo-Json -Compress
    $regBytes = [System.Text.Encoding]::UTF8.GetBytes($regJson)
    $loginJson = @{ phone = $u.phone; password = $u.password } | ConvertTo-Json -Compress
    $loginBytes = [System.Text.Encoding]::UTF8.GetBytes($loginJson)

    $registered = $false
    try {
        $resp = Invoke-RestMethod -Uri "$baseUrl/user/register" -Method POST -ContentType "application/json; charset=utf-8" -Body $regBytes
        if ($resp.code -eq 200) {
            Write-Host "[OK] $($u.username) 注册成功, userId=$($resp.data.userId)" -ForegroundColor Green
            $tokens += @{ userId = $resp.data.userId; token = $resp.data.token; username = $u.username; isBigV = $u.isBigV }
            $registered = $true
        }
    } catch {
        Write-Host "[WARN] $($u.username) 注册失败，尝试登录" -ForegroundColor DarkYellow
    }

    if (-not $registered) {
        try {
            $loginResp = Invoke-RestMethod -Uri "$baseUrl/user/login" -Method POST -ContentType "application/json; charset=utf-8" -Body $loginBytes
            if ($loginResp.code -eq 200) {
                Write-Host "[LOGIN] $($u.username) 登录成功, userId=$($loginResp.data.userId)" -ForegroundColor Yellow
                $tokens += @{ userId = $loginResp.data.userId; token = $loginResp.data.token; username = $u.username; isBigV = $u.isBigV }
            } else {
                Write-Host "[FAIL] $($u.username): code=$($loginResp.code) msg=$($loginResp.msg)" -ForegroundColor Red
            }
        } catch {
            Write-Host "[ERR] $($u.username): 登录失败" -ForegroundColor Red
        }
    }
}

Write-Host ""
Write-Host "===== 建立关注关系（大V保证 + 随机） =====" -ForegroundColor Cyan

if ($tokens.Count -ge 60) {
    $bigVIndices = @(0, 1, 2)
    $normalIndices = @(3..59)

    $followCount = 0
    foreach ($ni in $normalIndices) {
        $normalUser = $tokens[$ni]
        $h = @{ "Authorization" = "Bearer $($normalUser.token)"; "X-User-Id" = "$($normalUser.userId)" }

        foreach ($bvi in $bigVIndices) {
            $target = $tokens[$bvi]
            try {
                $resp = Invoke-RestMethod -Uri "$baseUrl/user/follow/$($target.userId)" -Method POST -Headers $h
                if ($resp.data.followed) { $followCount++ }
            } catch {}
        }

        $extraCount = Get-Random -Minimum 2 -Maximum 8
        $candidates = $normalIndices | Where-Object { $_ -ne $ni }
        $shuffled = $candidates | Sort-Object { Get-Random }
        $picks = $shuffled | Select-Object -First $extraCount

        foreach ($pi in $picks) {
            $target = $tokens[$pi]
            try {
                $resp = Invoke-RestMethod -Uri "$baseUrl/user/follow/$($target.userId)" -Method POST -Headers $h
                if ($resp.data.followed) { $followCount++ }
            } catch {}
        }

        Write-Host "[关注] $($normalUser.username) -> $($bigVIndices.Count + $picks.Count) 个用户" -ForegroundColor DarkGray
    }

    Write-Host ""
    Write-Host "关注操作总数: $followCount" -ForegroundColor Green

    Write-Host ""
    Write-Host "===== 验证大V粉丝数 =====" -ForegroundColor Cyan
    foreach ($bvi in $bigVIndices) {
        $bv = $tokens[$bvi]
        try {
            $info = Invoke-RestMethod -Uri "$baseUrl/user/$($bv.userId)" -Method GET -Headers @{ "Authorization" = "Bearer $($bv.token)" }
            $fc = $info.data.followerCount
            $mark = if ($fc -ge 50) { "大V" } else { "非大V" }
            Write-Host "  $($bv.username): $fc 粉丝 [$mark]" -ForegroundColor $(if ($fc -ge 50) { "Green" } else { "Yellow" })
        } catch {
            Write-Host "  $($bv.username): 查询失败" -ForegroundColor Red
        }
    }
}

Write-Host ""
Write-Host "===== 完成 =====" -ForegroundColor Cyan
Write-Host "60 个用户: 13800000001 ~ 13800000060, 密码 12345678" -ForegroundColor White
Write-Host "大V: bigv_foodking, bigv_cheflife, bigv_tastehunter (>= 50 粉丝)" -ForegroundColor White
