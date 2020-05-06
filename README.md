# `@atomist/lein-deps-tree`

## Problem
 
## Developing Locally

1.  **Run build server**

    ```
    npm install
    npm run build:watch
    ```

    The build server will have created an Nrepl server:
    
    ```
    shadow-cljs - nREPL server started on port 62170
    shadow-cljs - watching build :dev
    ```

3.  **start a Node host running index.js**

    Initialize with environment variables to set up the context for the skill
    
    ```
    export WORKSPACE_ID=T29E48P34
    export GRAPHQL_ENDPOINT=https://automation.atomist.com/graphql
    export ATOMIST_PAYLOAD=/Users/slim/skills/lein-deps-tree-skill/payload.json
    export ATOMIST_STORAGE=gs://t29e48p34-workspace-storage
    export ATOMIST_CORRELATION_ID=whatever
    export ATOMIST_HOME=/Users/slim/atmhq/view-service
    export GOOGLE_APPLICATION_CREDENTIALS=/Users/slim/skills/lein-deps-tree-skill/atomist-skill-production-ec3c6e5c9a1b.json
    export TOPIC=projects/atomist-skill-production/topics/packaging-test-topic
    export IN_REPL=true
    node index.js
    ```
    
    Note that `ATOMIST_PAYLOAD` should reference a file with Push `application/json` content (with secrets):
    
    ```
    {
      "data": {
        "Push": [
          {
            "repo": {
              "channels": [
                {
                  "name": "package-cljs-skill",
                  "channelId": "C0100Q8DFNE",
                  "team": {
                    "id": "T29E48P34",
                    "name": "atomist-community"
                  }
                }
              ]
            }
          }
        ]
      },
      "secrets": [
        {
          "uri": "atomist://api-key",
          "value": "fill this in"
        }
      ]
    }
    ```
    
    and `ATOMIST_HOME` should point at cloned copy of this Repo.  I have also used `GOOGLE_APPLICATION_CREDENTIALS`
    to test against a real Test PubSub Topic.  In the container, you'll have the response topic available, and
    the container identity will ensure that Topic publishes work.

4.  **switch repl to connect to this new Node.js process**

    ```
    (shadow/repl :dev)
    ```

---

Created by [Atomist][atomist].
Need Help?  [Join our Slack workspace][slack].

[atomist]: https://atomist.com/ (Atomist - How Teams Deliver Software)
[slack]: https://join.atomist.com/ (Atomist Community Slack) 
