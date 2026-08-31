FROM debian:bookworm-slim AS build

ARG SIPP_VERSION=3.7.7
ARG SIPP_SOURCE_SHA256=e55b15f567760e9febeef366a1ab51a5239d197a132ce931b78c826d22d31e69

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl build-essential cmake libssl-dev libpcap-dev libncurses-dev \
    && curl --fail --location --silent --show-error \
       "https://github.com/SIPp/sipp/releases/download/v${SIPP_VERSION}/sipp-${SIPP_VERSION}.tar.gz" \
       --output /tmp/sipp.tar.gz \
    && echo "${SIPP_SOURCE_SHA256}  /tmp/sipp.tar.gz" | sha256sum --check - \
    && mkdir /tmp/sipp \
    && tar --extract --gzip --file /tmp/sipp.tar.gz --directory /tmp/sipp --strip-components=1 \
    && cmake -S /tmp/sipp -B /tmp/sipp/build -DCMAKE_BUILD_TYPE=Release -DUSE_PCAP=1 \
    && cmake --build /tmp/sipp/build --parallel 2

FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates libssl3 libpcap0.8 libncursesw6 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /tmp/sipp/build/sipp /usr/local/bin/sipp
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/sipp"]
CMD ["-v"]
