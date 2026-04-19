#!/bin/bash
set -e

TARGET_DIR="/workspaces/target"
APP_LOG="/tmp/plugin-sandbox-app.log"

case "${CODESPACE_NAME:-}" in
  *flask*)  _DEFAULT_REPO="https://github.com/pallets/flask.git" ;;
  *django*) _DEFAULT_REPO="https://github.com/django/django.git" ;;
  *)        _DEFAULT_REPO="https://github.com/fastapi/fastapi.git" ;;
esac
TARGET_REPO="${TARGET_REPO_URL:-$_DEFAULT_REPO}"

echo "==> Cloning $TARGET_REPO into $TARGET_DIR..."
if [ ! -d "$TARGET_DIR/.git" ]; then
  git clone --depth 1 "$TARGET_REPO" "$TARGET_DIR"
fi
echo "==> Clone complete."

ORIGIN="$(git -C "$TARGET_DIR" remote get-url origin 2>/dev/null || true)"
cd "$TARGET_DIR"

touch "$APP_LOG"
echo "==> Installing dependencies and launching app on port 8000..."
case "$ORIGIN" in
  *flask*)
    pip install -q -e . -e examples/tutorial
    python -m flask --app examples/tutorial/flaskr init-db > /tmp/plugin-sandbox-flask-init.log 2>&1 || true
    nohup python -m flask --app examples/tutorial/flaskr run --host 0.0.0.0 --port 8000 > "$APP_LOG" 2>&1 &
    ;;
  *django*)
    pip install -q -e .
    RUNTIME_DIR="$TARGET_DIR/.plugin-sandbox-runtime/django-demo"
    mkdir -p "$RUNTIME_DIR"
    [ -f "$RUNTIME_DIR/manage.py" ] || django-admin startproject sandbox_app "$RUNTIME_DIR"
    cd "$RUNTIME_DIR"
    python manage.py migrate --noinput > /tmp/plugin-sandbox-django-migrate.log 2>&1
    nohup python manage.py runserver 0.0.0.0:8000 > "$APP_LOG" 2>&1 &
    ;;
  *)
    pip install -q fastapi uvicorn
    nohup python -m uvicorn docs_src.body_updates.tutorial001_py310:app --host 0.0.0.0 --port 8000 > "$APP_LOG" 2>&1 &
    ;;
esac

echo "==> App started (PID=$!). Streaming logs — press Ctrl+C to detach:"
tail -f "$APP_LOG"
