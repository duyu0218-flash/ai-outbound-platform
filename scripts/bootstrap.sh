#!/usr/bin/env bash
set -euo pipefail

cp .env.example .env
mkdir -p backend/app backend/app/api/routers
echo "bootstrap done"
