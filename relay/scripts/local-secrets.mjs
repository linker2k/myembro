// Creates only ignored local test values. Never overwrites existing configuration or prints keys.
import { writeFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
writeFileSync(new URL('../.dev.vars', import.meta.url),
  `APP_KEY=${randomBytes(32).toString('hex')}\nMCP_TOKEN=${randomBytes(32).toString('hex')}\n`, { flag: 'wx', mode: 0o600 });
console.log('Created ignored local .dev.vars (values not displayed).');
