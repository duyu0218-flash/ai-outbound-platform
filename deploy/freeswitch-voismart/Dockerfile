# Export only native FreeSWITCH executables/modules and their resolved ELF libraries.
# The upstream integration image is an artifact source, never the runtime base.
ARG FS_NATIVE_IMAGE=ai-freeswitch-voismart:2.5.1
FROM ${FS_NATIVE_IMAGE} AS native
RUN set -eu; mkdir -p /runtime; \
    copy_native() { dest="/runtime$1"; case "$1" in /lib/*) dest="/runtime/usr$1" ;; esac; mkdir -p "$(dirname "$dest")"; cp -L "$1" "$dest"; }; \
    for binary in /usr/bin/freeswitch /usr/bin/fs_cli /usr/lib/libfreeswitch.so* /usr/lib/freeswitch/mod/*.so; do \
      case "$binary" in *mod_python*|*mod_pypy*) continue ;; esac; \
      test -e "$binary" || continue; copy_native "$binary"; \
      ldd "$binary" | awk '/=> \/|^\t\// { for (i=1;i<=NF;i++) if ($i ~ /^\//) print $i }' | while read library; do \
        copy_native "$library"; done; \
    done; \
    test -x /runtime/usr/bin/freeswitch
FROM eclipse-temurin:22-jdk-jammy AS helper
WORKDIR /helper
COPY tools/java/Pbx.java .
RUN javac --release 17 Pbx.java
FROM eclipse-temurin:22-jre-jammy AS jre
FROM debian:bookworm-slim
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
COPY --from=native /runtime/ /
COPY --from=helper /helper/Pbx.class /opt/media-tools/Pbx.class
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
RUN ldconfig && freeswitch -version \
    && mkdir -p /recordings && chown 10001:10001 /recordings \
    && for p in python python3 pip pip3; do if command -v "$p" >/dev/null 2>&1; then exit 1; fi; done
USER 10001:10001
ENTRYPOINT ["java", "-cp", "/opt/media-tools", "Pbx", "start"]
