ARG PYTHON_BASE_IMAGE=python:3.11-slim
FROM ${PYTHON_BASE_IMAGE}
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    NLTK_DATA=/opt/nltk_data
WORKDIR /app
COPY voice_gateway/pyproject.toml ./
COPY voice_gateway/app ./app
RUN pip install --no-cache-dir .
RUN python -c "import nltk; nltk.download('punkt_tab', download_dir='/opt/nltk_data', quiet=True, raise_on_error=True); nltk.data.find('tokenizers/punkt_tab')"
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
USER app
EXPOSE 8002
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8002"]
