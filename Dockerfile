FROM openjdk:10-jre-slim
ARG VERSION
COPY build/libs/stuncheck-${VERSION}-all.jar /stuncheck.jar
RUN apt-get update && apt-get install -y dumb-init && rm -rf /var/lib/apt/lists/*

ADD run.sh /run.sh
RUN chmod 755 /run.sh
RUN touch /env.sh

ENTRYPOINT ["/run.sh"]
CMD ["java","-Xmx16m","-jar","stuncheck.jar"]

