# 一键运行脚本（给拿到镜像压缩包的人用，Windows PowerShell）
# 用法：把本脚本和 shopping-system.tar、docker-compose.yml 放在同一目录，执行  .\run.ps1
# 前置条件：已安装并启动 Docker Desktop

$ErrorActionPreference = "Stop"

$Image = "shopping-system:1.0.0"
$Tar = "shopping-system.tar"
$Port = 3000

# 1. 检查 Docker 可用
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "[run] 错误：Docker 未运行，请先启动 Docker Desktop。" -ForegroundColor Red
    exit 1
}

# 2. 镜像不存在则从 tar 导入
$hasImage = docker images --filter "reference=$Image" --format "{{.Repository}}"
if ($hasImage -ne "shopping-system") {
    if (-not (Test-Path $Tar)) {
        Write-Host "[run] 错误：未找到 $Tar，请确认它与本脚本在同一目录。" -ForegroundColor Red
        exit 1
    }
    Write-Host "[run] 导入镜像 $Image（首次运行约需一分钟）..." -ForegroundColor Yellow
    docker load -i $Tar
}

# 3. 启动服务
Write-Host "[run] 启动服务..." -ForegroundColor Green
docker compose up -d

# 4. 等待启动并验证
Write-Host "[run] 等待服务就绪..." -ForegroundColor Green
$ok = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 2
    try {
        $resp = Invoke-WebRequest -Uri "http://localhost:$Port/" -UseBasicParsing -TimeoutSec 3
        if ($resp.StatusCode -eq 200) { $ok = $true; break }
    } catch {}
}

if ($ok) {
    Write-Host "[run] 启动成功！" -ForegroundColor Green
    Write-Host "  买家页面：http://localhost:$Port/"
    Write-Host "  卖家后台：http://localhost:$Port/admin.html"
    Write-Host "  停止服务：docker compose down（数据保留）"
} else {
    Write-Host "[run] 服务未在预期时间内就绪，请查看日志：docker logs shopping-system" -ForegroundColor Red
    exit 1
}
