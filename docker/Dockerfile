FROM gradle:5.4-jdk8-alpine as builder
MAINTAINER publicplan GmbH
LABEL MAINTAINER="publicplan GmbH"

COPY --chown=gradle:gradle ./ /app/yacy_grid_crawler
WORKDIR /app/yacy_grid_crawler

RUN docker/crawler_vsm_blacklist.sh /app/yacy_grid_crawler/conf/crawler_blacklist_vsm.txt && \
    gradle build --info && \
    mkdir build/distributions/app && \
    unzip build/distributions/*.zip -d build/distributions/app && \
    mkdir -p data/crawler-8300/conf/ && \
    cp -rf conf/* data/crawler-8300/conf/

FROM openjdk:8-alpine as runner

COPY --from=builder /app/yacy_grid_crawler/build/distributions/app/* /app
COPY --from=builder /app/yacy_grid_crawler/data /app/data

WORKDIR /app
EXPOSE 8300
ENTRYPOINT ["/bin/sh"]
CMD ["bin/yacy_grid_crawler"]
