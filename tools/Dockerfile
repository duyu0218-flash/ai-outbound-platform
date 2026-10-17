# Clean development/test toolchain. No interpreter package is installed.
FROM node:22-bookworm-slim AS node
FROM maven:3.9.11-eclipse-temurin-17 AS toolchain
COPY --from=node /usr/local/ /usr/local/
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
WORKDIR /workspace
RUN for p in python python3 pip pip3; do if command -v "$p" >/dev/null 2>&1; then exit 1; fi; done \
    && corepack enable pnpm
COPY . .
RUN cd frontend && pnpm install --frozen-lockfile && pnpm build
RUN bash backend-java/scripts/build-java17.sh -B -DskipTests package \
    && bash services-java/scripts/build-java17.sh -B -DskipTests package
# Installing the browser's native packages is optional; the base stage can build/test
# Java, frontend and tools in an environment where Python is absent.
CMD ["bash", "tools/clean-verify.sh"]
FROM toolchain AS browser
RUN cd frontend && pnpm exec playwright install --with-deps chromium \
    && for p in python python3 pip pip3; do if command -v "$p" >/dev/null 2>&1; then exit 1; fi; done
CMD ["bash", "tools/clean-verify.sh", "--browser"]
