#!/usr/bin/env bash
# Usage: ./k8s/deploy.sh [image-tag]   (builds image, loads it into minikube/kind if present, applies manifests)
set -euo pipefail
TAG="${1:-1.0.0}"
IMAGE="countryinfo:${TAG}"
DIR="$(cd "$(dirname "$0")" && pwd)"

docker build -t "$IMAGE" "$DIR/.."
if command -v minikube >/dev/null && minikube status >/dev/null 2>&1; then
  minikube image load "$IMAGE"
elif command -v kind >/dev/null && kind get clusters 2>/dev/null | grep -q .; then
  kind load docker-image "$IMAGE"
fi

kubectl apply -f "$DIR/00-namespace-config.yaml"
kubectl apply -f "$DIR/10-mysql.yaml"
kubectl -n countryinfo rollout status statefulset/mysql --timeout=180s
kubectl apply -f "$DIR/20-app.yaml"
kubectl -n countryinfo set image deployment/countryinfo countryinfo="$IMAGE"
kubectl -n countryinfo rollout status deployment/countryinfo --timeout=300s
kubectl -n countryinfo get pods,svc,hpa,ingress
