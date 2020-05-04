set -e
docker build -t gcr.io/atomist-container-skills/clojure-skill:$1 -f docker/Dockerfile .
docker push gcr.io/atomist-container-skills/lein-deps-tree-skill:$1