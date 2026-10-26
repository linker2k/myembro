import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { WebStandardStreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js';
import { CallToolRequestSchema, ListToolsRequestSchema, type Tool } from '@modelcontextprotocol/sdk/types.js';
import { Broker } from './broker';

import { authorizationFailure, type RelaySecrets } from './auth';

interface Env extends RelaySecrets { RELAY: DurableObjectNamespace }

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const denied = await authorizationFailure(request, env);
    if (denied) return denied;
    return env.RELAY.get(env.RELAY.idFromName('single-relay')).fetch(request);
  }
};

/** One object and one active app per deployment. Cached definitions survive reconnects/restarts. */
export class Relay {
  private broker: Broker;
  constructor(private state: DurableObjectState, private env: Env) {
    this.broker = new Broker(tools => state.waitUntil(state.storage.put('tools', tools)));
    state.blockConcurrencyWhile(async () => { this.broker.tools = await state.storage.get<Tool[]>('tools') ?? []; });
  }
  async fetch(request: Request): Promise<Response> {
    const denied = await authorizationFailure(request, this.env);
    if (denied) return denied;
    if (new URL(request.url).pathname === '/app') {
      if (request.method !== 'GET' || request.headers.get('Upgrade')?.toLowerCase() !== 'websocket')
        return new Response('WebSocket upgrade required', { status: 426 });
      const pair = new WebSocketPair(); const [client, app] = Object.values(pair);
      app.accept(); this.broker.open(app);
      app.addEventListener('message', event => {
        if (typeof event.data === 'string') this.broker.receive(app, event.data);
        else { this.broker.close(app); app.close(1008, 'JSON text required'); }
      });
      app.addEventListener('close', () => this.broker.close(app));
      app.addEventListener('error', () => this.broker.close(app));
      return new Response(null, { status: 101, webSocket: client });
    }
    // Stateless official Streamable HTTP: each request gets a fresh SDK server/transport.
    // App connection, cached tools and concurrent-call routing live in the Durable Object.
    const server = new Server({ name: 'myembro-relay', version: '0.1.0' }, { capabilities: { tools: {} } });
    server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: this.broker.tools }));
    server.setRequestHandler(CallToolRequestSchema, async request => this.broker.call(request.params.name, request.params.arguments ?? {}));
    const transport = new WebStandardStreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
    await server.connect(transport);
    try { return await transport.handleRequest(request); }
    finally { await server.close(); }
  }
}
