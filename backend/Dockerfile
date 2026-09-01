ARG NODE_BASE_IMAGE=node:22-alpine
ARG PYTHON_BASE_IMAGE=python:3.11-slim

FROM ${NODE_BASE_IMAGE} AS frontend-builder

WORKDIR /workspace

COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml /workspace/frontend/
RUN corepack enable && cd /workspace/frontend && pnpm install --frozen-lockfile

COPY frontend /workspace/frontend
COPY backend /workspace/backend
RUN cd /workspace/frontend && pnpm build


FROM ${PYTHON_BASE_IMAGE}

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY backend/pyproject.toml /app/pyproject.toml
COPY backend/app /app/app
COPY backend/migrations /app/migrations
COPY --from=frontend-builder /workspace/backend/app/static /app/app/static
RUN pip install --no-cache-dir .

RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
USER app

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
