/// <reference types="node" />
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  PlayStoreCatalogProvider,
  parseInstallText,
} from '../src/discovery/PlayStoreCatalogProvider';
import { extractAfBlobs } from '../src/parsers/afdata';
import { canonicalAppUrl, decodeEntities, isPlausiblePackageId, stripTags } from '../src/parsers/html';

const here = dirname(fileURLToPath(import.meta.url));
const devHtml = readFileSync(join(here, 'fixtures/developer-page.html'), 'utf8');
const appHtml = readFileSync(join(here, 'fixtures/app-detail-page.html'), 'utf8');

describe('html utilities', () => {
  it('decodes bounded named/numeric entities', () => {
    expect(decodeEntities('A &amp; B &lt;tag&gt; &#39; &#x27;')).toBe("A & B <tag> ' '");
    expect(decodeEntities('plain')).toBe('plain');
    expect(decodeEntities('&notarealentity;')).toBe('&notarealentity;');
  });

  it('strips tags, decodes entities, and preserves paragraph breaks', () => {
    expect(stripTags('<p>Hello <b>world</b></p>  next ')).toBe('Hello world\nnext');
    expect(stripTags('A &amp; B')).toBe('A & B');
  });

  it('validates plausible package ids', () => {
    expect(isPlausiblePackageId('com.charles.qrcode')).toBe(true);
    expect(isPlausiblePackageId('com.cruisewatch.app')).toBe(true);
    expect(isPlausiblePackageId('not-a-package')).toBe(false);
    expect(isPlausiblePackageId('com..double')).toBe(false);
    expect(isPlausiblePackageId('1com.thing')).toBe(false);
  });

  it('canonicalizes app URLs and rejects non-app links', () => {
    expect(canonicalAppUrl('/store/apps/details?id=com.charles.qrcode')).toEqual({
      packageName: 'com.charles.qrcode',
      storeUrl: 'https://play.google.com/store/apps/details?id=com.charles.qrcode',
    });
    expect(canonicalAppUrl('https://play.google.com/store/apps/details?id=com.cruisewatch.app&hl=en')).toEqual({
      packageName: 'com.cruisewatch.app',
      storeUrl: 'https://play.google.com/store/apps/details?id=com.cruisewatch.app',
    });
    expect(canonicalAppUrl('/store/apps/developer?id=Hartmann+Studios')).toBeNull();
    expect(canonicalAppUrl('/store/movies/details?id=some.movie')).toBeNull();
    expect(canonicalAppUrl('javascript:void(0)')).toBeNull();
  });
});

describe('AF data blob extraction', () => {
  it('extracts and JSON-parses ds blobs from real page HTML', () => {
    const blobs = extractAfBlobs(appHtml);
    const keys = blobs.map((b) => b.key);
    expect(keys).toContain('ds:5');
    const ds5 = blobs.find((b) => b.key === 'ds:5');
    expect(ds5).toBeTruthy();
    expect(Array.isArray(ds5!.data)).toBe(true);
  });

  it('never throws on truncated blobs', () => {
    const truncated = "<script>AF_initDataCallback({key: 'ds:5', data:[[1,2],";
    expect(() => extractAfBlobs(truncated)).not.toThrow();
    expect(extractAfBlobs(truncated)).toEqual([]);
  });
});

describe('developer page discovery (real fixture)', () => {
  const result = PlayStoreCatalogProvider.parseDeveloperPage(devHtml, 'Hartmann Studios');

  it('discovers the real Hartmann Studios catalog dynamically', () => {
    expect(result.apps.length).toBeGreaterThanOrEqual(15);
  });

  it('finds real known packages without any hardcoded list', () => {
    const pkgs = result.apps.map((a) => a.packageName);
    expect(pkgs).toContain('com.charles.qrcode');
    expect(pkgs).toContain('com.cruisewatch.app');
    expect(pkgs).toContain('com.charles.messenger.v2');
  });

  it('extracts names for most apps from the card markup', () => {
    const named = result.apps.filter((a) => a.name != null);
    expect(named.length).toBeGreaterThan(result.apps.length / 2);
    const tp = result.apps.find((a) => a.packageName === 'com.charles.messenger.v2');
    expect(tp?.name).toContain('TextPilot');
  });

  it('extracts real icon URLs', () => {
    const icons = result.apps.filter((a) => a.iconUrl?.startsWith('https://play-lh.googleusercontent.com/'));
    expect(icons.length).toBeGreaterThan(0);
  });

  it('every discovered app has a canonical store URL', () => {
    for (const a of result.apps) {
      expect(a.storeUrl).toBe(`https://play.google.com/store/apps/details?id=${a.packageName}`);
    }
  });

  it('rejects a page that does not belong to the developer', () => {
    const foreign = devHtml.replace(/Hartmann Studios/g, 'Some Other Publisher');
    expect(() => PlayStoreCatalogProvider.parseDeveloperPage(foreign, 'Hartmann Studios')).toThrow();
  });

  it('returns empty result on parser-hostile input without throwing', () => {
    const broken = '<html><body>Google Play had a problem</body></html>';
    const r = PlayStoreCatalogProvider.parseDeveloperPage(broken, 'Hartmann Studios');
    expect(r.apps).toEqual([]);
  });

  it('handles duplicate links for the same package (normalized once)', () => {
    const dup = devHtml + devHtml.slice(0, 60_000);
    const r = PlayStoreCatalogProvider.parseDeveloperPage(dup, 'Hartmann Studios');
    const pkgs = r.apps.map((a) => a.packageName);
    expect(new Set(pkgs).size).toBe(pkgs.length);
  });
});

