import { describe, expect, it } from 'vitest';
import type { Catalog, CatalogApp, Env } from '../src/types';
import { refreshCatalog } from '../src/refresh';
import { Store } from '../src/store';

// ------------------------------------------------------------ helpers
class MockKv {
  private m = new Map<string, string>();
  async get(key: string, type?: string): Promise<unknown> {
    const v = this.m.get(key);
    if (v == null) return null;
    if (type === 'json') return JSON.parse(v);
    return v;
  }
  async put(key: string, value: string): Promise<void> {
    this.m.set(key, value);
  }
  async list(): Promise<{ keys: { name: string }[] }> {
    return { keys: [...this.m.keys()].map((name) => ({ name })) };
  }
}

function mkApp(pkg: string, overrides: Partial<CatalogApp> = {}): CatalogApp {
  return {
    packageName: pkg,
    name: pkg,
    iconUrl: 'https://play-lh.example/i=w256',
    storeUrl: `https://play.google.com/store/apps/details?id=${pkg}`,
    shortDescription: 'd',
    fullDescription: null,
    rating: null,
    ratingCount: null,
    installText: '100+',
    estimatedMinimumInstalls: 100,
    estimatedMaximumInstalls: null,
    category: null,
    priceText: 'Free',
    isFree: true,
    developer: 'Hartmann Studios',
    developerPageUrl: null,
    version: null,
    updatedIso: null,
    contentRating: null,
    firstDiscoveredAt: null,
    lastSeenAt: new Date().toISOString(),
    lastMetadataRefresh: new Date().toISOString(),
    enabledForPromotion: true,
    promotionMultiplier: 1,
    ...overrides,
  };
}

function mkEnv(): Env {
  const kv = new MockKv();
  return {
    CATALOG_KV: kv as never,
    ANALYTICS_KV: kv as never,
    ADMIN_TOKEN: 't',
    DEVELOPER_ID: 'Hartmann Studios',
    DEVELOPER_PAGE_URL: 'https://play.google.com/store/apps/developer?id=Hartmann+Studios',
    ENVIRONMENT: 'test',
  };
}

const okResponse = (body: string) => new Response(body, { status: 200 });

/** Build a developer page containing the given packages (real card shape). */
function devPage(packages: string[], appNamePrefix = 'App'): string {
  const cards = packages
    .map(
      (p, i) =>
        `<a class="Si6A0c Gy4nib" href="/store/apps/details?id=${p}">` +
        `<img src="https://play-lh.googleusercontent.com/abc${i}=w240-h480" alt="Thumbnail image" />` +
        `<div class="cXFu1"><div class="ubGTjb"><span class="DdYX5">${appNamePrefix} ${i}</span></div>` +
        `<div class="ubGTjb"><span class="wMUdtb">Hartmann Studios</span></div></div></a>`
    )
    .join('\n');
  return `<html><head><title>Hartmann Studios on Google Play</title></head><body>${cards}</body></html>`;
}

function detailPage(pkg: string, installs: string): string {
  return (
    `<html><head><link rel="canonical" href="https://play.google.com/store/apps/details?id=${pkg}&amp;hl=en">` +
    `<meta name="description" content="A real short description for ${pkg}"></head><body>` +
    `<div class="wVqUob"><div class="ClM7O">${installs}</div><div class="g1rdde">Downloads</div></div>` +
    `</body></html>`
  );
}

function fakeFetch(devPageHtml: string, details: Map<string, string>) {
  return (async (url: string | URL | Request): Promise<Response> => {
    const u = String(url);
    if (u.includes('/store/apps/developer?')) return okResponse(devPageHtml);
    const id = u.match(/details\?id=([^&]+)/)?.[1] ?? '';
    const page = details.get(id);
    if (page == null) return new Response('not found', { status: 404 });
    return okResponse(page);
  }) as unknown as typeof fetch;
}

async function seedCatalog(env: Env, packages: string[]): Promise<void> {
  const store = new Store(env);
  const nowIso = new Date().toISOString();
  await store.writeRefreshedCatalog({
    version: 1,
    generatedAt: nowIso,
    source: 'seed',
    apps: packages.map((p) => mkApp(p, { firstDiscoveredAt: nowIso })),
  });
}

