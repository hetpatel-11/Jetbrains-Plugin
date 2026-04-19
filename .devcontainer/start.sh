#!/bin/bash
set -e

echo "🚀 Starting sandbox..."

rm -f devcontainer.txt

FASTAPI_REPO="https://github.com/fastapi/fastapi.git"
FLASK_REPO="https://github.com/pallets/flask.git"
DJANGO_REPO="https://github.com/django/django.git"

infer_target_repo() {
  case "${CODESPACE_NAME:-}" in
    *flask*)
      echo "$FLASK_REPO"
      ;;
    *django*)
      echo "$DJANGO_REPO"
      ;;
    *)
      echo "$FASTAPI_REPO"
      ;;
  esac
}

# Allow override via environment variable
TARGET_REPO="${TARGET_REPO_URL:-$(infer_target_repo)}"
TARGET_DIR="/workspaces/target"
APP_LOG="/tmp/plugin-sandbox-app.log"

echo "📥 Using repo: $TARGET_REPO"

# Clone repo
if [ ! -d "$TARGET_DIR/.git" ]; then
  rm -rf "$TARGET_DIR"
  git clone --depth 1 "$TARGET_REPO" "$TARGET_DIR"
fi

rm -f "$TARGET_DIR/devcontainer.txt"
mkdir -p "$TARGET_DIR/.devcontainer"
cat > "$TARGET_DIR/.devcontainer/devcontainer.json" <<'JSON'
{
  "name": "Ephemeral Sandbox Target",
  "image": "mcr.microsoft.com/devcontainers/python:3.10",
  "postCreateCommand": "pip install fastapi uvicorn git",
  "postStartCommand": "bash .devcontainer/start.sh",
  "forwardPorts": [8000],
  "portsAttributes": {
    "8000": {
      "label": "Sandbox App",
      "onAutoForward": "openBrowser"
    }
  }
}
JSON

cat > "$TARGET_DIR/.devcontainer/start.sh" <<'SH'
#!/bin/bash
set -e

echo "🚀 Starting target sandbox..."

FASTAPI_REPO="https://github.com/fastapi/fastapi.git"
FLASK_REPO="https://github.com/pallets/flask.git"
DJANGO_REPO="https://github.com/django/django.git"
TARGET_DIR="$(pwd)"
TARGET_REPO_URL="$(git remote get-url origin 2>/dev/null || true)"

python -m pip install --upgrade pip

if [ "$TARGET_REPO_URL" = "$FASTAPI_REPO" ]; then
  if [ -f requirements.txt ]; then
    pip install -r requirements.txt
  else
    pip install fastapi uvicorn
  fi
  python -m uvicorn docs_src.body_updates.tutorial001_py310:app --host 0.0.0.0 --port 8000
elif [ "$TARGET_REPO_URL" = "$FLASK_REPO" ]; then
  pip install -e .
  pip install -e examples/tutorial
  cd examples/tutorial
  python -m flask --app flaskr init-db >/tmp/plugin-sandbox-flask-init.log 2>&1 || true
  python -m flask --app flaskr run --host 0.0.0.0 --port 8000
elif [ "$TARGET_REPO_URL" = "$DJANGO_REPO" ]; then
  pip install -e .
  RUNTIME_DIR="$TARGET_DIR/.plugin-sandbox-runtime/django-demo"
  mkdir -p "$RUNTIME_DIR"
  if [ ! -f "$RUNTIME_DIR/manage.py" ]; then
    django-admin startproject sandbox_app "$RUNTIME_DIR"
  fi
  cd "$RUNTIME_DIR"
  python manage.py migrate --noinput >/tmp/plugin-sandbox-django-migrate.log 2>&1
  python manage.py runserver 0.0.0.0:8000
else
  if [ -f requirements.txt ]; then
    pip install -r requirements.txt
  else
    pip install fastapi uvicorn
  fi
  python -m uvicorn docs_src.body_updates.tutorial001_py310:app --host 0.0.0.0 --port 8000
fi
SH

chmod +x "$TARGET_DIR/.devcontainer/start.sh"

cd "$TARGET_DIR"

echo "📦 Installing dependencies..."

echo "🔥 Launching app..."
bash .devcontainer/start.sh >"$APP_LOG" 2>&1 || {
  echo "❌ Failed to start app. Keeping container alive for debugging..."
  cat "$APP_LOG" || true
  sleep 300
}
