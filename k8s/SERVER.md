# AlMaChess on the HTWG VM (chess@141.37.74.146)

Production-ish deploy that mirrors the local k3d setup. The VM constraints
shape the approach:

- `chess` user has no `sudo` access. Native k3s install was a non-starter.
- `chess` is in the `docker` group, so it can run containers freely.
- 4 vCPU, 3.8 GB RAM — tight, but enough for a single-node k3d cluster
  running six AlMaChess workloads (Mongo is disabled on this VM, see
  below).
- The VM's CPU lacks **AVX**. `mongo:7` (and any 5.0+) crash-loops on
  startup. The API supports a Postgres backend (`ALMACHESS_DB=postgres`)
  which works fine, so we ship that override and scale Mongo to 0 on the
  server. The `mongo.yaml` manifest is still applied (so a future
  AVX-capable host gets it for free), it's just zero-replicas here.

The chosen stack is therefore **k3d on the VM**: k3s wrapped in Docker
containers, no host-level privileges, identical manifests as the local
deploy. Docker is only used as runtime substrate; the orchestration is
still Kubernetes.

## Prerequisites on a fresh deploy machine (developer side)

- Docker Desktop running (used to build / save images).
- Python with `paramiko` (`pip install paramiko`) if you want to script SSH.
- `kubectl` locally is optional — the cluster on the VM is self-contained.

## One-shot deploy from the developer machine

The flow is: build images locally → `docker save` → SFTP to VM → load into
the VM's Docker → `k3d image import` → apply manifests.

```bash
# 1) Build images (from the repo root)
docker build -t almachess:latest .
docker build -t almachess-web:latest ./web

# 2) Pack them
mkdir -p ~/almachess-deploy/images
docker save -o ~/almachess-deploy/images/almachess.tar     almachess:latest
docker save -o ~/almachess-deploy/images/almachess-web.tar almachess-web:latest

# 3) Copy to the VM (any SFTP client works)
scp ~/almachess-deploy/images/*.tar  chess@141.37.74.146:~/images/
scp -r k8s                            chess@141.37.74.146:~/almachess/

# 4) On the VM: bootstrap k3d + kubectl, then deploy
ssh chess@141.37.74.146 'bash -s' < scripts/bootstrap-k3d.sh
ssh chess@141.37.74.146 'bash -s' < scripts/install-kubectl.sh
ssh chess@141.37.74.146 'export PATH=$HOME/bin:$PATH && \
    docker load -i ~/images/almachess.tar && \
    docker load -i ~/images/almachess-web.tar && \
    k3d image import almachess:latest almachess-web:latest -c almachess && \
    kubectl apply -f ~/almachess/k8s/namespace.yaml && \
    kubectl apply -f ~/almachess/k8s/ && \
    bash ~/almachess/k8s/server-patches.sh'

# 5) Wait for Ready (mongo:7 pull can take 5+ min the first time)
ssh chess@141.37.74.146 'export PATH=$HOME/bin:$PATH && \
    kubectl -n almachess wait --for=condition=Ready pod --all --timeout=600s && \
    kubectl -n almachess get pods'
```

The two bootstrap scripts referenced above are reproduced verbatim at the
end of this document.

## Endpoint

Once all pods are Ready, the app is reachable on the VM's public IP:

```
http://141.37.74.146/
```

The k3d cluster maps host port 80 to the bundled Traefik ingress, which
routes `/` to the `web` Service (nginx). nginx in turn proxies `/api`,
`/notation`, `/ai`, `/health` to the respective backend Services by their
in-cluster DNS names.

Smoke tests against the deployed VM:

```bash
curl -fsS http://141.37.74.146/health           # API
curl -fsS http://141.37.74.146/notation/health
curl -fsS http://141.37.74.146/ai/health
curl -fsS http://141.37.74.146/api/game

curl -fsS -X POST http://141.37.74.146/api/game/reset
curl -fsS -X POST http://141.37.74.146/api/game/move \
  -H 'Content-Type: application/json' -d '{"from":"e2","to":"e4"}'
curl -fsS -X POST http://141.37.74.146/api/game/ai-move \
  -H 'Content-Type: application/json' -d '{"depth":2}'
```

## Re-deploying after a code change