describe('refresh pipeline safety', () => {
  it('writes a full catalog when discovery is healthy', async () => {
    const env = mkEnv();
    const details = new Map<string, string>();
    const pkgs = ['com.a.app', 'com.b.app', 'com.c.app'];
    for (const p of pkgs) details.set(p, detailPage(p, '10K+'));
    const envWithFetch = { ...env, DEVELOPER_PAGE_URL: 'https://play.google.com/store/apps/developer?id=Hartmann+Studios' };
    // inject fake fetch by monkey-patching global
    const origFetch = globalThis.fetch;
    globalThis.fetch = fakeFetch(devPage(pkgs), details) as typeof fetch;
    try {
      const outcome = await refreshCatalog(envWithFetch);
      expect(outcome.status).toBe('ok');
      expect(outcome.apps).toBe(3);
      const store = new Store(envWithFetch);
      const cat = await store.getCurrentCatalog();
      expect(cat?.apps.map((a) => a.packageName).sort()).toEqual(['com.a.app', 'com.b.app', 'com.c.app']);
      expect(cat?.apps.every((a) => a.installText === '10K+')).toBe(true);
      expect(cat?.apps.every((a) => a.shortDescription != null)).toBe(true);
      const lkg = await store.getLastKnownGoodCatalog();
      expect(lkg?.apps.length).toBe(3);
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('REJECTS a refresh that discovers 0 apps and keeps last-known-good', async () => {
    const env = mkEnv();
    await seedCatalog(env, ['com.a.app', 'com.b.app', 'com.c.app']);
    const origFetch = globalThis.fetch;
    globalThis.fetch = fakeFetch('<html><body>maintenance page, no apps</body></html>', new Map()) as typeof fetch;
    try {
      const outcome = await refreshCatalog(env);
      expect(outcome.status).toBe('rejected');
      expect(outcome.reason).toContain('0 apps');
      const store = new Store(env);
      const cat = await store.getCurrentCatalog();
      expect(cat?.apps.length).toBe(3); // untouched
      const meta = await store.getRefreshMeta();
      expect(meta?.lastResult).toBe('rejected');
      // The seed's healthy refresh timestamp is PRESERVED (never clobbered by a rejected run).
      expect(meta?.lastSuccessfulRefresh).toBeTruthy();
      expect(meta?.rejectReason).toContain('0 apps');
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('REJECTS a suspicious drop (20 apps → 1) and keeps last-known-good', async () => {
    const env = mkEnv();
    const big = Array.from({ length: 20 }, (_, i) => `com.app${i}.pkg`);
    await seedCatalog(env, big);
    const origFetch = globalThis.fetch;
    globalThis.fetch = fakeFetch(devPage(['com.survivor.app']), new Map()) as typeof fetch;
    try {
      const outcome = await refreshCatalog(env);
      expect(outcome.status).toBe('rejected');
      expect(outcome.reason).toContain('suspicious');
      const store = new Store(env);
      const cat = await store.getCurrentCatalog();
      expect(cat?.apps.length).toBe(20);
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('ACCEPTS legitimate growth (3 → 5 apps) and preserves firstDiscoveredAt', async () => {
    const env = mkEnv();
    const before = await (async () => {
      await seedCatalog(env, ['com.a.app', 'com.b.app', 'com.c.app']);
      return (await new Store(env).getCurrentCatalog())!.apps.find((a) => a.packageName === 'com.a.app')!
        .firstDiscoveredAt;
    })();
    const pkgs = ['com.a.app', 'com.b.app', 'com.c.app', 'com.d.app', 'com.e.app'];
    const details = new Map(pkgs.map((p) => [p, detailPage(p, '5+')]));
    const origFetch = globalThis.fetch;
    globalThis.fetch = fakeFetch(devPage(pkgs), details) as typeof fetch;
    try {
      const outcome = await refreshCatalog(env);
      expect(outcome.status).toBe('ok');
      expect(outcome.added.sort()).toEqual(['com.d.app', 'com.e.app']);
      const store = new Store(env);
      const cat = (await store.getCurrentCatalog())!;
      expect(cat.apps.length).toBe(5);
      const a = cat.apps.find((x) => x.packageName === 'com.a.app')!;
      expect(a.firstDiscoveredAt).toBe(before); // preserved across refreshes
      const d = cat.apps.find((x) => x.packageName === 'com.d.app')!;
      expect(d.firstDiscoveredAt).toBe(cat.generatedAt); // new app stamped now
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('removed apps disappear from the catalog after a clean refresh', async () => {
    const env = mkEnv();
    await seedCatalog(env, ['com.a.app', 'com.b.app', 'com.c.app']);
    const pkgs = ['com.a.app', 'com.b.app'];
    const details = new Map(pkgs.map((p) => [p, detailPage(p, '5+')]));
    const origFetch = globalThis.fetch;
    globalThis.fetch = fakeFetch(devPage(pkgs), details) as typeof fetch;
    try {
      const outcome = await refreshCatalog(env);
      expect(outcome.status).toBe('ok');
      expect(outcome.removed).toEqual(['com.c.app']);
      const cat = (await new Store(env).getCurrentCatalog())!;
      expect(cat.apps.map((a) => a.packageName)).toEqual(['com.a.app', 'com.b.app']);
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('operator flags survive refreshes (enabled=false, multiplier)', async () => {
    const env = mkEnv();
    await seedCatalog(env, ['com.a.app', 'com.b.app']);
    const store = new Store(env);
    const cat = (await store.getCurrentCatalog())!;
    cat.apps = cat.apps.map((a) =>
      a.packageName === 'com.a.app' ? { ...a, enabledForPromotion: false, promotionMultiplier: 3.5 } : a
    );
    await store.writeRefreshedCatalog(cat);

    const pkgs = ['com.a.app', 'com.b.app'];
    const details = new Map(pkgs.map((p) => [p, detailPage(p, '5+')]));
    const origFetch = globalThis.fetch;
    globalThis.fetch = fakeFetch(devPage(pkgs), details) as typeof fetch;
    try {
      await refreshCatalog(env);
      const after = (await store.getCurrentCatalog())!;
      const a = after.apps.find((x) => x.packageName === 'com.a.app')!;
      expect(a.enabledForPromotion).toBe(false);
      expect(a.promotionMultiplier).toBe(3.5);
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('metadata fetch failures never fail the catalog', async () => {
    const env = mkEnv();
    const pkgs = ['com.a.app', 'com.b.app', 'com.c.app'];
    const details = new Map<string, string>([['com.b.app', detailPage('com.b.app', '1K+')]]); // a & c will 500
    const failingFetch = (async (url: string | URL | Request): Promise<Response> => {
      const u = String(url);
      if (u.includes('/store/apps/developer?')) return okResponse(devPage(pkgs));
      const id = u.match(/details\?id=([^&]+)/)?.[1] ?? '';
      const page = details.get(id);
      if (page == null) return new Response('boom', { status: 500 });
      return okResponse(page);
    }) as unknown as typeof fetch;
    const origFetch = globalThis.fetch;
    globalThis.fetch = failingFetch;
    try {
      const outcome = await refreshCatalog(env);
      expect(outcome.status).toBe('ok');
      expect(outcome.metadataFailures).toBe(2);
      const cat = (await new Store(env).getCurrentCatalog())!;
      expect(cat.apps.length).toBe(3);
      expect(cat.apps.find((a) => a.packageName === 'com.b.app')?.installText).toBe('1K+');
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('rejects pages that do not name the developer', async () => {
    const env = mkEnv();
    const foreign = devPage(['com.a.app']).replaceAll('Hartmann Studios', 'Mega Corp');
    const origFetch = globalThis.fetch;
    globalThis.fetch = fakeFetch(foreign, new Map()) as typeof fetch;
    try {
      const outcome = await refreshCatalog(env);
      expect(outcome.status).toBe('error'); // thrown → treated as refresh error
      const meta = await new Store(env).getRefreshMeta();
      expect(meta?.lastResult === 'rejected' || meta?.lastResult === 'error').toBe(true);
    } finally {
      globalThis.fetch = origFetch;
    }
  });
});

describe('serve fallback chain', () => {
  it('falls back to LKG when current is wiped but LKG exists', async () => {
    const env = mkEnv();
    const store = new Store(env);
    await seedCatalog(env, ['com.a.app', 'com.b.app']);
    // Simulate corruption of current only.
    await env.CATALOG_KV.put('catalog:current', '{"version":1,"apps":[]}');
    const serve = await store.getServeCatalog();
    expect(serve.degraded).toBe(true);
    expect(serve.catalog?.apps.length).toBe(2);
  });

  it('returns null when nothing exists', async () => {
    const env = mkEnv();
    const serve = await new Store(env).getServeCatalog();
    expect(serve.catalog).toBeNull();
    expect(serve.degraded).toBe(false);
  });
});
