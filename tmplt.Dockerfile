FROM openjdk:8-jdk as builder
RUN mkdir /build
COPY ./stuncheck/ /build/
WORKDIR /build/
RUN ./gradlew clean build shadowJar
RUN ls -ltr /build/build/libs/stuncheck-1.0.0-all.jar


FROM openjdk:10-jre
COPY --from=builder /build/build/libs/stuncheck-{VERSION}-all.jar /
#setup dumb-init
RUN curl -k -L https://github.com/Yelp/dumb-init/releases/download/v1.2.1/dumb-init_1.2.1_amd64 > /usr/bin/dumb-init
RUN chmod 755 /usr/bin/dumb-init

ADD run.sh /run.sh
RUN chmod 755 /run.sh
RUN touch /env.sh

ENTRYPOINT ["/run.sh"]
CMD ["java","-Xmx16m","-jar","stuncheck-{VERSION}-all.jar"]

