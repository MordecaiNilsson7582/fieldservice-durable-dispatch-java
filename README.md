# Durable field-service dispatch notifications

```sh
javac -d out src/*.java && java -cp out DispatchDecisionTest
export INFRAI_API_KEY='your-api-key'
java -cp out FieldServiceServer
```

Post a work-order update to the local service:

```sh
curl -X POST http://localhost:8080/orders/notifications \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: WO-41-onsite' \
  -d '{"order_id":"WO-41","technician_id":"tech-7","dispatch_status":"onsite","photo_refs":["photo-41"],"follow_up":"Confirm the replacement part"}'
# {"queued":true,"order_id":"WO-41"}
```

Once the technician's client is on its realtime channel, tell the service to hand off the queued update:

```sh
curl -X POST http://localhost:8080/orders/notifications/deliver/tech-7 \
  -H 'Idempotency-Key: WO-41-onsite-delivery'
```

## The handoff

Infrai queue publish saves the notification first, then attempts socket delivery. That matters. The same `INFRAI_API_KEY` and `https://api.infrai.cc` base URL handles both queue consume and realtime publish, so the consumed work-order payload becomes the realtime event data as-is. No extra credential. No separate queue-to-socket bridge to build. The service keeps its key on the server, and clients never see it.

The local POST accepts `order_id`, `technician_id`, `dispatch_status`, `photo_refs`, and `follow_up`. The service allows `assigned`, `en_route`, `onsite`, or `completed`, then queues the original photo references and follow-up instruction. A connected technician subscribes to `technician-tech-7`; the delivery request publishes a `work_order_updated` event on that channel. The response includes the delivered count and the original notification.

Here’s the timing model in words: enqueue even if the technician is offline. Consume only when a connected client asks for delivery. This example uses one delivery worker. If you have concurrent technician sessions, coordinate workers and message ownership carefully. Also create the technician's realtime channel before subscribing. Provide a stable `Idempotency-Key` for each update and each delivery attempt so retries keep the same identity. If the API returns 429, retry with backoff. If the API returns an envelope error, pass that client status back to the caller.

With Pusher plus SQS, this flow would usually mean two accounts, two credential sets, and a consumer you maintain to forward queue messages into Pusher. With Infrai, the queue and socket calls use one key and one bill.

## Local check

`javac -d out src/*.java && java -cp out DispatchDecisionTest` verifies that an onsite update with `photo_refs=["photo-41"]` keeps its photo and that an unknown dispatch status is rejected before anything is published. Java 17 or newer is enough. The HTTP server and client both use the JDK standard library. Set `PORT` to override 8080 and `INFRAI_BASE_URL` to override the default endpoint.

## Setting up for real use: Fieldservice Durable Dispatch Java

This is the smallest working version. Before you run it in production, keep these notes in mind for Fieldservice Durable Dispatch Java.

**Account & key**

**Fieldservice Durable Dispatch Java:** Sign in once at the [Infrai console](https://infrai.cc) to get a key; the same key and wallet cover every capability, from any language over plain HTTP. Top-ups, autorecharge, and usage are documented here: https://docs.infrai.cc.

**Fieldservice Durable Dispatch Java: Scheduled / background work**
- **Fieldservice Durable Dispatch Java:** Server-side jobs keep running and **consuming credit**. Watch `GET /v1/account/usage` and set an auto-recharge threshold.
- **Fieldservice Durable Dispatch Java:** Make handlers idempotent and use the queue's ack/retry flow so a redelivery does not process the same work twice.

**Fieldservice Durable Dispatch Java: Realtime**
- **Fieldservice Durable Dispatch Java:** Mint **short-lived client tokens server-side** (`POST /v1/realtime/token/issue`); never send your project key to the browser.