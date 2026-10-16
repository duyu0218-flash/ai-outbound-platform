ARG NODE_BASE_IMAGE=node:22-alpine
ARG JAVA_BUILD_IMAGE=maven:3.9.11-eclipse-temurin-17
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy
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
# SQLite cannot load an extracted native library from a noexec /tmp mount.
# Extract the architecture-specific library into the immutable image instead.
RUN mkdir -p /workspace/native && cd /workspace/native \
    && jar xf /workspace/target/outbound-control-0.1.0-SNAPSHOT.jar BOOT-INF/lib \
    && for driver in BOOT-INF/lib/sqlite-jdbc-*.jar; do \
         jar xf "$driver" "org/sqlite/native/Linux/$(uname -m)/libsqlitejdbc.so"; \
       done \
    && cp "org/sqlite/native/Linux/$(uname -m)/libsqlitejdbc.so" . \
    && rm -r BOOT-INF org

FROM ${JAVA_RUNTIME_IMAGE}
WORKDIR /app
COPY --from=java-builder /workspace/target/outbound-control-0.1.0-SNAPSHOT.jar /app/control.jar
COPY --from=java-builder /workspace/native/libsqlitejdbc.so /app/native/libsqlitejdbc.so
COPY --from=frontend-builder /workspace/backend/app/static /app/static
ENV JAVA_BIND_ADDRESS=0.0.0.0 PORT=8000 FRONTEND_STATIC_DIR=/app/static
ENV JAVA_TOOL_OPTIONS="-Dorg.sqlite.lib.path=/app/native"
USER 10001:10001
EXPOSE 8000
CMD ["java", "-jar", "/app/control.jar"]
