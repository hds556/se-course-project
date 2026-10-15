# 在线购物系统 MVP 部署镜像
# 构建镜像（需先完成 mvnw package 产出 target/shopping-system-1.0.0.jar）：
#   docker build -t shopping-system:1.0.0 .
# 运行：
#   docker run -d -p 3000:3000 -v shopping-data:/app/data -v shopping-uploads:/app/uploads --name shopping-system shopping-system:1.0.0

FROM eclipse-temurin:17-jre

LABEL maintainer="se-course-project"
LABEL version="1.0.0"

WORKDIR /app

# 只拷贝构建产物，保持镜像最小化
COPY target/shopping-system-1.0.0.jar app.jar

# 数据库与上传图片目录（可通过卷持久化）
RUN mkdir -p /app/data /app/uploads

ENV SHOP_HOST=0.0.0.0 \
    SHOP_PORT=3000 \
    SHOP_DB_FILE=/app/data/shop.db \
    SHOP_UPLOAD_DIR=/app/uploads \
    TZ=Asia/Shanghai

EXPOSE 3000

VOLUME ["/app/data", "/app/uploads"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -qO- http://127.0.0.1:3000/ >/dev/null 2>&1 || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
