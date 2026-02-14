# Digital Phenotyping Backend - Dockerfile
# Multi-stage build for optimized image size

# Stage 1: Build the application
FROM gradle:8.5-jdk17 AS builder

WORKDIR /app

# Copy gradle files first for better caching
COPY build.gradle settings.gradle gradle.properties gradle.lockfile ./
COPY gradle ./gradle
COPY gradlew gradlew.bat ./

# Download dependencies
RUN gradle dependencies --no-daemon

# Copy source code
COPY src ./src

# Cache bust to force fresh snapshot allocation
ARG CACHE_BUST=1
RUN echo "Building with cache bust: ${CACHE_BUST}"

# Build the fat JAR
RUN gradle shadowJar --no-daemon --info

# Stage 2: Run the application
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy the fat JAR from builder
COPY --from=builder /app/build/libs/*-fat.jar app.jar

# Copy the configuration file
COPY aware-config.json ./

# Expose ports (HTTP and WebSocket)
EXPOSE 8080 8081

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/testing || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar", "run", "com.awareframework.micro.MainVerticle"]