```bash
docker build -t almachess:latest .
docker save -o ~/almachess-deploy/images/almachess.tar almachess:latest
scp ~/almachess-deploy/images/almachess.tar chess@141.37.74.146:~/images/
ssh chess@141.37.74.146 'export PATH=$HOME/bin:$PATH && \
    docker load -i ~/images/almachess.tar && \
    k3d image import almachess:latest -c almachess && \
    kubectl -n almachess rollout restart \
      deploy/almachess-api deploy/notation-service deploy/ai-service'
```

Web-tier re-deploy is the same flow with `almachess-web` and `deploy/web`.

## Teardown

```bash
ssh chess@141.37.74.146 'export PATH=$HOME/bin:$PATH && \
    kubectl delete namespace almachess && \
    k3d cluster delete almachess'
```

## Operational notes

- **No HTTPS yet.** The VM is reachable as plain HTTP on port 80. Add a
  domain + cert-manager (or front it with the HTWG reverse proxy) before
  using this for anything sensitive.
- **No Lichess tokens shipped.** `LICHESS_BOARD_TOKEN` / `LICHESS_BOT_TOKEN`
  are declared in `secret.yaml` as empty strings; the API runs with the
  integration disabled. To enable: recreate the secret out-of-band:

  ```bash
  ssh chess@141.37.74.146 'export PATH=$HOME/bin:$PATH && \
      kubectl -n almachess delete secret almachess-secrets && \
      kubectl -n almachess create secret generic almachess-secrets \
        --from-literal=POSTGRES_PASSWORD=... \
        --from-literal=LICHESS_BOARD_TOKEN=... \
        --from-literal=LICHESS_BOT_TOKEN=...' && \
  ssh chess@141.37.74.146 'export PATH=$HOME/bin:$PATH && \
      kubectl -n almachess rollout restart deploy/almachess-api'
  ```

- **RAM headroom.** Cluster overhead (k3d server + Traefik + CoreDNS) is
  ~500 MB. The three Scala services are the other big consumers. If the box
  starts paging, the first lever is `MAIN_CLASS=...AiService`'s JVM — set
  `-Xmx256m` via a `JAVA_OPTS` env var on the AI deployment.

- **Liveness probe tuning.** `server-patches.sh` relaxes the JVM services'
  liveness probes (initialDelay 120s, period 30s, failureThreshold 6).
  On a small VM the JVM cold-start plus first Stockfish search can trip the
  default tighter probes and SIGTERM healthy pods. The local k3d deploy
  keeps the stricter defaults because workstations have RAM to spare.

- **First-time image pulls** for `mongo:7` (~800 MB) and `postgres:16-alpine`
  / `redis:7-alpine` happen inside k3s's containerd, _not_ from the Docker
  daemon we used to load `almachess:latest`. The first deploy therefore
  takes a few extra minutes to settle.

## Helper scripts

These two scripts are reproduced verbatim — keep them under `scripts/` in
the repo or paste them in ad-hoc.

### `bootstrap-k3d.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
K3D_VERSION="v5.8.3"
CLUSTER_NAME="almachess"
mkdir -p "$HOME/bin"
K3D_BIN="$HOME/bin/k3d"

if [ ! -x "$K3D_BIN" ]; then
  curl -fsSL -o "$K3D_BIN" \
    "https://github.com/k3d-io/k3d/releases/download/$K3D_VERSION/k3d-linux-amd64"
  chmod +x "$K3D_BIN"
fi

grep -q '$HOME/bin' "$HOME/.profile" 2>/dev/null || \
  echo 'export PATH="$HOME/bin:$PATH"' >> "$HOME/.profile"
export PATH="$HOME/bin:$PATH"

if ! "$K3D_BIN" cluster list "$CLUSTER_NAME" 2>/dev/null | grep -q "$CLUSTER_NAME"; then
  "$K3D_BIN" cluster create "$CLUSTER_NAME" \
    --agents 0 \
    --port "80:80@loadbalancer" \
    --wait
fi
```

### `install-kubectl.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
export PATH="$HOME/bin:$PATH"

if [ ! -x "$HOME/bin/kubectl" ]; then
  curl -fsSL -o "$HOME/bin/kubectl" \
    "https://dl.k8s.io/release/v1.31.5/bin/linux/amd64/kubectl"
  chmod +x "$HOME/bin/kubectl"
fi

k3d kubeconfig merge almachess --kubeconfig-merge-default >/dev/null
kubectl config use-context k3d-almachess >/dev/null
```
