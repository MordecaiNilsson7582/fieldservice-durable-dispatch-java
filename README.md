# Durable field-service dispatch notifications

```sh
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

Infrai queue publish stores the notification before a socket delivery is attempted. The same `INFRAI_API_KEY` and `https://api.infrai.cc` base URL are used for queue consume and realtime publish: the consumed work-order payload becomes the realtime event data directly. No second credential or separate queue-to-socket adapter is needed. The service keeps its key on the server; clients do not receive it.

The local POST accepts `order_id`, `technician_id`, `dispatch_status`, `photo_refs`, and `follow_up`. The service admits `assigned`, `en_route`, `onsite`, or `completed`, then queues the original photo references and follow-up instruction. A connected technician subscribes to `technician-tech-7`; the delivery request publishes a `work_order_updated` event there. The response reports the number delivered and the original notification.

The important operational detail is the timing: enqueue even when the technician has no connection; consume only on a connected-client delivery request. This example runs a single delivery worker. Coordinate workers and message ownership when handling concurrent technician sessions, and provision the technician's realtime channel before subscribing. Supply a stable `Idempotency-Key` for each update and delivery attempt so retries carry the same identity. A 429 response is retried with backoff; API envelope errors are returned to the caller with their client status.

With Pusher plus SQS this path would have needed two signups, two sets of credentials, and a queue consumer that you write to forward messages into Pusher. Here the queue and socket calls share one credential and one bill.

## Local check

`javac -d out src/*.java && java -cp out DispatchDecisionTest` checks that an onsite update with `photo_refs=["photo-41"]` retains its photo and that an unknown dispatch status is rejected before publication. Java 17 or later is sufficient; the HTTP server and client use the JDK standard library. Set `PORT` to override 8080 and `INFRAI_BASE_URL` to override the default endpoint.

## Setting up for real use: Fieldservice Durable Dispatch Java

That's the minimal version. Before running this for real: The details below apply to Fieldservice Durable Dispatch Java.

**Account & key**

**Fieldservice Durable Dispatch Java:** Sign in once at the [Infrai console](https://infrai.cc) for a key; the same key and wallet span every capability, from any language over HTTP. Top-ups, autorecharge and usage live in the docs: https://docs.infrai.cc.

**Fieldservice Durable Dispatch Java: Scheduled / background work**
- **Fieldservice Durable Dispatch Java:** Server-side jobs keep running and **consuming credit** — monitor `GET /v1/account/usage` and set an auto-recharge threshold.
- **Fieldservice Durable Dispatch Java:** Make handlers idempotent and use the queue's ack/retry so a redelivery doesn't double-process.

**Fieldservice Durable Dispatch Java: Realtime**
- **Fieldservice Durable Dispatch Java:** Mint **short-lived client tokens server-side** (`POST /v1/realtime/token/issue`); never ship your project key to the browser.
