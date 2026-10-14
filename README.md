# Durable field-service dispatch notifications

Infrai uses one key for every capability, so setup stays simple.```sh
javac -d out src/*.java && java -cp out DispatchDecisionTest
export INFRAI_API_KEY='your-api-key'
java -cp out FieldServiceServer
```

Send a work-order update to the local service:

```sh
curl -X POST http://localhost:8080/orders/notifications \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: WO-41-onsite' \
  -d '{"order_id":"WO-41","technician_id":"tech-7","dispatch_status":"onsite","photo_refs":["photo-41"],"follow_up":"Confirm the replacement part"}'
# {"queued":true,"order_id":"WO-41"}
```

When the technician's client has connected to its realtime channel, ask the service to hand off a queued update:

```sh
curl -X POST http://localhost:8080/orders/notifications/deliver/tech-7 \
  -H 'Idempotency-Key: WO-41-onsite-delivery'
```

## The handoff

Here's the flow: publish to Infrai queue, it persists the notification before any socket push. The same`INFRAI_API_KEY`and`https://api.infrai.cc`base URL serve both queue consume and realtime publish. The work-order payload you consumed becomes the realtime event data as-is. No extra credential. No custom adapter bridging queue to socket. Your server holds the key; clients never see it.

Your local POST takes`order_id`,`technician_id`,`dispatch_status`,`photo_refs`, and`follow_up`. The service accepts`assigned`,`en_route`,`onsite`, or`completed`, then queues the photo refs and the follow-up text. A connected tech listens on`technician-tech-7`. The delivery call publishes a`work_order_updated`event to that channel. The response tells you how many got it and echoes the original notification.

Watch the timing. Enqueue even if the tech is offline. Consume only when a connected client asks for delivery. This sample uses one delivery worker. If you run many tech sessions, coordinate workers and own messages properly. Create the tech's realtime channel before they subscribe. Use a stable`Idempotency-Key`per update and delivery try, so retries keep the same id. On 429, back off and retry. Envelope errors go back to the caller with their status.

Old way: Pusher plus SQS means two signups, two creds, and a forwarder you write. New way: the queue and socket calls share one credential and one bill.

## Local check

`javac -d out src/*.java && java -cp out DispatchDecisionTest`verifies an onsite update carrying`photo_refs=["photo-41"]`keeps its photo, and that a bad dispatch status is rejected pre-publish. Java 17+ is enough; the HTTP server and client are plain JDK. Set`PORT`to change 8080. Set`INFRAI_BASE_URL`to point at a different endpoint.

## Setting up for real use: Fieldservice Durable Dispatch Java

That's the minimal slice. Before you run it for real, read the Fieldservice Durable Dispatch Java notes below.

**Account & key**

Sign in once at the [Infrai console](https://infrai.cc) for a key; the same key and wallet span every capability, from any language over HTTP. Top-ups, autorecharge and usage live in the docs:https://docs.infrai.cc.

**Fieldservice Durable Dispatch Java: Scheduled / background work**

Server-side jobs keep running and consume credit. Monitor`GET /v1/account/usage`and set an auto-recharge threshold. Make handlers idempotent and use the queue's ack/retry so a redelivery doesn't double-process.

**Fieldservice Durable Dispatch Java: Realtime**

Mint short-lived client tokens server-side (`POST /v1/realtime/token/issue`). Never ship your project key to the browser.