# Feldera dev container

An Ubuntu 24.04 workspace with the Rust toolchain, Bun, Java, Python and
librdkafka, plus a Postgres instance for the tests that need one.

VS Code and any other editor that supports the Dev Containers specification read
`devcontainer.json` and start everything on their own. The rest of this file
covers driving the same containers by hand with podman.

## Services

| Service        | Purpose                            | Host port |
| -------------- | ---------------------------------- | --------- |
| `workspace`    | Build and test environment         | -         |
| `postgres-dev` | Database for the Postgres CDC tests | 5435      |
| `db-admin`     | Adminer web UI for `postgres-dev`  | 5436      |

## Launching with podman

Give the podman machine enough memory first. The Rust builds link large debug
binaries with one codegen/link job per CPU, so the default 2 GiB gets the
linker killed by the OOM killer.

```bash
podman machine stop
podman machine set --memory 32768 --cpus 8
podman machine start
```

`podman compose` delegates to an external provider, so install one. Prefer
`podman-compose`, which names containers `<project>_<service>_<index>` and
therefore produces `devcontainer_workspace_1`.

```bash
brew install podman-compose        # or your platform's package manager
```

Start the three services from the repository root. Pass both compose files, as
`devcontainer.json` does, so that the services of `deploy/docker-compose.yml`
join the same project and network and can be started alongside the workspace
later. The `workspace` environment points `REDPANDA_BROKERS` at the host
`redpanda`, which only resolves once that service runs; see the Redpanda note
below.

```bash
podman-compose -p devcontainer \
    -f .devcontainer/docker-compose.devcontainer.yml -f deploy/docker-compose.yml \
    up -d workspace postgres-dev db-admin
```

`podman-compose` ignores the `postCreateCommand` of `devcontainer.json`, so run
the provisioning script yourself. It installs librdkafka, which the Kafka
connectors link against, and the Playwright browsers used by the web console
tests. Re-run it after every `up --force-recreate`.

```bash
podman exec --user user devcontainer_workspace_1 \
    bash /workspaces/feldera/.devcontainer/postCreate.sh
```

Then open a shell. The repository is bind-mounted at `/workspaces/feldera`, so
edits on the host appear immediately in the container.

```bash
podman exec -it --user user devcontainer_workspace_1 bash
```

Tear the stack down with `podman-compose -p devcontainer -f
.devcontainer/docker-compose.devcontainer.yml -f deploy/docker-compose.yml
down`.

## Notes

- The image builds for the host architecture. On Apple silicon it is `aarch64`,
  so anything that assumes `x86_64` will not run.
- The Rust version lives in one place, the `RUST_VERSION` build argument in
  `Dockerfile`. Bump it there.
- `docker compose` works too, but it names the container
  `devcontainer-workspace-1` with hyphens.
- The Kafka connector tests need Redpanda, which sits behind the `redpanda`
  profile in `deploy/docker-compose.yml` and stays off otherwise. Start it
  with `--profile redpanda up -d redpanda` added to the command above.
- On a Linux host running podman with SELinux enforcing, the repository bind
  mount needs relabelling or the container cannot read it. Add
  `--security-opt label=disable` to the `podman-compose` commands above, or add
  `:z` to the volume line in `docker-compose.devcontainer.yml` (this relabels
  the whole repository on the host, so prefer the flag for a tree this size).
