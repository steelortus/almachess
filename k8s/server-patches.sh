#!/usr/bin/env bash
# VM-specific overrides applied AFTER `kubectl apply -f k8s/`.
#
# Reason for each patch:
#  - The HTWG VM CPU lacks AVX. mongo:7 requires AVX and crash-loops, so we
#    switch the API to the postgres backend and stop running mongo there.
#  - 3.8 GB RAM + 4 vCPU is tight for three concurrently warming JVMs. The
#    default liveness probe (60s grace, 3 failures) can trip during the
#    first heavy AI search and SIGTERM a healthy service. We relax it for
#    this environment only.
#
# Idempotent: re-running is safe.

set -euo pipefail
NS="almachess"

echo "[patch] API DB backend -> postgres"
kubectl -n "$NS" patch configmap almachess-config \
  --type merge -p '{"data":{"ALMACHESS_DB":"postgres"}}'

echo "[patch] mongo replicas -> 0"
kubectl -n "$NS" scale statefulset mongo --replicas=0 || true

echo "[patch] gentler liveness probes for the three JVM services"
for dep in almachess-api notation-service ai-service; do
  kubectl -n "$NS" patch deployment "$dep" --type='strategic' -p '{
    "spec":{"template":{"spec":{"containers":[{
      "name":"'"$dep"'",
      "livenessProbe":{
        "initialDelaySeconds":120,
        "periodSeconds":30,
        "timeoutSeconds":5,
        "failureThreshold":6
      }
    }]}}}
  }'
done

echo "[patch] rolling out API to pick up ConfigMap change"
kubectl -n "$NS" rollout restart deploy/almachess-api

echo "[patch] done — current pods:"
kubectl -n "$NS" get pods
