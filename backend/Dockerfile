FROM node:22-alpine AS frontend-builder

WORKDIR /workspace

COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml /workspace/frontend/
RUN corepack enable && cd /workspace/frontend && pnpm install --frozen-lockfile

COPY frontend /workspace/frontend
COPY backend /workspace/backend
RUN cd /workspace/frontend && pnpm build


FROM python:3.11-slim

WORKDIR /app

RUN pip install --no-cache-dir --upgrade pip

COPY backend/pyproject.toml /app/pyproject.toml
COPY backend/app /app/app
RUN pip install --no-cache-dir .

COPY --from=frontend-builder /workspace/backend/app/static /app/app/static

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
