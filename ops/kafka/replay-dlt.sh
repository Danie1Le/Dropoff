#!/usr/bin/env bash
# Replays a dead-letter topic: reads <topic>.DLT and sends each record back to <topic> with its original key and its
# __TypeId__ header, so the consumers try it again (ticket #32). Safe to repeat: consumers skip an event id they have
# already processed (processed_events).
#
# Usage: ops/kafka/replay-dlt.sh <topic>        e.g. ops/kafka/replay-dlt.sh order-events
#
# Runs the Kafka CLI inside the Compose `kafka` container, so start it first (`docker compose up -d kafka`). Records
# are read as the consumer group `replay-dlt-<topic>`, so a record is replayed once; the script stops after 10 s with
# no new record. Fix the cause before replaying, or the record fails again and lands back in the DLT.
set -euo pipefail

topic="${1:?usage: ops/kafka/replay-dlt.sh <topic>}"
case "$topic" in
  *.DLT) echo "Pass the topic, not its DLT: ${topic%.DLT}" >&2; exit 2 ;;
esac

docker compose exec -T kafka bash -s -- "$topic" <<'REPLAY'
set -euo pipefail
topic="$1"
bin=/opt/kafka/bin
bootstrap=localhost:9092
# Control characters as separators: the DLT headers hold exception messages and stack traces with spaces, tabs,
# commas, colons and line breaks, so no printable separator is safe. Each header prints as name:value, and only
# __TypeId__ is kept: consumers read the event type from it.
rs=$'\x1e' fs=$'\x1c' hs=$'\x1f'

"$bin/kafka-console-consumer.sh" --bootstrap-server "$bootstrap" --topic "$topic.DLT" \
    --group "replay-dlt-$topic" --command-property auto.offset.reset=earliest --timeout-ms 10000 \
    --formatter-property print.headers=true --formatter-property print.key=true \
    --formatter-property "line.separator=$rs" --formatter-property "key.separator=$fs" \
    --formatter-property "headers.separator=$hs" 2>/dev/null \
  | awk -v RS="$rs" -v FS="$fs" -v hs="$hs" '
      NF >= 3 {
        type = ""
        n = split($1, headers, hs)
        for (i = 1; i <= n; i++) {
          if (index(headers[i], "__TypeId__:") == 1) type = substr(headers[i], 12)
        }
        value = $3
        for (i = 4; i <= NF; i++) value = value FS $i
        out = "dlt-replayed:true"
        if (type != "") out = out ",__TypeId__:" type
        printf "%s\t%s\t%s\n", out, $2, value
        count++
      }
      END { printf "%d\n", count > "/tmp/replay-count" }' \
  | "$bin/kafka-console-producer.sh" --bootstrap-server "$bootstrap" --topic "$topic" \
      --reader-property parse.key=true --reader-property parse.headers=true

echo "Replayed $(cat /tmp/replay-count) record(s) from $topic.DLT to $topic"
REPLAY
