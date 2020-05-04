FROM gcr.io/atomist-container-skills/clojure-base:0.0.1
 
WORKDIR /usr/src/app  

COPY package*.json /usr/src/app/
RUN npm ci \
    && npm cache clean --force \
    && rm -rf ~/.cache

COPY deps.edn shadow-cljs.edn /usr/src/app/
COPY src /usr/src/app/src
RUN "`npm bin`/shadow-cljs" release release --verbose \
    && if ! [ -f index.js ]; then exit 101; fi; \
    rm -rf .cpcache .shadow-cljs ~/.m2

ENTRYPOINT [ "node" ]
CMD [ "index.js" ]
 
