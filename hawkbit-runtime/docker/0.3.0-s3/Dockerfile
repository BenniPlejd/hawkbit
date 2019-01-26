FROM openjdk:8u181-jre-alpine

ENV HAWKBIT_VERSION=0.3.0 \
    HAWKBIT_HOME=/opt/hawkbit

EXPOSE 8080

RUN set -x \
    && apk add --no-cache --virtual build-dependencies gnupg unzip libressl wget \
    && mkdir -p $HAWKBIT_HOME \
    && cd $HAWKBIT_HOME \
    && wget -O hawkbit-update-server.jar --no-verbose https://github.com/BenniPlejd/hawkbit/releases/download/$HAWKBIT_VERSION-s3/hawkbit-update-server-$HAWKBIT_VERSION-SNAPSHOT.jar \
    && apk del build-dependencies

VOLUME "$HAWKBIT_HOME/data"

WORKDIR $HAWKBIT_HOME
ENTRYPOINT ["java","-jar","hawkbit-update-server.jar","-Xms768m -Xmx768m -XX:MaxMetaspaceSize=250m -XX:MetaspaceSize=250m -Xss300K -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+UseCompressedOops -XX:+HeapDumpOnOutOfMemoryError"]