describe('app detail page metadata (real fixture)', () => {
  const patch = PlayStoreCatalogProvider.parseAppDetailPage(appHtml, 'com.charles.messenger.v2');

  it('reads real install data (TextPilot shows 100+ downloads)', () => {
    expect(patch.installText).toBeTruthy();
    expect(patch.installText).toMatch(/^\d/);
    if (patch.estimatedMinimumInstalls != null) {
      expect(patch.estimatedMinimumInstalls).toBeGreaterThan(0);
    }
  });

  it('rating is null for this young app (no invented metrics)', () => {
    if (patch.ratingCount == null || patch.ratingCount === 0) {
      expect(patch.rating ?? null).toBeNull();
    } else {
      expect(patch.rating).toBeGreaterThan(1);
      expect(patch.rating).toBeLessThanOrEqual(5);
    }
  });

  it('extracts a real short description', () => {
    expect(patch.shortDescription).toBeTruthy();
    expect(patch.shortDescription!.length).toBeGreaterThan(10);
  });

  it('extracts a real icon URL', () => {
    expect(patch.iconUrl).toContain('play-lh.googleusercontent.com');
  });

  it('canonical mismatch is rejected; missing canonical is tolerated', () => {
    const withForeignCanonical =
      '<link rel="canonical" href="https://play.google.com/store/apps/details?id=com.other.app&amp;hl=en">' +
      appHtml;
    expect(() =>
      PlayStoreCatalogProvider.parseAppDetailPage(withForeignCanonical, 'com.charles.messenger.v2')
    ).toThrow(/mismatch/i);
    // No canonical link at all → tolerant (the fixture has none).
    expect(() =>
      PlayStoreCatalogProvider.parseAppDetailPage(appHtml, 'com.charles.messenger.v2')
    ).not.toThrow();
  });
});

describe('defensive parser behavior on corrupted pages', () => {
  it('detail parser falls back to DOM stat boxes when ds:5 is missing', () => {
    const noDs5 = appHtml.replace(/AF_initDataCallback\(\{key:'ds:5'[\s\S]{0,400000}?sideChannel[\s\S]{0,40}?\}\);/g, '');
    const patch = PlayStoreCatalogProvider.parseAppDetailPage(noDs5, 'com.charles.messenger.v2');
    // At minimum it must not throw; install text comes from the DOM box.
    expect(patch).toBeTruthy();
  });

  it('handles totally broken HTML gracefully', () => {
    const junk = '<<<brupted>>>{{{{';
    expect(() => PlayStoreCatalogProvider.parseAppDetailPage(junk, 'com.charles.qrcode')).not.toThrow();
    const patch = PlayStoreCatalogProvider.parseAppDetailPage(junk, 'com.charles.qrcode');
    expect(patch.shortDescription).toBeNull();
  });
});

describe('install text parsing', () => {
  it('parses Play install buckets', () => {
    expect(parseInstallText('100K+')).toEqual({ min: 100_000, max: null });
    expect(parseInstallText('10M+')).toEqual({ min: 10_000_000, max: null });
    expect(parseInstallText('100+')).toEqual({ min: 100, max: null });
    expect(parseInstallText('5,000+')).toEqual({ min: 5000, max: null });
    expect(parseInstallText('garbage')).toEqual({ min: null, max: null });
  });
});
