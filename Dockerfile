# syntax=docker/dockerfile:1
#
# solvis2mqtt container image.
#
# Build stage: compiles the jar with scripts/build-jar.sh. It runs on the build
# host's platform ($BUILDPLATFORM) because class files are platform independent;
# only the small runtime stage is built per target platform (amd64, arm64).
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk AS build
WORKDIR /build
COPY lib lib
COPY rsc rsc
COPY src src
COPY scripts/build-jar.sh scripts/
RUN sh scripts/build-jar.sh /build/solvis2mqtt.jar

# Runtime stage
FROM eclipse-temurin:17-jre
LABEL org.opencontainers.image.title="solvis2mqtt" \
      org.opencontainers.image.description="Solvis heating systems (SolvisMax 6/7, SolvisControl 2 + SolvisRemote) via MQTT, with Home Assistant auto-discovery" \
      org.opencontainers.image.source="https://github.com/lufi42/solvis2mqtt" \
      org.opencontainers.image.licenses="MIT"

# /data holds base.xml (writablePathLinux="/data") and the generated
# SolvisServerData directory (learned images, backups, logs).
ENV JAVA_OPTS="-Xmx384m" \
    SOLVIS2MQTT_BASE_XML="/data/base.xml"

# newer Ubuntu base images already ship a user with uid 1000 ("ubuntu"); replace it
RUN if getent passwd 1000 >/dev/null; then userdel -r "$(getent passwd 1000 | cut -d: -f1)"; fi \
    && useradd --system --uid 1000 --home-dir /data --shell /usr/sbin/nologin solvis \
    && mkdir -p /app /data \
    && chown solvis:solvis /data

COPY --from=build /build/solvis2mqtt.jar /app/solvis2mqtt.jar
COPY rsc/de/sgollmer/solvismax/data/base.xml /app/base.xml.new
COPY rsc/de/sgollmer/solvismax/data/base.xsd /app/base.xsd
COPY --chmod=755 docker/entrypoint.sh /app/entrypoint.sh

USER solvis
WORKDIR /data
VOLUME /data
# The server only talks to the Solvis unit and the MQTT broker, no ports to expose.
STOPSIGNAL SIGTERM

ENTRYPOINT ["/app/entrypoint.sh"]
