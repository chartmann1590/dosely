/**
 * Extraction of Google Play's AF_initDataCallback data blobs.
 *
 * Play pages embed their data in `<script>` blocks shaped like:
 *   AF_initDataCallback({key: 'ds:5', hash: '…', data:[…], sideChannel:…});
 *
 * The payload is JSON with rare JS literals (\'
) that standard JSON.parse
 * rejects; we sanitize those two known cases only, then parse strictly.
 */

export interface AfBlob {
  key: string;
  data: unknown;
}

export function extractAfBlobs(html: string): AfBlob[] {
  const out: AfBlob[] = [];
  const marker = 'AF_initDataCallback(';
  let idx = html.indexOf(marker);
  let guard = 0;
  while (idx !== -1 && guard < 64) {
    guard++;
    const objStart = html.indexOf('{', idx + marker.length);
    if (objStart === -1) break;
    const keyMatch = html.slice(objStart, objStart + 400).match(/key:\s*'([^']+)'/);
    const dataStart = html.indexOf('data:', objStart);
    if (!keyMatch || dataStart === -1) {
      idx = html.indexOf(marker, idx + marker.length);
      continue;
    }
    const arrayStart = html.indexOf('[', dataStart);
    if (arrayStart === -1) {
      idx = html.indexOf(marker, idx + marker.length);
      continue;
    }
    const end = findMatchingBracket(html, arrayStart);
    if (end !== -1) {
      const raw = html.slice(arrayStart, end + 1);
      try {
        out.push({ key: keyMatch[1], data: JSON.parse(sanitizeJsLiterals(raw)) });
      } catch {
        // Skip malformed blob — never fail the whole page parse.
      }
    }
    idx = html.indexOf(marker, idx + marker.length);
  }
  return out;
}

/** Find the index of the ']' that closes the array opened at `open`,
 *  respecting string literals and nesting. Bounded scan. */
function findMatchingBracket(s: string, open: number): number {
  let depth = 0;
  let inStr: string | null = null;
  for (let i = open; i < s.length && i - open < 4_000_000; i++) {
    const c = s[i];
    if (inStr) {
      if (c === '\\') i++;
      else if (c === inStr) inStr = null;
      continue;
    }
    if (c === '"' || c === "'") inStr = c;
    else if (c === '[') depth++;
    else if (c === ']') {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

/** Play data blobs escape apostrophes as \' inside JSON strings and contain
 *  rare "\n" JS literals. JSON.parse chokes on these two cases only. */
function sanitizeJsLiterals(raw: string): string {
  return raw.replace(/\\'/g, "'").replace(/"\\n"/g, '""');
}

// ---------------------------------------------------------------------------
// Structural search helpers (indices shift across Google redesigns)
// ---------------------------------------------------------------------------

export type Node = unknown;

/** Bounded depth-first search yielding every array/object with a visitor path. */
export function walk(root: Node, visit: (node: Node, path: string) => void, maxNodes = 200_000): void {
  let count = 0;
  const rec = (node: Node, path: string): void => {
    if (count++ > maxNodes) return;
    visit(node, path);
    if (Array.isArray(node)) {
      for (let i = 0; i < node.length && count <= maxNodes; i++) rec(node[i], `${path}[${i}]`);
    } else if (node && typeof node === 'object') {
      for (const [k, v] of Object.entries(node as Record<string, unknown>)) {
        if (count > maxNodes) return;
        rec(v, `${path}.${k}`);
      }
    }
  };
  rec(root, '$');
}

export function isStringArray(v: unknown): v is string[] {
  return Array.isArray(v) && v.length > 0 && v.every((x) => typeof x === 'string');
}

/** First string matching `pred` anywhere in the tree (nearest to root first). */
export function findString(root: Node, pred: (s: string) => boolean): string | null {
  let found: string | null = null;
  walk(root, (node) => {
    if (found !== null) return;
    if (typeof node === 'string' && pred(node)) found = node;
  });
  return found;
}

/** All strings matching `pred` (deduplicated, document order). */
export function findStrings(root: Node, pred: (s: string) => boolean, limit = 200): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  walk(root, (node) => {
    if (out.length >= limit) return;
    if (typeof node === 'string' && pred(node) && !seen.has(node)) {
      seen.add(node);
      out.push(node);
    }
  });
  return out;
}

/** Find arrays shaped like `["100+", 100, 109, "100+"]` (Play install buckets)
 *  or generally [string, number, number|null, string]. */
export function findInstallShapedArrays(root: Node): [string, number, number | null, string][] {
  const out: [string, number, number | null, string][] = [];
  walk(root, (node) => {
    if (
      Array.isArray(node) &&
      node.length >= 4 &&
      typeof node[0] === 'string' &&
      typeof node[1] === 'number' &&
      (typeof node[2] === 'number' || node[2] === null) &&
      typeof node[3] === 'string'
    ) {
      out.push([node[0], node[1], node[2] as number | null, node[3]]);
    }
  });
  return out;
}

/** Find [timestampSeconds, nanos] arrays (Play "updated on" fields). */
export function findTimestampPairs(root: Node): number[][] {
  const out: number[][] = [];
  walk(root, (node) => {
    if (
      Array.isArray(node) &&
      node.length === 2 &&
      typeof node[0] === 'number' &&
      node[0] > 1_000_000_000 &&
      node[0] < 4_000_000_000 &&
      typeof node[1] === 'number' &&
      node[1] >= 0
    ) {
      out.push([node[0], node[1]]);
    }
  });
  return out;
}

/** Find play-lh.googleusercontent.com image URLs (icons). */
export function findImageUrls(root: Node, limit = 40): string[] {
  return findStrings(root, (s) => s.startsWith('https://play-lh.googleusercontent.com/'), limit);
}
