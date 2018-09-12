FROM gradle:4.10-jdk8-alpine as builder
MAINTAINER publicplan GmbH
LABEL MAINTAINER="publicplan GmbH"

COPY --chown=gradle:gradle ./ /app/yacy_grid_crawler
WORKDIR /app/yacy_grid_crawler

RUN gradle shadowJar && \
    mkdir data/crawler-8300/conf/ -p && \
    cp docker/config-crawler.properties data/crawler-8300/conf/config.properties

FROM openjdk:8-alpine as runner

WORKDIR /app

COPY --from=builder /app/yacy_grid_crawler/build/libs/*.jar /app/yacy-grid-crawler.jar
COPY --from=builder /app/yacy_grid_crawler/data /app/data

EXPOSE 8100
ENTRYPOINT ["java"]
CMD ["-jar", "yacy-grid-crawler.jar"]
