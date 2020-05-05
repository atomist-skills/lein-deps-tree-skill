#!/bin/bash
export WORKSPACE_ID=T29E48P34
export GRAPHQL_ENDPOINT=https://automation.atomist.com/graphql
export ATOMIST_PAYLOAD="$(pwd)/payload.json"
export ATOMIST_STORAGE=gs://t29e48p34-workspace-storage
export ATOMIST_CORRELATION_ID=whatever
export ATOMIST_HOME="$(pwd)/../lore"
export GOOGLE_APPLICATION_CREDENTIALS="$(pwd)/atomist-skill-production-T29E48P34.json"
export TOPIC=projects/atomist-skill-production/topics/packaging-test-topic
export IN_REPL=true
node index.js

