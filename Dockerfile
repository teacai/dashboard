# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle gradle.lockfile ./
COPY gradle/wrapper/ gradle/wrapper/
RUN chmod 0755 gradlew
COPY src/ src/

# Run the JVM test suite before packaging. Browser tooling is not needed in the image.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain test installDist

FROM eclipse-temurin:25-jre-noble AS runtime
WORKDIR /app

RUN groupadd --gid 10001 wiki \
    && useradd --uid 10001 --gid wiki --no-create-home --shell /usr/sbin/nologin wiki \
    && mkdir -p /app/data \
    && chown wiki:wiki /app/data \
    && chmod 0700 /app/data

# Only runtime JARs/resources enter the final image; no Gradle, source, tests or Node.js.
COPY --from=build /workspace/build/install/wiki/lib/ /app/lib/
COPY LICENSE /app/LICENSE

USER 10001:10001
VOLUME ["/app/data"]
EXPOSE 8080
STOPSIGNAL SIGTERM

# No Micronaut environment/profile is selected: use application.yml defaults.
# /app is the working directory, so the default H2 URL resolves to /app/data/wiki.
ENTRYPOINT ["java", "-cp", "/app/lib/*", "com.teacai.wiki.WikiApplication"]
