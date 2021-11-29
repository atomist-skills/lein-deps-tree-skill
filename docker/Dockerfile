FROM gcr.io/atomist-container-skills/clojure-base:openjdk11@sha256:e5a6d694cb740d8d7cd683dbbca4a259d53e81eabcd7248974a4bb5e417b9d22

RUN mkdir /opt/gpg

RUN apt-get update && apt-get install -y --no-install-recommends \
    gpg=2.2.12-1+deb10u1 \
 && apt-get clean \
 && rm -rf /var/cache/apt /var/lib/apt/lists/* /tmp/* /var/tmp/*

COPY pubring.kbx /opt/gpg
COPY trustdb.gpg /opt/gpg

ENV GNUPGHOME=/opt/gpg

WORKDIR /usr/src/app

COPY package*.json /usr/src/app/
RUN npm ci
COPY deps.edn shadow-cljs.edn /usr/src/app/
COPY src /usr/src/app/src
RUN npm run build

ENTRYPOINT [ "node" ]
CMD [ "index.js" ]


