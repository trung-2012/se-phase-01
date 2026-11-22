#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NGINX_CONF="$ROOT_DIR/nginx/nginx-kaggle.conf"
JAR_PATH=$(ls "$ROOT_DIR"/target/cinema-*.jar 2>/dev/null | grep -v '\.original$' | head -n 1 || true)

stop_cluster() {
    if [ -f /tmp/cinema-nginx.pid ]; then
        nginx -c "$NGINX_CONF" -s stop 2>/dev/null || true
    fi
    for port in 8081 8082; do
        if [ -f "/tmp/cinema-app-${port}.pid" ]; then
            kill "$(cat "/tmp/cinema-app-${port}.pid")" 2>/dev/null || true
            rm -f "/tmp/cinema-app-${port}.pid"
        fi
    done
}

if [ "${1:-start}" = "stop" ]; then
    stop_cluster
    exit 0
fi

if [ -z "$JAR_PATH" ]; then
    "$ROOT_DIR/mvnw" -f "$ROOT_DIR/pom.xml" package -DskipTests
    JAR_PATH=$(ls "$ROOT_DIR"/target/cinema-*.jar | grep -v '\.original$' | head -n 1)
fi

stop_cluster

SERVER_PORT=8081 nohup java -jar "$JAR_PATH" > /tmp/cinema-app-8081.log 2>&1 &
echo $! > /tmp/cinema-app-8081.pid
for _ in $(seq 1 30); do curl -sf http://127.0.0.1:8081/actuator/health >/dev/null && break || sleep 1; done

SERVER_PORT=8082 nohup java -jar "$JAR_PATH" > /tmp/cinema-app-8082.log 2>&1 &
echo $! > /tmp/cinema-app-8082.pid
for _ in $(seq 1 30); do curl -sf http://127.0.0.1:8082/actuator/health >/dev/null && break || sleep 1; done

nginx -c "$NGINX_CONF"
echo "Multi-instance cluster ready at http://127.0.0.1:8088"