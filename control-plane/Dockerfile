FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Fetch Xray-core .proto files and compile Python gRPC stubs (including transitive deps) into /app/grpc_stubs
RUN mkdir -p /tmp/xray-proto /app/grpc_stubs && \
    git clone --depth 1 https://github.com/XTLS/Xray-core.git /tmp/xray-proto && \
    python -m grpc_tools.protoc \
      -I/tmp/xray-proto \
      --python_out=/app/grpc_stubs \
      --grpc_python_out=/app/grpc_stubs \
      $(find /tmp/xray-proto/common /tmp/xray-proto/proxy/vless /tmp/xray-proto/transport/internet /tmp/xray-proto/app/proxyman /tmp/xray-proto/app/stats -name '*.proto') && \
    find /app/grpc_stubs -type d -exec touch {}/__init__.py \; && \
    rm -rf /tmp/xray-proto

ENV PYTHONPATH="/app:/app/grpc_stubs"

COPY . .

EXPOSE 8000
