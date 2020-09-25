#!/bin/bash
# Copyright © 2020 Atomist, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

export ATOMIST_WORKSPACE_ID=T29E48P34
export ATOMIST_GRAPHQL_ENDPOINT=https://automation.atomist.com/graphql
export ATOMIST_PAYLOAD="$(pwd)/payload.json"
export ATOMIST_STORAGE=gs://t29e48p34-workspace-storage
export ATOMIST_CORRELATION_ID=whatever
export ATOMIST_HOME="$(pwd)/../lore"
export ATOMIST_TOPIC=projects/atomist-skill-production/topics/packaging-test-topic

export GOOGLE_APPLICATION_CREDENTIALS="$(pwd)/atomist-skill-production-T29E48P34.json"

export IN_REPL=true
node index.js

