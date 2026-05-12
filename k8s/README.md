# AlMaChess on Kubernetes

Plain manifests for a local k3d cluster (or any vanilla Kubernetes with a
default `StorageClass` and an Ingress controller). All resources live in the
`almachess` namespace.

## Layout

| File                     | What it creates                                    |
| ------------------------ | -------------------------------------------------- |
| `namespace.yaml`         | Namespace `almachess`                              |
| `configmap.yaml`         | Non-secret env (ports, service URLs, DB hostnames) |
| `secret.yaml`            | Demo `POSTGRES_PASSWORD` — replace before prod     |
| `mongo.yaml`             | Mongo 7 StatefulSet + headless Service + PVC       |
| `postgres.yaml`          | Postgres 16 StatefulSet + headless Service + PVC   |
| `redis.yaml`             | Redis 7 Deployment + Service                       |
| `notation-service.yaml`  | NotationService Deployment + Service               |
| `ai-service.yaml`        | AiService Deployment + Service (Stockfish in img)  |
| `almachess-api.yaml`     | Main API Deployment + Service                      |
| `web.yaml`               | Nginx web tier Deployment + Service                |
| `ingress.yaml`           | Ingress (Traefik) routing to `web`                 |

The three Scala services all use the same image (`almachess:latest`) and
differ only in `MAIN_CLASS`, mirroring the Compose setup.

## Local cluster (k3d)

```bash
# 1) Install k3d (https://k3d.io) — once.
# 2) Cluster with host-port 8088 mapped to the bundled Traefik ingress (80)
k3d cluster create almachess \
  --port "8088:80@loadbalancer" \
  --agents 1

# 3) Build the two images locally (from the repo root)
docker build -t almachess:latest .
docker build -t almachess-web:latest ./web

# 4) Import them into the k3d cluster (otherwise nodes can't pull them)
k3d image import almachess:latest almachess-web:latest -c almachess

# 5) Apply manifests
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/

# 6) Watch things come up
kubectl -n almachess get pods -w
```

The app is then reachable at:

- `http://almachess.localhost:8088` (preferred — uses the host rule)
- `http://localhost:8088` (fallback — uses the catch-all rule)

The Ingress proxies `/` to the `web` Service; nginx inside `web` then proxies
`/api`, `/notation`, `/ai`, `/health` onto the respective Services by their
in-cluster DNS names.

## Smoke tests (after pods are Ready)

```bash
curl -fsS http://localhost:8088/health           # API /health (via ingress -> web -> api)
curl -fsS http://localhost:8088/notation/health  # NotationService
curl -fsS http://localhost:8088/ai/health        # AiService
curl -fsS http://localhost:8088/api/game

curl -fsS -X POST http://localhost:8088/api/game/reset
curl -fsS -X POST http://localhost:8088/api/game/move \
  -H 'Content-Type: application/json' -d '{"from":"e2","to":"e4"}'
curl -fsS -X POST http://localhost:8088/api/game/ai-move \
  -H 'Content-Type: application/json' -d '{"depth":2}'
```

## Re-deploy after a code change

```bash
docker build -t almachess:latest .
k3d image import almachess:latest -c almachess
kubectl -n almachess rollout restart deploy/almachess-api deploy/notation-service deploy/ai-service
```

For web-tier changes:

```bash
docker build -t almachess-web:latest ./web
k3d image import almachess-web:latest -c almachess
kubectl -n almachess rollout restart deploy/web
```

## Teardown

```bash
kubectl delete namespace almachess           # removes Pods, Services, PVCs
k3d cluster delete almachess                 # removes the cluster
```

## Notes

- On Windows + Docker Desktop, k3d's generated kubeconfig points at
  `host.docker.internal:<port>`. That hostname can be unreachable from the
  host (firewall / DNS quirk). If `kubectl get nodes` hangs with a
  `connectex` timeout, repoint the cluster at the loopback:

  ```powershell
  $port = (docker port k3d-almachess-serverlb 6443/tcp).Split(':')[-1]
  kubectl config set-cluster k3d-almachess --server="https://127.0.0.1:$port"
  ```

- `imagePullPolicy: IfNotPresent` is what lets `k3d image import` work — the
  kubelet won't try to pull `almachess:latest` from a registry it doesn't know.
- `POSTGRES_PASSWORD` in `secret.yaml` is intentionally weak/visible — replace
  it (or drop the file and `kubectl create secret generic almachess-secrets
  --from-literal=POSTGRES_PASSWORD=...`) before any non-local deploy.
- Mongo runs without auth here, identical to the Compose setup. Tighten this
  before exposing it.
- StatefulSets need a `StorageClass` that supports `ReadWriteOnce`. k3d ships
  with `local-path` by default, which is fine for local use.
