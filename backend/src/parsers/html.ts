/**
 * Safe HTML utilities used by the Play Store parsers.
 * All entity decoding is bounded — no unbounded backtracking regexes.
 */

const NAMED_ENTITIES: Record<string, string> = {
  amp: '&',
  lt: '<',
  gt: '>',
  quot: '"',
  apos: "'",
  nbsp: ' ',
  copy: '©',
  reg: '®',
  hellip: '…',
  mdash: '—',
  ndash: '–',
  rsquo: '\u2019',
  lsquo: '\u2018',
  ldquo: '\u201C',
  rdquo: '\u201D',
  middot: '·',
  bull: '•',
  trade: '™',
  deg: '°',
  eacute: 'é',
  egrave: 'è',
  agrave: 'à',
  ccedil: 'ç',
  uuml: 'ü',
  ouml: 'ö',
  auml: 'ä',
  szlig: 'ß',
  ntilde: 'ñ',
};

/** Decode the HTML entities that actually occur in Play Store markup.
 *  Unknown entities are left as-is rather than guessed. */
export function decodeEntities(input: string): string {
  if (!input.includes('&')) return input;
  return input.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]{1,10});/g, (match, body: string) => {
    if (body.startsWith('#x') || body.startsWith('#X')) {
      const code = parseInt(body.slice(2), 16);
      return Number.isFinite(code) && code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : match;
    }
    if (body.startsWith('#')) {
      const code = parseInt(body.slice(1), 10);
      return Number.isFinite(code) && code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : match;
    }
    const named = NAMED_ENTITIES[body.toLowerCase()];
    return named ?? match;
  });
}

/** Strip tags, decode entities, collapse whitespace. */
export function stripTags(html: string): string {
  return decodeEntities(
    html
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/<\/(p|div|li|h[1-6])>/gi, '\n')
      .replace(/<[^>]{0,2000}>/g, ' ')
  )
    .replace(/[ \t\u00a0]+/g, ' ')
    .replace(/ *\n */g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/** Truncate for storage bounds, respecting an optional sentence-ish boundary. */
export function clampText(text: string | null, max: number): string | null {
  if (text == null) return null;
  if (text.length <= max) return text;
  const cut = text.slice(0, max);
  const sp = cut.lastIndexOf(' ');
  return (sp > max * 0.6 ? cut.slice(0, sp) : cut).trimEnd();
}

/** True only for plausible Android package ids (defensive validation). */
export function isPlausiblePackageId(id: string): boolean {
  return /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,}$/.test(id) && id.length <= 120;
}

/** Normalize a Google Play app URL to its canonical ?id= form; null if not an app link. */
export function canonicalAppUrl(href: string): { packageName: string; storeUrl: string } | null {
  let raw = decodeEntities(href.trim());
  if (raw.startsWith('/')) raw = 'https://play.google.com' + raw;
  const m = raw.match(/^https?:\/\/play\.google\.com\/store\/apps\/details\?[^#]*?id=([a-zA-Z0-9._]+)/);
  if (!m) return null;
  const packageName = m[1];
  if (!isPlausiblePackageId(packageName)) return null;
  return { packageName, storeUrl: `https://play.google.com/store/apps/details?id=${packageName}` };
}
