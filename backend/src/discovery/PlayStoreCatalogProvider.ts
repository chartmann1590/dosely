/**
 * PlayStoreCatalogProvider — the ONLY component that talks to Google Play.
 *
 * Discovery:   developer page → app cards → package ids + names + icons
 * Metadata:    app detail page → ds:5 blob → installs/updated/description/icon
 *
 * Everything is defensive: every extraction has fallbacks, every optional
 * field can be null, and a single failed field never fails the catalog.
 */
import type { CatalogApp } from '../types';
import {
  extractAfBlobs,
  findImageUrls,
  findInstallShapedArrays,
  findStrings,
  findTimestampPairs,
  isStringArray,
} from '../parsers/afdata';
import { canonicalAppUrl, clampText, decodeEntities, isPlausiblePackageId, stripTags } from '../parsers/html';

const USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36';

export interface DiscoveryResult {
  apps: DiscoveredApp[];
  rejectedPackageIds: string[];
  rawHtmlLength: number;
}

export interface DiscoveredApp {
  packageName: string;
  name: string | null;
  iconUrl: string | null;
  storeUrl: string;
}

export interface MetadataPatch {
  rating: number | null;
  ratingCount: number | null;
  installText: string | null;
  estimatedMinimumInstalls: number | null;
  estimatedMaximumInstalls: number | null;
  category: string | null;
  priceText: string | null;
  isFree: boolean | null;
  version: string | null;
  updatedIso: string | null;
  contentRating: string | null;
  iconUrl: string | null;
  shortDescription: string | null;
  fullDescription: string | null;
}

const ISO_DATE = /\b(\d{4}-\d{2}-\d{2})\b/;
/** The content-rating icon embedded in ds:5 is IDENTICAL for every app —
 *  never use it as an app icon. */
const RATINGS_ICON_URL =
  'https://play-lh.googleusercontent.com/OBVqgRK7eerY0GPfK8AOzitu5oE9ecC6kG4kURTCb1K41gpqVsN0WjmJwJh-wX8vILzpcc1kYHt56aLN2g';
const MONTHS: Record<string, number> = {
  jan: 0, feb: 1, mar: 2, apr: 3, may: 4, jun: 5,
  jul: 6, aug: 7, sep: 8, oct: 9, nov: 10, dec: 11,
};

export class PlayStoreCatalogProvider {
  private readonly developerId: string;
  private readonly developerPageUrl: string;
  // Bound copy: destructured/bare `fetch` throws "Illegal invocation" in
  // Workers because global fetch requires the global object as `this`.
  private readonly fetchImpl: typeof fetch;

  constructor(developerId: string, developerPageUrl: string, fetchImpl: typeof fetch = fetch) {
    this.developerId = developerId;
    this.developerPageUrl = developerPageUrl;
    this.fetchImpl = fetchImpl.bind(globalThis);
  }

  /** Fetch the developer page and extract the app list. */
  async discoverApps(): Promise<DiscoveryResult> {
    const res = await this.fetchImpl(this.developerPageUrl + '&hl=en&gl=US', {
      headers: {
        'user-agent': USER_AGENT,
        'accept-language': 'en-US,en;q=0.9',
        accept: 'text/html',
      },
      redirect: 'follow',
    });
    if (!res.ok) throw new Error(`developer page HTTP ${res.status}`);
    const html = await res.text();
    return parseDeveloperPage(html, this.developerId);
  }

  /** Fetch one app's detail page and normalize its metadata. */
  async fetchAppMetadata(packageName: string): Promise<MetadataPatch> {
    const url = `https://play.google.com/store/apps/details?id=${encodeURIComponent(packageName)}&hl=en&gl=US`;
    const res = await this.fetchImpl(url, {
      headers: {
        'user-agent': USER_AGENT,
        'accept-language': 'en-US,en;q=0.9',
        accept: 'text/html',
      },
      redirect: 'follow',
    });
    if (res.status === 404) return emptyPatch();
    if (!res.ok) throw new Error(`details page HTTP ${res.status}`);
    return parseAppDetailPage(await res.text(), packageName);
  }

  static parseDeveloperPage(html: string, expectedDeveloper: string): DiscoveryResult {
    return parseDeveloperPage(html, expectedDeveloper);
  }

  static parseAppDetailPage(html: string, packageName: string): MetadataPatch {
    return parseAppDetailPage(html, packageName);
  }
}

// ---------------------------------------------------------------------------
// Developer page parsing
// ---------------------------------------------------------------------------

