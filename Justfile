# Thin wrappers: every recipe is one command, and everything works without `just`.

# Unit and in-JVM suites (Docker required for the api, solver and library suites)
test:
    sbt test

# The whole system in one process: api with the runner in-process (needs `just db`)
run-api:
    sbt api/run

# Postgres for run-api, with ankka's schema
db:
    sbt schema && docker compose up -d

# Both service images into the local Docker daemon
images:
    sbt api/Docker/publishLocal solver/Docker/publishLocal deployDescriptors

# Refuse unformatted commits (once per clone)
hooks:
    git config core.hooksPath .githooks
