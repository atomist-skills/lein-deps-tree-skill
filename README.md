# `@atomist/lein-deps-tree`

## Problem

## What it does

Opens and closes GitHub Issues based on the presence of confusing Lein dependencies

## Configuration

| Name                   | Value        | Type   | Required | Notes |
| :---                   | :----        | :----  | :---  | :------ | 
| Scope | Selected GitHub Organization(s) and/or Repositories | `Org & Repo Selection` | false | By default, scope will include all organizations and repos available in the workspace  |

---

## Building

Ideally, we'd push new versions with skills but I'm still doing this by hand right now.

Read this and hope that you can get docker working:

https://cloud.google.com/container-registry/docs/advanced-authentication#gcloud-helper

The run `docker login gcr.io`

```
$> ./build-docker.sh 0.1.x
$> ./bootstrap-staging.sh
```

Created by [Atomist][atomist].
Need Help?  [Join our Slack workspace][slack].

[atomist]: https://atomist.com/ (Atomist - How Teams Deliver Software)
[slack]: https://join.atomist.com/ (Atomist Community Slack) 
