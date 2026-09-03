# Local validation image. Build the pinned upstream integration base first with
# scripts/build-voismart.sh. SDK/tools are intentionally retained for acceptance.
ARG FS_BASE_IMAGE=ai-freeswitch-voismart-base:4c5f5b3
ARG FS_BUILDER_IMAGE=ai-freeswitch-voismart-builder:4c5f5b3
FROM ${FS_BUILDER_IMAGE} AS fs-builder
FROM ${FS_BASE_IMAGE}
# Upstream's /usr/lib/*/ glob omits spandsp when ARM64 installs in /usr/lib.
COPY --from=fs-builder /src/spandsp/src/.libs/libspandsp.so.3.0.0 /usr/lib/
RUN ldconfig && freeswitch -version
WORKDIR /work
COPY . /work
RUN cmake -S tests -B /tmp/unit -DENABLE_TEST_SANITIZERS=ON \
    && cmake --build /tmp/unit --parallel 4 \
    && ctest --test-dir /tmp/unit --output-on-failure \
    && cmake -S . -B /tmp/module -DCMAKE_BUILD_TYPE=RelWithDebInfo -DUSE_TLS=ON \
    && cmake --build /tmp/module --parallel 4 \
    && cmake --install /tmp/module && ldconfig \
    && mkdir -p /recordings && chown 10001:10001 /recordings
LABEL org.opencontainers.image.source="https://github.com/VoiSmart/mod_openai_realtime" \
      org.opencontainers.image.revision="4c5f5b3000636edf6f5b08a482d62ec8d620bb06" \
      org.opencontainers.image.version="2.5.1" \
      org.opencontainers.image.licenses="MIT (module); FreeSWITCH and dependencies retain their own licenses"
CMD ["freeswitch", "-nf", "-nonat"]
