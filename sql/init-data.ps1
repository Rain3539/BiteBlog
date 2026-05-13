$ErrorActionPreference = "Continue"

$userBase = "http://localhost:8081"
$password = "12345678"

Write-Host "===== Init BiteBlog Base Users =====" -ForegroundColor Cyan
Write-Host "User service: $userBase"

function New-UserSeed($index) {
    $phone = "138000000{0:D2}" -f $index
    $role = if ($index -le 3) { "bigv" } else { "user" }
    return @{
        phone = $phone
        password = $password
        username = "bb_${role}_{0:D2}" -f $index
    }
}

function Invoke-Json($uri, $method, $body, $headers = $null) {
    $json = $body | ConvertTo-Json -Depth 8 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    if ($headers) {
        return Invoke-RestMethod -Uri $uri -Method $method -Headers $headers -ContentType "application/json; charset=utf-8" -Body $bytes -ErrorAction Stop
    }
    return Invoke-RestMethod -Uri $uri -Method $method -ContentType "application/json; charset=utf-8" -Body $bytes -ErrorAction Stop
}

function Login-SeedUser($seed) {
    $body = @{ phone = $seed.phone; password = $seed.password }
    $resp = Invoke-Json "$userBase/user/login" "POST" $body
    if ($resp.code -ne 200) {
        throw "Login failed: $($seed.phone), $($resp.msg)"
    }
    return @{
        phone = $seed.phone
        username = $resp.data.username
        userId = [long]$resp.data.userId
        token = $resp.data.token
    }
}

function Register-Or-Login($seed) {
    try {
        $resp = Invoke-Json "$userBase/user/register" "POST" $seed
        if ($resp.code -eq 200) {
            Write-Host "[OK] registered $($seed.phone), userId=$($resp.data.userId)" -ForegroundColor Green
            return @{
                phone = $seed.phone
                username = $seed.username
                userId = [long]$resp.data.userId
                token = $resp.data.token
            }
        }
        Write-Host "[SKIP] $($seed.phone): $($resp.msg)" -ForegroundColor Yellow
    } catch {
        Write-Host "[SKIP] $($seed.phone) already exists or register failed, login instead" -ForegroundColor Yellow
    }

    try {
        $account = Login-SeedUser $seed
        Write-Host "[OK] login $($seed.phone), userId=$($account.userId)" -ForegroundColor Green
        return $account
    } catch {
        Write-Host "[ERR] cannot prepare $($seed.phone): $($_.Exception.Message)" -ForegroundColor Red
        return $null
    }
}

function Ensure-Follow($follower, $target) {
    if (!$follower -or !$target -or $follower.userId -eq $target.userId) {
        return
    }

    $headers = @{
        "Authorization" = "Bearer $($follower.token)"
        "X-User-Id" = "$($follower.userId)"
    }

    try {
        $resp = Invoke-RestMethod -Uri "$userBase/user/follow/$($target.userId)" -Method POST -Headers $headers -ErrorAction Stop
        if ($resp.code -eq 200 -and $resp.data.followed -eq $false) {
            $resp = Invoke-RestMethod -Uri "$userBase/user/follow/$($target.userId)" -Method POST -Headers $headers -ErrorAction Stop
        }
        if ($resp.code -eq 200 -and $resp.data.followed -eq $true) {
            Write-Host "[Follow] $($follower.phone) -> $($target.phone)"
        } else {
            Write-Host "[WARN] follow uncertain: $($follower.phone) -> $($target.phone)" -ForegroundColor Yellow
        }
    } catch {
        Write-Host "[ERR] follow failed: $($follower.phone) -> $($target.phone), $($_.Exception.Message)" -ForegroundColor Red
    }
}

$seeds = 1..60 | ForEach-Object { New-UserSeed $_ }
$accounts = @()

foreach ($seed in $seeds) {
    $account = Register-Or-Login $seed
    if ($account) {
        $accounts += $account
    }
}

if ($accounts.Count -lt 60) {
    Write-Host "[WARN] only prepared $($accounts.Count)/60 users. Please check user-service and database state." -ForegroundColor Yellow
}

$byPhone = @{}
foreach ($account in $accounts) {
    $byPhone[$account.phone] = $account
}

Write-Host ""
Write-Host "===== Build Big-V Follow Relations =====" -ForegroundColor Cyan
Write-Host "Targets: 13800000001 ~ 13800000003, followers: 13800000004 ~ 13800000060"

$bigVTargets = @("13800000001", "13800000002", "13800000003")
$followerPhones = 4..60 | ForEach-Object { "138000000{0:D2}" -f $_ }

foreach ($targetPhone in $bigVTargets) {
    foreach ($followerPhone in $followerPhones) {
        Ensure-Follow $byPhone[$followerPhone] $byPhone[$targetPhone]
    }
}

Write-Host ""
Write-Host "===== Build Small Follow Graph =====" -ForegroundColor Cyan
Ensure-Follow $byPhone["13800000001"] $byPhone["13800000004"]
Ensure-Follow $byPhone["13800000004"] $byPhone["13800000005"]
Ensure-Follow $byPhone["13800000005"] $byPhone["13800000001"]

Write-Host ""
Write-Host "===== Done =====" -ForegroundColor Cyan
Write-Host "Users: 13800000001 ~ 13800000060"
Write-Host "Password: $password"
Write-Host "Big-V users: 13800000001 ~ 13800000003, each has more than 50 followers after this script."
