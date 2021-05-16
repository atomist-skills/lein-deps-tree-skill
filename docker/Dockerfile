FROM gcr.io/atomist-container-skills/clojure-base:openjdk11@sha256:eb899ffde28916d32c5f01a81fdcc1e0beb491b439cc7439939c1a341e4c89e6

RUN mkdir /opt/gpg

RUN apt-get update && apt-get install -y --no-install-recommends gpg \
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


