FROM python:3.13-slim
WORKDIR /app
COPY pyproject.toml .
COPY src src
RUN pip install --no-cache-dir .
ENV PYTHONUNBUFFERED=1
CMD ["python","-m","sitepilot_agent_runtime.worker"]
