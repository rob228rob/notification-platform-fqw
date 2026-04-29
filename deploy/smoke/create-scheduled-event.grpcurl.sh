#!/usr/bin/env sh
set -eu

# Usage:
#   sh deploy/smoke/create-scheduled-event.grpcurl.sh
# Optional env:
#   GRPC_ADDR=localhost:9090
#   SEND_AT=2026-03-22T18:30:00Z

GRPC_ADDR="${GRPC_ADDR:-localhost:9090}"
SEND_AT="${SEND_AT:-$(date -u -d '+10 minutes' +%Y-%m-%dT%H:%M:%SZ)}"
IDEMPOTENCY_KEY="${IDEMPOTENCY_KEY:-sched-$(date -u +%Y%m%dT%H%M%SZ)}"

grpcurl -plaintext \
  -import-path libs/proto-common/src/main/proto \
  -import-path libs/proto-facade/src/main/proto \
  -proto notification/facade/v1/facade.proto \
  -d "{
    \"idempotencyKey\": \"${IDEMPOTENCY_KEY}\",
    \"templateId\": \"tmpl-order-reminder\",
    \"templateVersion\": 1,
    \"priority\": \"DELIVERY_PRIORITY_NORMAL\",
    \"preferredChannel\": \"CHANNEL_EMAIL\",
    \"strategy\": {
      \"kind\": \"STRATEGY_KIND_SCHEDULED\",
      \"sendAt\": \"${SEND_AT}\"
    },
    \"payload\": {
      \"subject\": \"Scheduled test\",
      \"body\": \"Hello from scheduled grpcurl request\"
    },
    \"audience\": {
      \"kind\": \"AUDIENCE_KIND_EXPLICIT\",
      \"snapshotOnDispatch\": true,
      \"recipientId\": [\"user-1\"]
    }
  }" \
  "${GRPC_ADDR}" \
  notification.facade.v1.NotificationFacade/CreateNotificationEvent
