# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

COPY gradlew .
COPY gradle ./gradle
COPY build.gradle settings.gradle ./

RUN chmod +x ./gradlew
RUN ./gradlew dependencies --no-daemon

COPY src ./src

RUN ./gradlew clean bootJar -x test --no-daemon


FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --uid 10001 app \
    && mkdir -p /app/uploads \
    && chown -R app:app /app

COPY --from=builder --chown=app:app /workspace/build/libs/*.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=prod
ENV APP_MEDIA_UPLOAD_DIR=/app/uploads
ENV APP_MEDIA_VIDEO_OPTIMIZATION_FFMPEG_PATH=ffmpeg
ENV OPENAI_MODERATION_FFMPEG_PATH=ffmpeg
ENV JAVA_TOOL_OPTIONS="-Xms256m -Xmx768m"

EXPOSE 8080

USER app

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
