ARG PYTHON_BASE_IMAGE=python:3.11-slim
FROM ${PYTHON_BASE_IMAGE}
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
WORKDIR /app
COPY vendor/pipecat /opt/pipecat-patch/vendor/pipecat
COPY scripts/build-pipecat-wheel.py /opt/pipecat-patch/scripts/build-pipecat-wheel.py
RUN python /opt/pipecat-patch/scripts/build-pipecat-wheel.py --output-dir /opt/wheels
COPY voice_gateway/pyproject.toml ./
COPY voice_gateway/app ./app
RUN pip install --no-cache-dir "pip==26.2.1" "setuptools==84.0.0" \
    && pip install --no-cache-dir --find-links /opt/wheels . && pip check
COPY scripts/check-pipecat-distribution.py /opt/pipecat-patch/scripts/check-pipecat-distribution.py
RUN python /opt/pipecat-patch/scripts/check-pipecat-distribution.py
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
RUN mkdir -p /var/lib/voice-security && chown app:app /var/lib/voice-security && chmod 700 /var/lib/voice-security
USER app
EXPOSE 8002
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8002"]
