#!/usr/bin/env bash
# 一键运行脚本（给拿到镜像压缩包的人用，Linux / macOS）
# 用法：把本脚本和 shopping-system.tar、docker-compose.yml 放在同一目录，执行  ./run.sh
# 前置条件：已安装并启动 Docker

set -euo pipefail

IMAGE="shopping-system:1.0.0"
TAR="shopping-system.tar"
PORT=3000

# 1. 检查 Docker 可用
if ! docker info >/dev/null 2>&1; then
    echo "[run] 错误：Docker 未运行或未安装。" >&2
    exit 1
fi

# 2. 镜像不存在则从 tar 导入
if [ "$(docker images --filter "reference=$IMAGE" --format '{{.Repository}}')" != "shopping-system" ]; then
    if [ ! -f "$TAR" ]; then
        echo "[run] 错误：未找到 $TAR，请确认它与本脚本在同一目录。" >&2
        exit 1
    fi
    echo "[run] 导入镜像 $IMAGE（首次运行约需一分钟）..."
    docker load -i "$TAR"
fi

# 3. 启动服务
echo "[run] 启动服务..."
docker compose up -d

# 4. 等待启动并验证
echo "[run] 等待服务就绪..."
ok=0
for i in $(seq 1 30); do
    sleep 2
    if curl -fsS -o /dev/null "http://localhost:${PORT}/"; then
        ok=1
        break
    fi
done

if [ "$ok" -eq 1 ]; then
    echo "[run] 启动成功！"
    echo "  买家页面：http://localhost:${PORT}/"
    echo "  卖家后台：http://localhost:${PORT}/admin.html"
    echo "  停止服务：docker compose down（数据保留）"
else
    echo "[run] 服务未在预期时间内就绪，请查看日志：docker logs shopping-system" >&2
    exit 1
fi
