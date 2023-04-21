FROM openjdk:11
RUN apt update
RUN apt install -y git
COPY . /niobio
WORKDIR /niobio
RUN javac -cp ./lib/bcprov-jdk15on-1.66.jar:./lib/postgresql-42.2.18.jar ./src/coin/crypto/*.java ./src/coin/daemon/*.java ./src/coin/miner/*.java ./src/coin/run/*.java ./src/coin/util/*.java ./src/coin/wallet/*.java ./src/org/json/*.java -d ./bin
ARG RUN="coin.run.RunDaemon"
ARG PARAM=""
ENV RUN=${RUN}
ENV PARAM=${PARAM}
ENTRYPOINT java -cp ./lib/bcprov-jdk15on-1.66.jar:./lib/postgresql-42.2.18.jar:./bin ${RUN} ${PARAM}
