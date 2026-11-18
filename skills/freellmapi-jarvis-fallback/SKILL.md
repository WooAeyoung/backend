---
name: freellmapi-jarvis-fallback
description: Add, repair, or verify a FreeLLMAPI-based fallback for Jarvis or another OpenAI-compatible application when paid-provider authentication, quota, or rate-limit failures interrupt answers. Use for FreeLLMAPI setup, provider-chain integration, safe failover, and related deployment diagnostics; do not use it to promise keyless or unlimited production inference.
---

# FreeLLMAPI Jarvis Fallback

Connect an existing application to a user-controlled FreeLLMAPI router without weakening its current providers, security, or local deterministic answers.

Before implementation, read [references/integration.md](references/integration.md). Re-check the upstream repository when exact endpoints, supported models, deployment steps, or limits may have changed.

## Outcome

- Keep application-owned deterministic answers first when they can answer safely.
- Keep working primary providers ahead of FreeLLMAPI unless the user requests another order.
- Add FreeLLMAPI through its OpenAI-compatible API as a bounded fallback.
- Return a useful local response or a precise, friendly failure category when every provider fails.
- Verify the deployed path with a real request; a successful build alone is not completion.

## Workflow

1. Inspect the application's provider chain, request schema, timeouts, deployment target, and existing secret names.
2. Determine where the FreeLLMAPI router runs. A cloud application cannot reach the user's `localhost`; require a private reachable URL or deploy the router separately with authentication.
3. Use server-side configuration such as `FREELLMAPI_BASE_URL`, `FREELLMAPI_API_KEY`, and `FREELLMAPI_MODEL`. Never place the unified key in frontend code, logs, commits, screenshots, or error bodies.
4. Call the OpenAI-compatible `/v1/chat/completions` endpoint. Preserve conversation-role validation, input limits, and the application's prompt safety rules.
5. Use a short bounded timeout and try the next configured provider on authentication, rate-limit, provider, network, or empty-response failures. Avoid unbounded retries and retry storms.
6. Distinguish configuration failures from temporary failures. Do not tell users to retry an invalid key; do not expose provider response bodies that may contain secrets.
7. Add focused tests for provider order, missing configuration, timeout, 401/403, 429, 5xx, malformed output, and successful fallback.
8. Deploy only when authorized. Confirm the deployment status, send a real Jarvis request, and report which layer succeeded without revealing credentials.

## Safety boundaries

- FreeLLMAPI aggregates provider free tiers; it is not a source of unlimited models without provider credentials.
- Follow upstream's stated personal-experimentation limitation unless the user has independently confirmed suitable terms for their use.
- Do not expose a single-user router directly to the public internet. Require its unified API key and prefer private networking or an authenticated gateway.
- Do not install software, create provider accounts, accept terms, or change billing without explicit user authorization.
- If no reachable router URL and valid unified key exist, stop after preparing code/configuration and state exactly what the user must supply.
