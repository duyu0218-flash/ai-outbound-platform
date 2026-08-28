#!/usr/bin/env bash
set -euo pipefail

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "created .env from .env.example"
else
  echo "kept existing .env"
fi
mkdir -p backend/app backend/app/api/routers
echo "bootstrap done"
