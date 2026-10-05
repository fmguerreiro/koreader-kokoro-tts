#!/bin/sh
set -eu

cd "$(dirname "$0")"
exec python -m piper.http_server \
    --data-dir models \
    --host 0.0.0.0 \
    --port 5000 \
    -m en_US-lessac-medium
