ARG JAVA_BUILD_IMAGE=maven:3.9.11-eclipse-temurin-17
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy
FROM ${JAVA_BUILD_IMAGE} AS builder
WORKDIR /workspace
COPY services-java/pom.xml ./pom.xml
COPY services-java/src ./src
RUN mvn -B -DskipTests package
RUN mkdir /workspace/native && cd /workspace/native \
    && jar xf /workspace/target/outbound-services-0.1.0-SNAPSHOT.jar BOOT-INF/lib \
    && for driver in BOOT-INF/lib/sqlite-jdbc-*.jar; do jar xf "$driver" "org/sqlite/native/Linux/$(uname -m)/libsqlitejdbc.so"; done \
    && cp "org/sqlite/native/Linux/$(uname -m)/libsqlitejdbc.so" . && rm -r BOOT-INF org
FROM ${JAVA_RUNTIME_IMAGE}
WORKDIR /app
COPY --from=builder /workspace/target/outbound-services-0.1.0-SNAPSHOT.jar /app/services.jar
COPY --from=builder /workspace/native/libsqlitejdbc.so /app/native/libsqlitejdbc.so
RUN mkdir -p /var/lib/voice-security /var/spool/recordings /var/lib/model-quota \
    && chown -R 10001:10001 /var/lib/voice-security /var/spool/recordings /var/lib/model-quota
ENV JAVA_BIND_ADDRESS=0.0.0.0 JAVA_TOOL_OPTIONS="-Dorg.sqlite.lib.path=/app/native"
USER 10001:10001
CMD ["java", "-jar", "/app/services.jar"]
