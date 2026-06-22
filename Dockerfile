# --- Stage 1: Build ---------------------------------------------------------
# Compile once, produce a staged distribution with a launcher that accepts
# `-main <fqcn>` so every service uses the same image.
FROM eclipse-temurin:17-jdk-jammy AS builder

ARG SBT_VERSION=1.12.6
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
    | tar -xz -C /opt \
 && ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Warm the dependency cache separately from the sources so source-only edits
# do not re-download Akka & friends on every build.
COPY project/plugins.sbt project/build.properties ./project/
COPY build.sbt ./
RUN sbt update

COPY src ./src
RUN sbt "clean; stage"

# --- Stage 2: Runtime -------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app

# curl is used by the Compose healthchecks against /health.
# stockfish is the UCI engine used by the AiService (auto-fallback to ChessAI
# if AI_ENGINE=chess-ai or the binary cannot be launched).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl stockfish \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/target/universal/stage /app

# Default main class — override per service in docker-compose via MAIN_CLASS.
ENV MAIN_CLASS=de.htwg.softwarearchitecture.almachess.api.Server

# The image is reused for three roles via MAIN_CLASS; the port comes from env
# (ALMACHESS_PORT for the API, NOTATION_PORT for NotationService, AI_PORT for
# AiService). The values listed here document the ports used by the Compose
# stack (API 8083, NotationService 8084, AiService 8082); only the one that
# matches the active role actually listens at runtime.
EXPOSE 8082 8083 8084

# sbt-native-packager's bash launcher supports `-main` to swap the entry point.
ENTRYPOINT ["/bin/sh", "-c", "exec /app/bin/almachess -main \"$MAIN_CLASS\""]