export function parseDeveloperPage(html: string, expectedDeveloper: string): DiscoveryResult {
  const rejected: string[] = [];
  const byPackage = new Map<string, DiscoveredApp>();

  // 1) Collect every canonical app link on the page.
  const linkRe = /href="(\/store\/apps\/details\?[^"]*|https:\/\/play\.google\.com\/store\/apps\/details\?[^"]*)"/g;
  let m: RegExpExecArray | null;
  while ((m = linkRe.exec(html)) !== null) {
    const canonical = canonicalAppUrl(decodeEntities(m[1]));
    if (!canonical) {
      const maybeId = m[1].match(/id=([a-zA-Z0-9._]+)/);
      if (maybeId) rejected.push(maybeId[1]);
      continue;
    }
    byPackage.set(canonical.packageName, {
      packageName: canonical.packageName,
      name: null,
      iconUrl: null,
      storeUrl: canonical.storeUrl,
    });
  }

  if (byPackage.size === 0) {
    return { apps: [], rejectedPackageIds: rejected, rawHtmlLength: html.length };
  }

  // 2) Extract names and icons from the card markup near each link.
  //    Cards look like:  <a href="/store/apps/details?id=X" …>
  //      <img … src="https://play-lh…=w240-h480…" …/>
  //      <span class="DdYX5">App Name</span><span class="wMUdtb">Developer</span> …
  const iconRe = /src="(https:\/\/play-lh\.googleusercontent\.com\/[^"]+)"/g;
  const nameRe = /<span class="DdYX5[^"]*">([\s\S]{0,200}?)<\/span>/g;
  const devRe = /<span class="wMUdtb[^"]*">([\s\S]{0,120}?)<\/span>/g;
  const anchors: { start: number; end: number; pkg: string }[] = [];
  const anchorRe = /href="\/store\/apps\/details\?id=([a-zA-Z0-9._]+)"/g;
  const seenAnchorPkgs = new Set<string>();
  while ((m = anchorRe.exec(html)) !== null) {
    if (seenAnchorPkgs.has(m[1])) continue; // normalize duplicate links
    seenAnchorPkgs.add(m[1]);
    anchors.push({ start: m.index, end: m.index + m[0].length, pkg: m[1] });
  }

  for (const a of anchors) {
    const app = byPackage.get(a.pkg);
    if (!app || (app.name && app.iconUrl)) continue;
    const window = html.slice(a.start, a.start + 4000);
    if (!app.iconUrl) {
      iconRe.lastIndex = 0;
      const img = iconRe.exec(window);
      if (img) app.iconUrl = stripIconParams(img[1]);
    }
    if (!app.name) {
      nameRe.lastIndex = 0;
      const t = nameRe.exec(window);
      if (t) {
        const name = stripTags(t[1]);
        if (name) app.name = name;
      }
    }
  }

  // 3) Defensive cross-check: every listed app must name the expected developer
  //    somewhere on the page (the page IS the developer page), and each anchor's
  //    package id must be plausible. Invalid ids go to rejected.
  for (const pkg of [...byPackage.keys()]) {
    if (!isPlausiblePackageId(pkg)) {
      byPackage.delete(pkg);
      rejected.push(pkg);
    }
  }

  // 4) Developer name validation on visible page text. Google's pages open
  //    with a huge <head> block, so scan headless content plus the page tail.
  const lowerExpected = expectedDeveloper.toLowerCase();
  const bodyStart = html.indexOf('</head>');
  const bodyStartIdx = bodyStart === -1 ? 0 : bodyStart;
  const visible =
    stripTags(html.slice(bodyStartIdx, bodyStartIdx + 400_000)) +
    ' ' +
    stripTags(html.slice(Math.max(0, html.length - 100_000)));
  const devOk = visible.toLowerCase().includes(lowerExpected);
  if (!devOk && byPackage.size > 0) {
    // Do not invent apps from an unrelated page.
    throw new Error(`developer "${expectedDeveloper}" not found on page`);
  }

  // 5) Reject cards that explicitly name a DIFFERENT developer (per-card check —
  //    a single bad card must not take down the rest of the catalog).
  const apps: DiscoveredApp[] = [];
  for (const a of anchors) {
    const app = byPackage.get(a.pkg);
    if (!app) continue;
    const window = html.slice(a.start, a.start + 4000);
    devRe.lastIndex = 0;
    const dev = devRe.exec(window);
    if (dev) {
      const devName = stripTags(dev[1]).toLowerCase();
      if (devName.length > 0 && !devName.includes(lowerExpected)) {
        byPackage.delete(a.pkg);
        rejected.push(a.pkg);
        continue;
      }
    }
    apps.push(app);
  }

  return { apps, rejectedPackageIds: [...new Set(rejected)], rawHtmlLength: html.length };
}

