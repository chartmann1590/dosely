import { describe, expect, it } from 'vitest';
import type { Catalog, CatalogApp, Env } from '../src/types';
import { applyPatch, type MetadataPatch } from '../src/refresh';
import { Store, emptyAggregate, sanitizeConfig, emptyRefreshMeta } from '../src/store';
import { validateEvent } from '../src/validation';

// ------------------------------------------------------------ mock KV
class MockKv {
  private m = new Map<string, string>();
  async get(key: string, type?: string): Promise<unknown> {
    const v = this.m.get(key);
    if (v == null) return null;
    if (type === 'json') return JSON.parse(v);
    return v;
  }
  async put(key: string, value: string, _opts?: unknown): Promise<void> {
    this.m.set(key, value);
  }
  async list(opts?: { prefix?: string }): Promise<{ keys: { name: string }[] }> {
    const prefix = opts?.prefix ?? '';
    return { keys: [...this.m.keys()].filter((k) => k.startsWith(prefix)).map((name) => ({ name })) };
  }
}

function mkEnv(): { env: Env; kv: MockKv } {
  const kv = new MockKv();
  return {
    kv,
    env: {
      CATALOG_KV: kv as never,
      ANALYTICS_KV: kv as never,
      ADMIN_TOKEN: 't',
      DEVELOPER_ID: 'Hartmann Studios',
      DEVELOPER_PAGE_URL: 'https://play.google.com/store/apps/developer?id=Hartmann+Studios',
      ENVIRONMENT: 'test',
    },
  };
}

// ------------------------------------------------------------ helpers
function mkApp(pkg: string, overrides: Partial<CatalogApp> = {}): CatalogApp {
  return {
    packageName: pkg,
    name: pkg,
    iconUrl: null,
    storeUrl: `https://play.google.com/store/apps/details?id=${pkg}`,
    shortDescription: 'd',
    fullDescription: null,
    rating: null,
    ratingCount: null,
    installText: null,
    estimatedMinimumInstalls: null,
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
    lastMetadataRefresh: null,
    enabledForPromotion: true,
    promotionMultiplier: 1,
    ...overrides,
  };
}

function mkCatalog(packages: string[]): Catalog {
  return {
    version: 1,
    generatedAt: new Date().toISOString(),
    source: 'test',
    apps: packages.map((p) => mkApp(p)),
  };
}

