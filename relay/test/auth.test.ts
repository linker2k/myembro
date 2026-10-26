import { afterEach, describe, expect, it, vi } from 'vitest';
import { timingSafeEqual } from 'node:crypto';
import { secretMatches, authorizationFailure } from '../src/auth';

describe('constant-time credential comparison boundary', () => {
  afterEach(() => vi.unstubAllGlobals());
  function nativeCompare() {
    const original = globalThis.crypto;
    const compare = vi.fn((a: ArrayBuffer, b: ArrayBuffer) => timingSafeEqual(new Uint8Array(a), new Uint8Array(b)));
    vi.stubGlobal('crypto', { subtle: { digest: original.subtle.digest.bind(original.subtle), timingSafeEqual: compare } });
    return compare;
  }
  it('compares two 32-byte digests using the native constant-time primitive for every supplied length', async () => {
    const compare = nativeCompare();
    for (const supplied of ['', 's', 'secret-valuX', 'secret-value-extra', 'secret-value', '한글']) {
      expect(await secretMatches(supplied, 'secret-value')).toBe(supplied === 'secret-value');
      const [a, b] = compare.mock.calls.at(-1)!;
      expect(a.byteLength).toBe(32); expect(b.byteLength).toBe(32);
    }
    expect(compare).toHaveBeenCalledTimes(6);
  });
  it('fails closed for missing/empty configuration and rejects empty or malformed credentials', async () => {
    nativeCompare();
    expect(await secretMatches('', undefined)).toBe(false);
    expect(await secretMatches('', '')).toBe(false);
    for (const headers of [{}, { Authorization: 'Bearer' }, { Authorization: 'Basic secret-value' }]) {
      const denied = await authorizationFailure(new Request('http://relay/mcp', { headers }), { MCP_TOKEN: 'secret-value' });
      expect(denied?.status).toBe(401); expect(await denied?.text()).toBe('Unauthorized');
    }
  });
});
