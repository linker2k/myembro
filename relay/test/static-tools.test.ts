import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import { isSpecType } from '@modelcontextprotocol/server';
import { RELAY_TOOLS, RELAY_TOOL_NAMES } from '../src/tools';
import { Broker, DISCONNECTED, MAX_CALL_BYTES, TOO_LARGE, UPDATE_REQUIRED, clientName, type AppSocket } from '../src/broker';

const catalog = JSON.parse(readFileSync(new URL('../../docs/agent/tools.json', import.meta.url), 'utf8')).tools;
const byName = (name: string) => structuredClone(RELAY_TOOLS.find(t => t.name === name)!);
function connected(tools: any[]) {
  const sent: any[] = []; const socket: AppSocket = { send: raw => sent.push(JSON.parse(raw)), close: vi.fn() };
  const broker = new Broker(); broker.open(socket); broker.receive(socket, JSON.stringify({ type: 'hello', appVersion: 'test', tools }));
  return { broker, socket, sent };
}

describe('deployment tool contract drift gate', () => {
  it('is exactly docs/agent/relay-tools.json, the file the app generates from its hello', () => {
    const file = JSON.parse(readFileSync(new URL('../../docs/agent/relay-tools.json', import.meta.url), 'utf8')).tools;
    expect(RELAY_TOOLS).toEqual(file);
    for (const tool of RELAY_TOOLS) expect(isSpecType.Tool(tool), tool.name).toBe(true);
  });
  it('only narrows the strict docs/agent/tools.json schemas (optional arguments, app-owned transactionId)', () => {
    let shared = 0;
    for (const tool of RELAY_TOOLS) {
      const source = catalog.find((t: any) => t.name === tool.name);
      if (!source) { expect(['list_attachments', 'view_attachment', 'import_picture', 'list_blocks', 'set_block_stitch', 'set_block_order']).toContain(tool.name); continue; }
      const relay: any = tool.inputSchema, strict = source.inputSchema;
      const strictProps = Object.keys(strict.properties).filter(k => k !== 'transactionId');
      const extra = tool.name === 'add_text' ? ['xMm', 'yMm'] : [];
      expect(Object.keys(relay.properties).sort(), tool.name).toEqual([...strictProps, ...extra].sort());
      for (const name of relay.required) expect(strict.required, tool.name).toContain(name);
      expect(relay.required, tool.name).not.toContain('transactionId');
      expect(relay.additionalProperties, tool.name).toBe(strict.additionalProperties);
      shared++;
    }
    expect(shared).toBe(22); expect(RELAY_TOOLS).toHaveLength(28);
    expect(byName('render_preview').inputSchema.required).toEqual([]);
    expect(byName('add_text').inputSchema.required).toEqual(['text', 'heightMm']);
  });
  it('pins app-only attachment schemas to RelayPhotoTools.kt', () => {
    const source = readFileSync(new URL('../../app/src/main/kotlin/io/github/linker2k/myembro/relay/RelayPhotoTools.kt', import.meta.url), 'utf8');
    const local = JSON.parse(source.match(/val local = Json.parseToJsonElement\("""([\s\S]*?)"""\)/)![1]);
    for (const tool of local) expect(byName(tool.name).inputSchema, tool.name).toEqual(tool.inputSchema);
    // import_picture (RelayPicture.kt): strict schema with colors/title made optional by the relay projection.
    const picture = readFileSync(new URL('../../app/src/main/kotlin/io/github/linker2k/myembro/relay/RelayPicture.kt', import.meta.url), 'utf8');
    const strict = JSON.parse(picture.match(/val DEFINITION: JsonObject = Json.parseToJsonElement\("""([\s\S]*?)"""\)/)![1]).inputSchema;
    const relay: any = byName('import_picture').inputSchema;
    expect(Object.keys(relay.properties).sort()).toEqual(Object.keys(strict.properties).sort());
    expect(relay.required).toEqual(['format', 'data']);
    expect(relay.properties.colors).toEqual({ ...strict.properties.colors, default: 4 });
    expect(byName('import_picture').description).toMatch(/SVG is preferred[\s\S]*must be small/);
  });
  it('pins block schemas to the app and keeps the patch optional and closed', () => {
    const source = readFileSync(new URL('../../app/src/main/kotlin/io/github/linker2k/myembro/relay/RelayBlockTools.kt', import.meta.url), 'utf8');
    const local = JSON.parse(source.match(/val LOCAL = Json.parseToJsonElement\("""([\s\S]*?)"""\)/)![1]);
    for (const tool of local) expect(byName(tool.name)).toEqual(tool);
    expect(byName('set_block_stitch').inputSchema.required).toEqual(['objectId', 'regionIds', 'change']);
    expect(byName('list_blocks').annotations?.readOnlyHint).toBe(true);
  });
  it('never exposes separate-screen operations and preserves the approved name set', () => {
    expect(RELAY_TOOL_NAMES.size).toBe(28);
    for (const forbidden of ['save_project','save_pes','share_pes','send_usb','delete_designs','restore_backup','set_settings','undo','prepare_pes']) expect(RELAY_TOOL_NAMES.has(forbidden)).toBe(false);
    expect(RELAY_TOOL_NAMES.has('get_settings')).toBe(true); // read, not a settings mutation
  });
});

