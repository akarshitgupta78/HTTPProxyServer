# ====================================================================
# Multi-Stage Dockerfile for High-Throughput Java 21 Caching Proxy
# ====================================================================

# --------------------------------------------------------------------
# Stage 1: Build & Package with Maven and JDK 21
# --------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /workspace

# Cache dependencies layer
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy sources and package Spring Boot executable fat-jar
COPY src ./src
RUN mvn clean package -DskipTests

# --------------------------------------------------------------------
# Stage 2: Hardened, Minimalist JRE 21 Runtime
# --------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Antigravity Engineering"
LABEL description="Multithreaded In-Memory Caching HTTP Proxy Server"

WORKDIR /app

# Run as non-root user for container security
RUN addgroup -S proxygroup && adduser -S proxyuser -G proxygroup
USER proxyuser

# Copy built artifact from builder stage
COPY --from=builder /workspace/target/*.jar /app/proxy-server.jar

# Port 8000: Forward Proxy Server Traffic
# Port 8080: Spring Boot REST & Actuator Management APIs
EXPOSE 8000 8080

# Environment Defaults
ENV PROXY_PORT=8000 \
    SERVER_PORT=8080 \
    JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75.0"

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q -O - http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/proxy-server.jar"]