const basePatch: MetadataPatch = {
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

describe('metadata patch application', () => {
  it('overwrites only fields present in the patch (nullable-safe)', () => {
    const app = mkApp('com.x.app', { rating: 3.0, ratingCount: 10, installText: '50+', name: 'Old Name' });
    applyPatch(app, { ...basePatch, installText: '100+', rating: null }, '2026-09-29T00:00:00Z');
    expect(app.installText).toBe('100+');
    expect(app.rating).toBe(3.0); // null in patch = "unknown", keep previous
    expect(app.ratingCount).toBe(10);
    expect(app.lastMetadataRefresh).toBe('2026-09-29T00:00:00Z');
  });

  it('applies real-shaped metadata', () => {
    const app = mkApp('com.x.app');
    applyPatch(
      app,
      { ...basePatch, installText: '100+', estimatedMinimumInstalls: 100, iconUrl: 'https://play-lh.example/i=w256' },
      '2026-09-29T00:00:00Z'
    );
    expect(app.estimatedMinimumInstalls).toBe(100);
    expect(app.iconUrl).toContain('play-lh');
  });
});

describe('aggregate analytics math', () => {
  it('accumulates impressions and clicks with pair keys', async () => {
    const { env } = mkEnv();
    const store = new Store(env);
    await store.applyEvents([
      { event: 'promo_impression', sourcePackage: 'com.a', targetPackage: 'com.b', placement: 'settings', ts: 100, sdkVersion: '1.0.0' },
      { event: 'promo_impression', sourcePackage: 'com.a', targetPackage: 'com.b', placement: 'settings', ts: 110, sdkVersion: null },
      { event: 'promo_click', sourcePackage: 'com.a', targetPackage: 'com.b', placement: 'settings', ts: 120, sdkVersion: '1.0.0' },
    ], '2026-09-29');
    const agg = (await store.getDailyAggregate('2026-09-29'))!;
    expect(agg.impressions['com.b|com.a']).toBe(2);
    expect(agg.clicks['com.b|com.a']).toBe(1);
    expect(agg.impressionsPlacement['settings']).toBe(2);
    expect(agg.sdk['1.0.0']).toBe(2); // third event carried sdkVersion: null
    expect(agg.totalEvents).toBe(3);
  });

  it('starts from emptyAggregate shape', () => {
    const a = emptyAggregate();
    expect(a.impressions).toEqual({});
    expect(a.totalEvents).toBe(0);
    expect(a.firstTs).toBeNull();
  });

  it('concurrent shard writers never drop each other (last-write-wins safe)', async () => {
    const { env } = mkEnv();
    const store = new Store(env);
    const evt = (ts: number) => ({
      event: 'promo_impression' as const,
      sourcePackage: 'com.a',
      targetPackage: 'com.b',
      placement: 'settings',
      ts,
      sdkVersion: null,
    });
    // Simulates two uncoordinated /events requests racing on the same day.
    await store.applyEvents([evt(100)], '2026-09-29', 'shard-one');
    await store.applyEvents([evt(200)], '2026-09-29', 'shard-two');
    const agg = (await store.getDailyAggregate('2026-09-29'))!;
    expect(agg.impressions['com.b|com.a']).toBe(2);
    expect(agg.totalEvents).toBe(2);
  });

  it('merges pre-sharding base aggregate with shards', async () => {
    const { env, kv } = mkEnv();
    const store = new Store(env);
    const legacy = { ...emptyAggregate(), impressions: { 'com.b|com.a': 7 }, totalEvents: 7 };
    await kv.put('daily:2026-09-29', JSON.stringify(legacy));
    await store.applyEvents(
      [{ event: 'promo_impression', sourcePackage: 'com.a', targetPackage: 'com.b', placement: 's', ts: 1, sdkVersion: null }],
      '2026-09-29',
      'shard-x'
    );
    const agg = (await store.getDailyAggregate('2026-09-29'))!;
    expect(agg.impressions['com.b|com.a']).toBe(8);
    expect(agg.totalEvents).toBe(8);
  });

  it('listDailyKeys returns bare day keys across shards', async () => {
    const { env } = mkEnv();
    const store = new Store(env);
    const evt = { event: 'promo_impression', sourcePackage: 'com.a', targetPackage: 'com.b', placement: 's', ts: 1, sdkVersion: null } as const;
    await store.applyEvents([evt], '2026-09-28', 'a');
    await store.applyEvents([evt], '2026-09-29', 'b');
    const days = await store.listDailyKeys();
    expect(days).toContain('2026-09-28');
    expect(days).toContain('2026-09-29');
    expect(days.some((d) => d.includes(':'))).toBe(false);
  });

  it('sanitizeConfig clamps remote config abuse', () => {
    const cfg = sanitizeConfig({
      ...DEFAULT_CONFIG_WIRE,
      popularWeight: 7.5,
      maxLimit: 999,
      newAppBoostDays: -5,
      sessionRotationHours: 10000,
      enabled: false,
    } as never);
    expect(cfg.popularWeight).toBe(1);
    expect(cfg.maxLimit).toBe(10);
    expect(cfg.newAppBoostDays).toBe(0);
    expect(cfg.sessionRotationHours).toBe(24);
    expect(cfg.enabled).toBe(false);
  });

  it('sanitizeConfig keeps multipliers above one (default 1.8)', () => {
    const boosted = sanitizeConfig({ ...DEFAULT_CONFIG_WIRE, newAppBoostMultiplier: 2.5 } as never);
    expect(boosted.newAppBoostMultiplier).toBe(2.5);
    const defaulted = sanitizeConfig({ ...DEFAULT_CONFIG_WIRE, newAppBoostMultiplier: 0 } as never);
    expect(defaulted.newAppBoostMultiplier).toBe(1.8);
    const negative = sanitizeConfig({ ...DEFAULT_CONFIG_WIRE, newAppBoostMultiplier: -3 } as never);
    expect(negative.newAppBoostMultiplier).toBe(1.8);
  });

  it('emptyRefreshMeta has safe nulls', () => {
    const m = emptyRefreshMeta();
    expect(m.lastResult).toBeNull();
    expect(m.lastCounts).toBeNull();
    expect(m.lastDiagnostics).toBeNull();
  });
});

describe('event validation (bot/spam protection)', () => {
  const catalogPackages = new Set(['com.hartmann.source', 'com.hartmann.target']);

  it('accepts a valid click', () => {
    const v = validateEvent(
      {
        event: 'promo_click',
        sourcePackage: 'com.hartmann.source',
        targetPackage: 'com.hartmann.target',
        placement: 'settings',
        rankPosition: 1,
        selectionType: 'popular',
        sessionId: 'abc123',
        recommendationRequestId: 'req456',
        sdkVersion: '1.0.0',
      },
      catalogPackages
    );
    expect(v.ok).toBe(true);
  });

  it('rejects packages outside the catalog', () => {
    const v = validateEvent(
      { event: 'promo_click', sourcePackage: 'com.evil.spam', targetPackage: 'com.hartmann.target', placement: 'x' },
      catalogPackages
    );
    expect(v.ok).toBe(false);
    const v2 = validateEvent(
      { event: 'promo_click', sourcePackage: 'com.hartmann.source', targetPackage: 'com.other.app', placement: 'x' },
      catalogPackages
    );
    expect(v2.ok).toBe(false);
  });

  it('rejects unknown event types', () => {
    expect(
      validateEvent(
        { event: 'promo_victory', sourcePackage: 'com.hartmann.source', targetPackage: 'com.hartmann.target', placement: 'x' },
        catalogPackages
      ).ok
    ).toBe(false);
  });

  it('rejects invalid placements, ranks, selection types, sessions', () => {
    const ok = { sourcePackage: 'com.hartmann.source', targetPackage: 'com.hartmann.target' };
    expect(validateEvent({ ...ok, event: 'promo_impression', placement: 'bad placement!' }, catalogPackages).ok).toBe(false);
    expect(validateEvent({ ...ok, event: 'promo_impression', placement: 'ok', rankPosition: 99 }, catalogPackages).ok).toBe(false);
    expect(validateEvent({ ...ok, event: 'promo_impression', placement: 'ok', selectionType: 'hacked' }, catalogPackages).ok).toBe(false);
    expect(validateEvent({ ...ok, event: 'promo_impression', placement: 'ok', sessionId: 'bad session id!!' }, catalogPackages).ok).toBe(false);
    expect(validateEvent({ ...ok, event: 'promo_impression', placement: 'ok', ts: 12345 }, catalogPackages).ok).toBe(false);
  });

  it('rejects malformed input shapes', () => {
    expect(validateEvent(null, catalogPackages).ok).toBe(false);
    expect(validateEvent('spam', catalogPackages).ok).toBe(false);
    expect(validateEvent({}, catalogPackages).ok).toBe(false);
  });
});

interface DailyAggregateWire {
  impressions: Record<string, number>;
  clicks: Record<string, number>;
  impressionsPlacement: Record<string, number>;
  clicksPlacement: Record<string, number>;
  impressionsTarget: Record<string, number>;
  clicksTarget: Record<string, number>;
  sdk: Record<string, number>;
  totalEvents: number;
  firstTs: number | null;
  lastTs: number | null;
}

const DEFAULT_CONFIG_WIRE = {
  enabled: true,
  popularWeight: 0.65,
  newAppBoostDays: 14,
  newAppBoostMultiplier: 1.8,
  defaultLimit: 3,
  maxLimit: 6,
  minKeepRatio: 0.5,
  sessionRotationHours: 6,
  maxExclude: 20,
  maxRecent: 20,
};

export { mkCatalog, mkApp };