describe('calling AI name', () => {
  it('is sent only when known, as one trimmed line of at most 40 characters', () => {
    const { broker, socket, sent } = connected([byName('get_project')]);
    void broker.call('get_project', {}); void broker.call('get_project', {}, '  '); void broker.call('get_project', {}, 'claude-code');
    expect(sent.map(m => m.client)).toEqual([undefined, undefined, 'claude-code']);
    expect('client' in sent[0]).toBe(false);
    expect(clientName('a\u0000b\tc')).toBe('a b c'); expect(clientName(7)).toBeUndefined(); expect(clientName('y'.repeat(41))).toHaveLength(40);
    broker.close(socket);
  });
});

describe('import_picture message size', () => {
  const filler = (n: number) => 'x'.repeat(n);
  it('sends a call message of exactly MAX_CALL_BYTES and refuses one byte more with the share guidance', async () => {
    const { broker, socket, sent } = connected([byName('import_picture')]);
    // The data length whose whole call message is exactly the limit (the envelope is ASCII, the id a fixed-length UUID).
    const envelope = new TextEncoder().encode(JSON.stringify({ type: 'call', id: crypto.randomUUID(), tool: 'import_picture', args: { format: 'png', data: '' } })).length;
    const fit = MAX_CALL_BYTES - envelope;
    void broker.call('import_picture', { format: 'png', data: filler(fit - 1) });
    void broker.call('import_picture', { format: 'png', data: filler(fit) });
    expect(sent).toHaveLength(2);
    expect(new TextEncoder().encode(JSON.stringify(sent[1])).length).toBe(MAX_CALL_BYTES);
    expect(await broker.call('import_picture', { format: 'png', data: filler(fit + 1) })).toEqual({ isError: true, content: [{ type: 'text', text: TOO_LARGE }] });
    expect(sent).toHaveLength(2); expect(TOO_LARGE).toContain('공유');
    broker.close(socket);
  });
  it('the app limit (RelayPicture.MAX_BASE64_CHARS) fits inside the message limit with the longest title', () => {
    const picture = readFileSync(new URL('../../app/src/main/kotlin/io/github/linker2k/myembro/relay/RelayPicture.kt', import.meta.url), 'utf8');
    const max = Number(picture.match(/MAX_BASE64_CHARS = ([\d_]+)/)![1].replaceAll('_', ''));
    // The app trims the title to MAX_TITLE characters only after the relay sent it: allow ten times that, in 3-byte Hangul.
    const title = '가'.repeat(Number(picture.match(/MAX_TITLE = (\d+)/)![1]) * 10);
    const message = JSON.stringify({ type: 'call', id: crypto.randomUUID(), tool: 'import_picture', args: { format: 'jpeg', data: filler(max), colors: 6, title } });
    expect(new TextEncoder().encode(message).length).toBeLessThanOrEqual(MAX_CALL_BYTES);
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
  it('forwards render_preview without widthPx (the app draws 512) and still refuses bad widths', async () => {
    const { broker, socket, sent } = connected(RELAY_TOOLS);
    const pending = broker.call('render_preview', {}); expect(sent[0]).toMatchObject({ tool: 'render_preview', args: {} });
    broker.receive(socket, JSON.stringify({ type: 'result', id: sent[0].id, isError: false, content: [{ type: 'text', text: 'ok' }] }));
    expect((await pending).isError).toBe(false);
    for (const args of [{ widthPx: 255 }, { widthPx: 1025 }, { widthPx: 512.5 }, { widthPx: 512, path: 'x' }])
      expect((await broker.call('render_preview', args)).isError, JSON.stringify(args)).toBe(true);
    expect(sent).toHaveLength(1); broker.close(socket);
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

it('cleans Unicode controls/bidi/newlines and limits AI names without splitting surrogate pairs', () => {
  expect(clientName(' A\u0085\u202e\u2028\u2029\t B ')).toBe('A B');
  expect(clientName('\u200b\n\t')).toBeUndefined();
  const emoji = clientName('😀'.repeat(45))!;
  expect(Array.from(emoji)).toHaveLength(40); expect(emoji).toBe('😀'.repeat(40));
});
