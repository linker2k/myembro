# Relay operations

## Redeploy after every secret change

**After adding or changing APP_KEY or MCP_TOKEN, always rerun `wrangler deploy`.**
Do not treat a successful `wrangler secret put` as completion of the update.
Run the deployment from `relay/`, targeting the same Worker environment as the
secret update:

```sh
npx wrangler deploy
```

Observed in the manager's production deployment on 2026-10-26: immediately after
setting the secret, the Durable Object retained its old environment without
MCP_TOKEN, so correct Bearer authentication returned 401. Rerunning deployment
restarted the object and resolved the failure. This is the operational rule for
this relay based on that incident, rather than a claim about all Cloudflare
secret-update behavior.

After redeployment, reconnect the app WebSocket and verify MCP initialize and
tools/list with the configured Authorization header. Do not print credentials
or include them in URLs while checking the connection.
