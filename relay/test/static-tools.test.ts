import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import { isSpecType } from '@modelcontextprotocol/server';
import { RELAY_TOOLS, RELAY_TOOL_CATALOG, RELAY_TOOL_NAMES } from '../src/tools';
import { Broker, DISCONNECTED, UPDATE_REQUIRED, type AppSocket } from '../src/broker';

const catalog = JSON.parse(readFileSync(new URL('../../docs/agent/tools.json', import.meta.url), 'utf8')).tools;
const byName = (name: string) => structuredClone(RELAY_TOOLS.find(t => t.name === name)!);
function connected(tools: any[]) {
  const sent: any[] = []; const socket: AppSocket = { send: raw => sent.push(JSON.parse(raw)), close: vi.fn() };
  const broker = new Broker(); broker.open(socket); broker.receive(socket, JSON.stringify({ type: 'hello', appVersion: 'test', tools }));
  return { broker, socket, sent };
}

describe('deployment tool contract drift gate', () => {
  it('keeps every shared snapshot schema identical to docs/agent/tools.json', () => {
    let shared = 0;
    for (const snapshot of RELAY_TOOL_CATALOG) {
      const source = catalog.find((t: any) => t.name === snapshot.name);
      if (!source) { expect(['list_attachments', 'view_attachment']).toContain(snapshot.name); continue; }
      expect(snapshot.inputSchema, snapshot.name).toEqual(source.inputSchema); shared++;
    }
    expect(shared).toBe(22); expect(RELAY_TOOLS).toHaveLength(24);
  });
  it('advertises the exact existing app relay projection, not newly invented schemas', () => {
    for (const raw of RELAY_TOOL_CATALOG) {
      const expected: any = structuredClone(raw.inputSchema);
      if (raw.name !== 'add_generated_art') {
        delete expected.properties.transactionId;
        expected.required = expected.required.filter((n: string) => n !== 'transactionId');
      }
      expect(byName(raw.name).inputSchema, raw.name).toEqual(expected);
      expect(isSpecType.Tool(byName(raw.name)), raw.name).toBe(true);
    }
    expect(byName('add_generated_art').inputSchema.required).toContain('transactionId');
    expect(byName('transform_objects').inputSchema.required).not.toContain('transactionId');
  });
  it('pins app-only attachment schemas to RelayPhotoTools.kt', () => {
    const source = readFileSync(new URL('../../app/src/main/kotlin/io/github/linker2k/myembro/relay/RelayPhotoTools.kt', import.meta.url), 'utf8');
    const local = JSON.parse(source.match(/val local = Json.parseToJsonElement\("""([\s\S]*?)"""\)/)![1]);
    for (const tool of local) expect(byName(tool.name).inputSchema, tool.name).toEqual(tool.inputSchema);
  });
  it('never exposes separate-screen operations and preserves the approved name set', () => {
    expect(RELAY_TOOL_NAMES.size).toBe(24);
    for (const forbidden of ['save_project','save_pes','share_pes','send_usb','delete_designs','restore_backup','set_settings','undo','prepare_pes']) expect(RELAY_TOOL_NAMES.has(forbidden)).toBe(false);
    expect(RELAY_TOOL_NAMES.has('get_settings')).toBe(true); // read, not a settings mutation
  });
});

describe('app compatibility never controls advertised definitions', () => {
  it('starts with the full catalog before any hello, with disconnected error precedence', async () => {
    const broker = new Broker(); expect(broker.tools).toEqual(RELAY_TOOLS);
    expect(await broker.call('transform_objects', {})).toEqual({ isError: true, content: [{ type: 'text', text: DISCONNECTED }] });
  });
  it('old two-tool app gets update errors for new tools with zero app calls', async () => {
    const { broker, socket, sent } = connected([byName('get_project'), byName('render_preview')]);
    expect(broker.tools).toEqual(RELAY_TOOLS);
    expect(await broker.call('transform_objects', {})).toEqual({ isError: true, content: [{ type: 'text', text: UPDATE_REQUIRED }] });
    expect(sent).toEqual([]); broker.close(socket);
  });
  it('mismatched schemas fail locally, while metadata/key-order differences do not break compatible tools', async () => {
    const old = byName('render_preview'); old.inputSchema = { type: 'object', properties: {} };
    const { broker, socket, sent } = connected([old]);
    expect((await broker.call('render_preview', { widthPx: 512 })).content).toEqual([{ type: 'text', text: UPDATE_REQUIRED }]); expect(sent).toEqual([]);
    const same = byName('render_preview'); same.description = 'App-specific wording';
    same.inputSchema = Object.fromEntries(Object.entries(same.inputSchema).reverse()) as any;
    broker.receive(socket, JSON.stringify({ type: 'hello', appVersion: 'updated', tools: [same] }));
    const pending = broker.call('render_preview', { widthPx: 512 }); expect(sent).toHaveLength(1);
    broker.receive(socket, JSON.stringify({ type: 'result', id: sent[0].id, isError: false, content: [{ type: 'text', text: 'ok' }] }));
    expect((await pending).isError).toBe(false); broker.close(socket);
  });
  it('updated app receives edits unchanged and disconnect restores the existing error', async () => {
    const { broker, socket, sent } = connected(RELAY_TOOLS);
    const args = { objectIds: ['logo'], deltaXMm: 1, deltaYMm: 0, rotationDeg: 0, scaleFactor: 1 };
    const pending = broker.call('transform_objects', args); expect(sent[0].args).toEqual(args);
    broker.receive(socket, JSON.stringify({ type: 'result', id: sent[0].id, isError: false, content: [{ type: 'text', text: 'PASS' }] }));
    expect((await pending).isError).toBe(false); broker.close(socket);
    expect((await broker.call('transform_objects', args)).content).toEqual([{ type: 'text', text: DISCONNECTED }]);
    expect(broker.tools).toEqual(RELAY_TOOLS);
  });
});