function stripIconParams(icon: string): string {
  // Keep base identity, request a reasonably sized square render.
  const eq = icon.indexOf('=');
  const base = eq === -1 ? icon : icon.slice(0, eq);
  return base + '=w256';
}

// ---------------------------------------------------------------------------
// App detail page parsing
// ---------------------------------------------------------------------------

export function parseAppDetailPage(html: string, packageName: string): MetadataPatch {
  const patch = emptyPatch();
  const canonicalHref = html.match(/<link rel="canonical" href="([^"]+)"/)?.[1];
  if (canonicalHref) {
    const canonical = canonicalAppUrl(decodeEntities(canonicalHref));
    if (canonical && canonical.packageName !== packageName) {
      throw new Error(`canonical package mismatch: ${canonical.packageName}`);
    }
  }

  const blobs = extractAfBlobs(html);
  const ds5 = blobs.find((b) => b.key === 'ds:5')?.data ?? null;

  if (ds5) {
    parseDs5(ds5, patch);
  } else {
    parseDomFallback(html, patch);
  }

  // Description: modern Play pages carry it inside ds:5 as the longest prose
  // string; legacy pages use a data-g-id="description" div or meta description.
  if (!patch.fullDescription && ds5) {
    const desc = extractDescriptionFromDs5(ds5);
    if (desc) patch.fullDescription = desc;
  }
  if (!patch.fullDescription || !patch.shortDescription) {
    parseDescriptions(html, patch);
  }
  if (!patch.shortDescription && patch.fullDescription) {
    patch.shortDescription = clampText(patch.fullDescription.split('\n')[0], 200);
  }
  // og:image is the app's canonical icon on detail pages - highest fidelity.
  const og = html.match(/<meta property="og:image" content="([^"]+)"/);
  if (og && og[1].startsWith("https://play-lh.googleusercontent.com/")) {
    patch.iconUrl = stripIconParams(og[1]);
  }
  if (!patch.iconUrl) {
    const icon = html.match(
      /<img[^>]+src="(https:\/\/play-lh\.googleusercontent\.com\/[^"]+)"[^>]+alt="Icon image"/
    );
    if (icon) patch.iconUrl = stripIconParams(icon[1]);
  }
  if (!patch.installText) {
    // DOM fallback for the Downloads stat box (works even without ds:5).
    const box = html.match(
      /<div class="wVqUob"[^>]*><div class="ClM7O">([^<]{1,40})<\/div><div class="g1rdde">Downloads<\/div>/
    );
    if (box) applyInstallText(patch, box[1].trim());
  }
  if (!patch.priceText && ds5 === null) {
    // DOM fallback: buy-box is marked data-is-free.
    const free = html.match(/data-is-free="(true|false)"/);
    if (free) {
      patch.isFree = free[1] === 'true';
      patch.priceText = patch.isFree ? 'Free' : null;
    }
  }
  return patch;
}

function parseDs5(data: unknown, patch: MetadataPatch): void {
  // Structural search — Google shifts these indices across redesigns.
  const installs = findInstallShapedArrays(data);
  if (installs.length > 0) {
    // Prefer the array whose text matches [digits][K/M]?+ form.
    const best =
      installs.find((i) => /^\d[\d,.]*[KMB]?\+$/.test(i[0])) ?? installs[0];
    patch.installText = best[0];
    patch.estimatedMinimumInstalls = best[1] >= 0 ? best[1] : null;
    patch.estimatedMaximumInstalls = best[2] != null && best[2] >= 0 ? best[2] : null;
  }

  // Icon: the ds:5 blob leads with content-RATING icons (identical across all
  // apps) before the app icon, so prefer the canonical og:image instead.
  const imgs = findImageUrls(data, 12);
  const ratingIcon = imgs.find((u) => u === RATINGS_ICON_URL);
  const ds5Icon = imgs.find((u) => u !== RATINGS_ICON_URL);
  if (ds5Icon) patch.iconUrl = stripIconParams(ds5Icon);
  else if (ratingIcon) patch.iconUrl = stripIconParams(ratingIcon);

  const times = findTimestampPairs(data);
  if (times.length > 0) {
    // "Updated on" is the most recent plausible timestamp in ds:5.
    const latest = times.map((t) => t[0]).sort((a, b) => b - a)[0];
    patch.updatedIso = new Date(latest * 1000).toISOString().slice(0, 10);
  }

  // Titles: ds:5 contains [ [ "<title>" ] ] near the root.
  const titleCandidates = findStrings(data, (s) => s.length >= 2 && s.length <= 100, 30);
  void titleCandidates;

  // Version: d[1][2] contains a leaf "x.y(.z)" string.
  const version = findStrings(data, (s) => /^\d+\.\d+(\.\d+)?$/.test(s) && s.length <= 15, 10);
  if (version.length > 0) patch.version = version[0];

  // Content rating: "Everyone", "Teen", "Mature 17+" …
  const cr = findStrings(
    data,
    (s) => /^(Everyone|Teen|Mature 17\+|Adults only 18\+|Rated for \d{1,2}\+|Unrated)$/i.test(s),
    5
  );
  if (cr.length > 0) patch.contentRating = cr[0];

  // Price: exactly "$X.YZ" or "Free"/"Install".
  const price = findStrings(data, (s) => /^(Free|Install)$|^[$€£]\d+(\.\d{2})?$/.test(s), 10);
  if (price.length > 0) {
    const p = price[0];
    patch.priceText = p === 'Install' ? 'Free' : p;
    patch.isFree = p === 'Free' || p === 'Install';
  }
}

