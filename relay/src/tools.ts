import type { Tool } from '@modelcontextprotocol/server';
import definitions from './tool-definitions.json';

/** Frozen deployment snapshot; never read docs/ or app hello at runtime. */
export const RELAY_TOOL_CATALOG = definitions;
export const RELAY_TOOLS: Tool[] = definitions.map(source => {
  const tool = structuredClone(source);
  // RelayProtocol.definitions: project edit turns own their transactionId in the app.
  // RelayPhotoTools.add_generated_art explicitly retains the catalog's transactionId.
  if (tool.name !== 'add_generated_art' && 'transactionId' in tool.inputSchema.properties) {
    delete (tool.inputSchema.properties as Record<string, unknown>).transactionId;
    tool.inputSchema.required = tool.inputSchema.required.filter(name => name !== 'transactionId');
  }
  const readOnly = tool.annotations.readOnly;
  return {
    name: tool.name,
    description: tool.description + (tool.name === 'remove_objects' ? ' The phone asks the user to confirm removal (up to 90 seconds); tell the user to check their phone.' : ''),
    inputSchema: tool.inputSchema as unknown as Tool['inputSchema'],
    annotations: { readOnlyHint: readOnly, ...(!readOnly ? { destructiveHint: tool.annotations.needsUserConfirmation === true } : {}), idempotentHint: readOnly, openWorldHint: false }
  };
});
export const RELAY_TOOL_NAMES = new Set(RELAY_TOOLS.map(tool => tool.name));
export function sameSchema(a: unknown, b: unknown): boolean {
  return JSON.stringify(canonical(a)) === JSON.stringify(canonical(b));
}
function canonical(value: any): any {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  return value;
}
