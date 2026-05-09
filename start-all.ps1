# ================================================================
# BiteBlog 一键启动所有微服务
# 用法: .\start-all.ps1 [compile|run]
#   compile  - 仅编译
#   run      - 编译并启动所有服务（默认）
# ================================================================

param([string]$action = "run")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

$services = @(
    @{Name="gateway";    Port=8080; Path="$root\biteblog-backend\biteblog-gateway"   },
    @{Name="user";       Port=8081; Path="$root\biteblog-backend\biteblog-user"      },
    @{Name="post";       Port=8082; Path="$root\biteblog-backend\biteblog-post"      },
    @{Name="feed";       Port=8083; Path="$root\biteblog-backend\biteblog-feed"      },
    @{Name="recommend";  Port=8084; Path="$root\biteblog-backend\biteblog-recommend" },
    @{Name="location";   Port=8085; Path="$root\biteblog-backend\biteblog-location"  },
    @{Name="rank";       Port=8086; Path="$root\biteblog-backend\biteblog-rank"      },
    @{Name="notify";     Port=8087; Path="$root\biteblog-backend\biteblog-notify"    }
)

# 编译
if ($action -eq "compile" -or $action -eq "run") {
    Write-Host "=== 编译所有模块 ===" -ForegroundColor Cyan
    Push-Location "$root\biteblog-backend"
    mvn install -DskipTests -q
    Pop-Location
    Write-Host "编译完成`n" -ForegroundColor Green
}

if ($action -eq "compile") {
    return
}

# 启动服务
Write-Host "=== 启动微服务 ===" -ForegroundColor Cyan
foreach ($svc in $services) {
    $title = "biteblog-$($svc.Name) :$($svc.Port)"
    Start-Process powershell -ArgumentList @(
        "-NoExit",
        "-Command",
        "`$host.UI.RawUI.WindowTitle='$title'; Write-Host 'Starting $title...' -ForegroundColor Yellow; cd '$($svc.Path)'; mvn spring-boot:run"
    )
    Write-Host "  已启动: $title"
    Start-Sleep -Seconds 2
}

Write-Host "`n全部 8 个服务已启动! 启动顺序: gateway > user > post > feed > recommend > location > rank > notify" -ForegroundColor Green
