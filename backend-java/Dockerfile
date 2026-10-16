ARG NODE_BASE_IMAGE=node:22-alpine
ARG JAVA_BUILD_IMAGE=maven:3.9.11-eclipse-temurin-8
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:8-jre-jammy
FROM ${NODE_BASE_IMAGE} AS frontend-builder
WORKDIR /workspace
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml /workspace/frontend/
RUN corepack enable && cd frontend && pnpm install --frozen-lockfile
COPY frontend /workspace/frontend
RUN mkdir -p /workspace/backend/app/static && cd frontend && pnpm build

FROM ${JAVA_BUILD_IMAGE} AS java-builder
WORKDIR /workspace
COPY backend-java/pom.xml ./pom.xml
COPY backend-java/src ./src
RUN mvn -B -DskipTests package

FROM ${JAVA_RUNTIME_IMAGE}
WORKDIR /app
COPY --from=java-builder /workspace/target/outbound-control-0.1.0-SNAPSHOT.jar /app/control.jar
COPY --from=frontend-builder /workspace/backend/app/static /app/static
ENV JAVA_BIND_ADDRESS=0.0.0.0 PORT=8000 FRONTEND_STATIC_DIR=/app/static
USER 10001:10001
EXPOSE 8000
CMD ["java", "-jar", "/app/control.jar"]
