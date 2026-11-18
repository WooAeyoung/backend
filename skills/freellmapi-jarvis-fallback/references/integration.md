# FreeLLMAPI integration reference

Source reviewed: <https://github.com/tashfeenahmed/freellmapi>

Check the upstream documentation again before relying on model names, provider counts, quotas, or installation commands; these are expected to change.

## Relevant upstream behavior

- FreeLLMAPI is a self-hosted router that aggregates provider free tiers and custom OpenAI-compatible endpoints.
- It exposes OpenAI-compatible endpoints, including `/v1/chat/completions`, and uses a unified bearer key for clients.
- Provider credentials are entered into the router and stored encrypted. The router needs a stable encryption key for real credentials.
- The default local address in upstream examples is `http://localhost:3001`.
- The project describes itself as intended for personal experimentation. Confirm terms independently before production or commercial use.

## Application contract

Recommended server-only settings:

```text
FREELLMAPI_BASE_URL=https://private-router.example
FREELLMAPI_API_KEY=<unified-router-key>
FREELLMAPI_MODEL=<model-or-router-alias>
```

Normalize the base URL so the client produces one `/v1/chat/completions` path. Send:

```http
POST /v1/chat/completions
Authorization: Bearer <unified-router-key>
Content-Type: application/json
```

Minimal non-streaming body:

```json
{
  "model": "<configured-model>",
  "messages": [
    {"role": "user", "content": "<application prompt>"}
  ]
}
```

Read the answer from `choices[0].message.content`. Treat a missing or blank value as an empty response and continue the application's fallback policy.

## Deployment checks

1. From the application host, confirm the router URL is reachable; browser access from the user's PC does not prove server-to-server reachability.
2. Query `/v1/models` with the unified bearer key when supported to validate URL and authentication without sending user content.
3. Send one low-risk application request and check latency, selected provider, and final failure category.
4. Confirm no key appears in frontend bundles, logs, source control, or returned JSON.

## Failure mapping

| Condition | Application category | Retry behavior |
|---|---|---|
| Missing URL or key | not configured | Do not retry automatically |
| 401 or 403 | authentication | Do not ask the end user to retry |
| 429 | rate limit | Try the next provider once; otherwise advise waiting |
| 5xx | provider error | Bounded retry or next provider |
| Connection/timeout | network or timeout | Next provider; no retry loop |
| Empty/malformed answer | empty response | Next provider |

Keep provider details operational. End-user wording should be friendly and should not mention secret names or internal stack traces.
