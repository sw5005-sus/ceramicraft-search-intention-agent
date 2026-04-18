# ====================================================================
# ceramicraft-search-intention-agent Dockerfile
# 多阶段构建 + 分层 JAR = 最小镜像 + 最优缓存
# ====================================================================

# ==================== Stage 1: 构建 ====================
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /build

# 1) 拷贝 Maven Wrapper + POM（利用 Docker 缓存）
COPY mvnw mvnw
COPY .mvn .mvn
COPY pom.xml pom.xml

# 2) 拷贝源码
COPY src src

# 3) 构建（BuildKit cache mount 缓存 Maven 本地仓库，跨次构建复用）
#    将依赖下载与编译放在同一 RUN，避免分步 DNS 解析失败
RUN --mount=type=cache,target=/root/.m2/repository \
    chmod +x mvnw && ./mvnw package -DskipTests -B

# ==================== Stage 2: 运行 ====================
FROM eclipse-temurin:17-jre

# 安全：使用非 root 用户运行
RUN groupadd --system appgroup && \
    useradd --system --gid appgroup --no-create-home appuser

WORKDIR /app

# 拷贝构建产物
COPY --from=builder --chown=appuser:appgroup /build/target/ceramicraft-search-intention-agent-*.jar app.jar

# 切换到非 root 用户
USER appuser

# 暴露端口
EXPOSE 8070

# 健康检查（依赖 Actuator /actuator/health 端点）
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8070/search-agent/actuator/health || exit 1

# JVM 调优参数
ENV JAVA_OPTS="-XX:+UseG1GC \
    -XX:MaxRAMPercentage=75.0 \
    -Djava.security.egd=file:/dev/./urandom \
    -Dfile.encoding=UTF-8"

# 默认启用 prod 环境（部署时可通过 -e SPRING_PROFILES_ACTIVE=xxx 覆盖）
ENV SPRING_PROFILES_ACTIVE=prod


ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/app.jar"]

