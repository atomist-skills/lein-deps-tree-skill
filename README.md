# `@atomist/lein-deps-tree`

## Intro 

This skill creates a GitHub check with a success status if there are no confusing dependencies when `lein deps :tree` is run, or a failure status otherwise

## Why?

Dependencies in lein can have clashing transitive dependencies which can lead to unexpected runtime behaviour, so it makes sense to exclude any clashes explicitly from the classpath to keep it clean.

## Developing Locally

1.  **Run build server**

    ```shell
    npm install
    npm run build:watch
    ```

    The build server will have created an Nrepl server:

    ```shell
    shadow-cljs - nREPL server started on port 62170
    shadow-cljs - watching build :dev
    ```

2.  **start a Node host running index.js**

    Initialize with environment variables to set up the context for the skill by editing `run-local.sh` to your needs

    Note that `ATOMIST_PAYLOAD` should reference a file with Push `application/json` content (with secrets). (see payload.json)

    and `ATOMIST_HOME` should point at cloned copy of this Repo. I have also used `GOOGLE_APPLICATION_CREDENTIALS`
    to test against a real Test PubSub Topic. In the container, you'll have the response topic available, and
    the container identity will ensure that Topic publishes work.

3.  **switch repl to connect to this new Node.js process**

    ```clojure
    (shadow/repl :dev)
    (atomist.main/handler) ;; to run the handler
    ```

---

Created by [Atomist][atomist].
Need Help? [Join our Slack workspace][slack].

[atomist]: https://atomist.com/ "Atomist - How Teams Deliver Software"
[slack]: https://join.atomist.com/ "Atomist Community Slack"