/**
 * The full description lives inside ds:5 as the longest prose-like string
 * (contains spaces, is not a URL/base64/parameter blob).
 */
export function extractDescriptionFromDs5(data: unknown): string | null {
  const candidates = findStrings(
    data,
    (s) =>
      s.length >= 40 &&
      s.includes(' ') &&
      !s.startsWith('http') &&
      !s.startsWith('{') &&
      !s.startsWith('[') &&
      !/^[A-Za-z0-9+/=]{40,}$/.test(s) &&
      !/[<>]|\/[a-z]+\?/.test(s.slice(0, 20)),
    400
  );
  let best: string | null = null;
  for (const s of candidates) {
    if (best === null || s.length > best.length) best = s;
  }
  if (!best) return null;
  const text = stripTags(best);
  return text.length >= 40 ? text : null;
}

function parseDescriptions(html: string, patch: MetadataPatch): void {
  // Full description lives in a <div data-g-id="description"> block.
  const full = html.match(/<div data-g-id="description"[^>]*>([\s\S]{0,40000}?)<\/div>/);
  if (full) {
    const text = stripTags(full[1]);
    if (text) patch.fullDescription = text;
  }
  // Meta description is a strong short-description fallback.
  const meta = html.match(/<meta name="description" content="([^"]{5,500})"/);
  if (meta) {
    patch.shortDescription = decodeEntities(meta[1]).trim();
  }
  if (!patch.shortDescription && patch.fullDescription) {
    patch.shortDescription = clampText(patch.fullDescription.split('\n')[0], 200);
  }
}

function parseDomFallback(html: string, patch: MetadataPatch): void {
  parseDescriptions(html, patch);
  const box = html.match(
    /<div class="wVqUob"[^>]*><div class="ClM7O">([^<]{1,40})<\/div><div class="g1rdde">Downloads<\/div>/
  );
  if (box) applyInstallText(patch, box[1].trim());
}

function applyInstallText(patch: MetadataPatch, text: string): void {
  patch.installText = text;
  const num = text.match(/([\d,.]+)\s*([KMB]?)/i);
  if (num) {
    const value = parseFloat(num[1].replace(/,/g, ''));
    if (Number.isFinite(value)) {
      const unit = (num[2] || '').toUpperCase();
      const mult = unit === 'K' ? 1_000 : unit === 'M' ? 1_000_000 : unit === 'B' ? 1e9 : 1;
      patch.estimatedMinimumInstalls = Math.round(value * mult);
      patch.estimatedMaximumInstalls = null;
    }
  }
}

function emptyPatch(): MetadataPatch {
  return {
    rating: null,
    ratingCount: null,
    installText: null,
    estimatedMinimumInstalls: null,
    estimatedMaximumInstalls: null,
    category: null,
    priceText: null,
    isFree: null,
    version: null,
    updatedIso: null,
    contentRating: null,
    iconUrl: null,
    shortDescription: null,
    fullDescription: null,
  };
}

// ---------------------------------------------------------------------------
// Shared helpers used by tests (kept close to the parser)
// ---------------------------------------------------------------------------

/** Parse a Play install bucket text into (min,max) estimates. */
export function parseInstallText(text: string): { min: number | null; max: number | null } {
  const t = text.trim();
  const m = t.match(/^([\d,.]+)\s*([KMB]?)\+?$/i);
  if (!m) return { min: null, max: null };
  const value = parseFloat(m[1].replace(/,/g, ''));
  if (!Number.isFinite(value)) return { min: null, max: null };
  const unit = (m[2] || '').toUpperCase();
  const mult = unit === 'K' ? 1_000 : unit === 'M' ? 1_000_000 : unit === 'B' ? 1e9 : 1;
  return { min: Math.round(value * mult), max: null };
}

export { emptyPatch, applyInstallText };
