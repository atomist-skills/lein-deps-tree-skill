set -e
docker login gcr.io
docker build -t gcr.io/atomist-container-skills/lein-deps-tree:$1 -f docker/Dockerfile .
docker push gcr.io/atomist-container-skills/lein-deps-tree-skill:$1