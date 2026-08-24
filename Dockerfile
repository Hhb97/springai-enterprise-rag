# ============================================================
# Stage 1 / 2：Maven 构建（缓存依赖层，避免每次都重新下载）
# 基础镜像：eclipse-temurin:21-jdk（Spring Boot 4.x 强制 JDK 21+）
# ============================================================
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# 1) 先只复制 pom.xml → 下载依赖（缓存命中时跳过，加快 80% 构建时间）
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q dependency:go-offline -DskipTests

# 2) 再复制源码，编译打包
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q clean package -DskipTests && \
    # 把最终 jar 拷出来并重命名，去掉版本号方便写脚本
    cp target/rag-assistant-*.jar /app.jar

# ============================================================
# Stage 2 / 2：运行镜像（只带 JRE，镜像从 ~900MB 缩到 ~280MB）
# 基础镜像：eclipse-temurin:21-jre-alpine
# ============================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

# 基础优化：中文字体（生成 PDF/图片时不乱码）、时区上海
RUN apk add --no-cache tzdata fontconfig ttf-dejavu && \
    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

WORKDIR /app

# 持久化目录：H2 文件库、上传的文档、向量库
VOLUME ["/app/data", "/app/uploads", "/app/documents"]

# 从 builder 阶段拿 jar
COPY --from=builder /app.jar app.jar

# JVM 参数：
#   -XX:+UseZGC             JDK 21 新 GC（低延迟，RAG 场景下减少 STW）
#   -XX:MaxRAMPercentage=75 容器内存自动按百分比分配（不用硬写 Xmx）
#   -XX:+ExitOnOutOfMemoryError  OOM 后主动退出，让 docker 重启
ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/data/heapdump.hprof"

# 业务端口 + H2 控制台端口（本项目把 H2 Console 挂在同一端口下）
EXPOSE 8900

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8900/actuator/health | grep -q '"status":"UP"' || exit 1

# 启动：注意 jar 要放 JAVA_OPTS 后面，否则 JVM 参数不生效
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]