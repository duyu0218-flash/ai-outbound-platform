FROM python:3.11-slim
WORKDIR /app
COPY voice_gateway/pyproject.toml ./
COPY voice_gateway/app ./app
RUN pip install --no-cache-dir .
EXPOSE 8002
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8002"]
