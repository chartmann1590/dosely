import { describe, expect, it } from 'vitest';
import type { Catalog, CatalogApp, EngineConfig } from '../src/types';
import { DEFAULT_CONFIG } from '../src/types';
import {
  eligibleApps,
  explorationMultiplier,
  isNewApp,
  popularityScore,
  recommend,
  seededRandom,
  sessionWindow,
  type EngineApp,
  type RecommendRequest,
} from '../src/engine';

const DAY = 86_400_000;
const NOW = 1_790_000_000_000;

function mkApp(overrides: Partial<CatalogApp> & { packageName: string }): CatalogApp {
  return {
    name: overrides.packageName,
    iconUrl: null,
    storeUrl: `https://play.google.com/store/apps/details?id=${overrides.packageName}`,
    shortDescription: 'desc',
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
    firstDiscoveredAt: new Date(NOW - 90 * DAY).toISOString(),
    lastSeenAt: new Date(NOW).toISOString(),
    lastMetadataRefresh: null,
    enabledForPromotion: true,
    promotionMultiplier: 1,
    ...overrides,
  };
}

function mkCatalog(apps: CatalogApp[]): Catalog {
  return { version: 1, generatedAt: new Date(NOW).toISOString(), source: 'test', apps };
}

function mkRequest(overrides: Partial<RecommendRequest> = {}): RecommendRequest {
  return {
    sourcePackage: 'com.source.app',
    placement: 'settings',
    limit: 3,
    sessionId: 'sess',
    exclude: [],
    locale: null,
    now: NOW,
    seedSalt: 'salt',
    ...overrides,
  };
}

describe('popularity scoring', () => {
  it('ranks installs logarithmically and never exceeds 1', () => {
    const big: EngineApp = baseApp({ estimatedMinimumInstalls: 10_000_000 });
    const small: EngineApp = baseApp({ estimatedMinimumInstalls: 100 });
    const sBig = popularityScore(big);
    const sSmall = popularityScore(small);
    expect(sBig).toBeGreaterThan(sSmall);
    expect(sBig).toBeLessThanOrEqual(1);
    expect(sSmall).toBeGreaterThanOrEqual(0);
  });

  it('falls back to review count + rating when installs are missing', () => {
    const rated: EngineApp = baseApp({ estimatedMinimumInstalls: null, ratingCount: 5000, rating: 4.5 });
    const unrated: EngineApp = baseApp({ estimatedMinimumInstalls: null, ratingCount: null, rating: null });
    expect(popularityScore(rated)).toBeGreaterThan(popularityScore(unrated));
  });

  it('gives floor weight when no metrics exist (never invents data)', () => {
    const bare: EngineApp = baseApp({});
    expect(popularityScore(bare)).toBeCloseTo(0.1, 5);
  });

  it('does not trust ratings without a rating count', () => {
    const highRatingNoCount: EngineApp = baseApp({ rating: 5.0, ratingCount: null });
    const none: EngineApp = baseApp({});
    expect(popularityScore(highRatingNoCount)).toBeCloseTo(popularityScore(none), 5);
  });
});

function baseApp(overrides: Partial<EngineApp>): EngineApp {
  return {
    packageName: 'com.test.app',
    name: 'T',
    iconUrl: null,
    shortDescription: null,
    rating: null,
    ratingCount: null,
    installText: null,
    estimatedMinimumInstalls: null,
    storeUrl: 'https://play.google.com/store/apps/details?id=com.test.app',
    firstDiscoveredAt: new Date(NOW - 90 * DAY).toISOString(),
    promotionMultiplier: 1,
    enabledForPromotion: true,
    ...overrides,
  };
}

describe('current-app exclusion & eligibility', () => {
  const catalog = mkCatalog([
    mkApp({ packageName: 'com.source.app' }),
    mkApp({ packageName: 'com.a.app' }),
    mkApp({ packageName: 'com.b.app', enabledForPromotion: false }),
    mkApp({ packageName: 'com.c.app' }),
  ]);

  it('never includes the current app', () => {
    const r = eligibleApps(catalog, DEFAULT_CONFIG, null, new Map(), mkRequest());
    expect(r.eligible.map((a) => a.packageName)).not.toContain('com.source.app');
    expect(r.excludedCurrent).toBe(1);
  });

  it('excludes disabled apps', () => {
    const r = eligibleApps(catalog, DEFAULT_CONFIG, null, new Map(), mkRequest());
    expect(r.eligible.map((a) => a.packageName)).not.toContain('com.b.app');
    expect(r.excludedConfig).toBe(1);
  });

  it('target config can disable an app and exclude from a source', () => {
    const r = eligibleApps(
      catalog,
      DEFAULT_CONFIG,
      null,
      new Map([['com.a.app', { enabledForPromotion: false }]]),
      mkRequest()
    );
    expect(r.eligible.map((a) => a.packageName)).not.toContain('com.a.app');

    const r2 = eligibleApps(
      catalog,
      DEFAULT_CONFIG,
      null,
      new Map([['com.c.app', { excludedFromSources: ['com.source.app'] }]]),
      mkRequest()
    );
    // com.c is source-excluded; com.a stays eligible, com.b is disabled.
    expect(r2.eligible.map((a) => a.packageName)).toEqual(['com.a.app']);
  });

  it('app-specific enable=false empties the pool (remote kill switch)', () => {
    const r = eligibleApps(
      catalog,
      DEFAULT_CONFIG,
      { enabled: false },
      new Map(),
      mkRequest()
    );
    expect(r.eligible).toHaveLength(0);
  });
});

describe('recommendation invariants', () => {
  const catalog = mkCatalog(
    Array.from({ length: 10 }, (_, i) =>
      mkApp({
        packageName: `com.app${i}.pkg`,
        estimatedMinimumInstalls: i === 0 ? 5_000_000 : 100 * (i + 1),
      })
    )
  );

  it('returns unique apps only (no duplicates)', () => {
    const r = recommend({
      catalog,
      config: DEFAULT_CONFIG,
      appConfig: null,
      targetConfigs: new Map(),
      request: mkRequest({ limit: 5 }),
    });
    const pkgs = r.apps.map((a) => a.packageName);
    expect(new Set(pkgs).size).toBe(pkgs.length);
    expect(pkgs.length).toBe(5);
  });

  it('caps limit to available eligible apps', () => {
    const r = recommend({
      catalog,
      config: DEFAULT_CONFIG,
      appConfig: null,
      targetConfigs: new Map(),
      request: mkRequest({ limit: 50 }),
    });
    expect(r.apps.length).toBe(10); // all eligible apps are returned
  });

  it('respects manual exclude list', () => {
    const r = recommend({
      catalog,
      config: DEFAULT_CONFIG,
      appConfig: null,
      targetConfigs: new Map(),
      request: mkRequest({ limit: 5, exclude: ['com.app1.pkg', 'com.app2.pkg'] }),
    });
    const pkgs = r.apps.map((a) => a.packageName);
    expect(pkgs).not.toContain('com.app1.pkg');
    expect(pkgs).not.toContain('com.app2.pkg');
  });

  it('deterministic per session+window (session rotation)', () => {
    const mk = (over: Partial<RecommendRequest> = {}) =>
      recommend({ catalog, config: DEFAULT_CONFIG, appConfig: null, targetConfigs: new Map(), request: mkRequest(over) });
    const a = mk({ sessionId: 'user-1' });
    const b = mk({ sessionId: 'user-1' });
    const c = mk({ sessionId: 'user-2' });
    expect(a.apps.map((x) => x.packageName)).toEqual(b.apps.map((x) => x.packageName));
    expect(a.apps.map((x) => x.packageName)).not.toEqual(c.apps.map((x) => x.packageName));
  });

  it('window changes rotate the selection', () => {
    const mk = (now: number) =>
      recommend({
        catalog,
        config: DEFAULT_CONFIG,
        appConfig: null,
        targetConfigs: new Map(),
        request: mkRequest({ now, sessionId: 'user-1' }),
      });
    const first = mk(NOW);
    const later = mk(NOW + 7 * 3_600_000);
    expect(first.apps.map((x) => x.packageName)).not.toEqual(later.apps.map((x) => x.packageName));
  });
});

describe('new app boost', () => {
  it('flags apps discovered within the boost window', () => {
    const fresh: EngineApp = baseApp({ firstDiscoveredAt: new Date(NOW - 3 * DAY).toISOString() });
    const old: EngineApp = baseApp({ firstDiscoveredAt: new Date(NOW - 30 * DAY).toISOString() });
    expect(isNewApp(fresh, DEFAULT_CONFIG, NOW)).toBe(true);
    expect(isNewApp(old, DEFAULT_CONFIG, NOW)).toBe(false);
    const b = explorationMultiplier(fresh, DEFAULT_CONFIG, NOW);
    expect(b.multiplier).toBeGreaterThan(1);
    expect(b.type).toBe('new_app_boost');
    expect(explorationMultiplier(old, DEFAULT_CONFIG, NOW).multiplier).toBe(1);
  });

  it('new apps get selected more often than equally popular old apps, without dominating', () => {
    // 3 old apps with identical weight + 1 brand new identical-weight app.
    const catalog = mkCatalog([
      mkApp({ packageName: 'com.old1.app', estimatedMinimumInstalls: 1000 }),
      mkApp({ packageName: 'com.old2.app', estimatedMinimumInstalls: 1000 }),
      mkApp({ packageName: 'com.old3.app', estimatedMinimumInstalls: 1000 }),
      mkApp({ packageName: 'com.fresh.app', estimatedMinimumInstalls: 1000, firstDiscoveredAt: new Date(NOW - 1 * DAY).toISOString() }),
    ]);
    const counts: Record<string, number> = {};
    const trials = 4000;
    for (let i = 0; i < trials; i++) {
      const r = recommend({
        catalog,
        config: DEFAULT_CONFIG,
        appConfig: null,
        targetConfigs: new Map(),
        request: mkRequest({ sessionId: `boost-${i}`, limit: 1 }),
      });
      const pkg = r.apps[0]?.packageName;
      if (pkg) counts[pkg] = (counts[pkg] ?? 0) + 1;
    }
    const fresh = counts['com.fresh.app'] ?? 0;
    const oldTotal = (counts['com.old1.app'] ?? 0) + (counts['com.old2.app'] ?? 0) + (counts['com.old3.app'] ?? 0);
    expect(fresh / trials).toBeGreaterThan(0.25); // boosted above 25% baseline
    expect(fresh / trials).toBeLessThan(0.75); // but never dominating
    expect(oldTotal / trials).toBeGreaterThan(0.2);
  });
});

describe('popularity-weighted vs exploration mix', () => {
  it('popular apps appear more often but small apps still get exposure', () => {
    const catalog = mkCatalog([
      mkApp({ packageName: 'com.popular.app', estimatedMinimumInstalls: 10_000_000 }),
      ...Array.from({ length: 7 }, (_, i) =>
        mkApp({ packageName: `com.small${i}.app`, estimatedMinimumInstalls: 50 * (i + 1) })
      ),
    ]);
    const counts: Record<string, number> = {};
    const trials = 6000;
    for (let i = 0; i < trials; i++) {
      const r = recommend({
        catalog,
        config: DEFAULT_CONFIG,
        appConfig: null,
        targetConfigs: new Map(),
        request: mkRequest({ sessionId: `mix-${i}`, limit: 1 }),
      });
      const pkg = r.apps[0]?.packageName;
      if (pkg) counts[pkg] = (counts[pkg] ?? 0) + 1;
    }
    const popularShare = (counts['com.popular.app'] ?? 0) / trials;
    // Popular app must lead the distribution…
    expect(popularShare).toBeGreaterThan(0.2);
    // …but not hog everything (exploration must surface small apps).
    expect(popularShare).toBeLessThan(0.6);
    // Every small app receives meaningful exposure.
    for (let i = 0; i < 7; i++) {
      const share = (counts[`com.small${i}.app`] ?? 0) / trials;
      expect(share).toBeGreaterThan(0.02);
    }
  });
});

describe('deterministic helpers', () => {
  it('seededRandom is stable and in range', () => {
    const a = seededRandom('x|y|1');
    const b = seededRandom('x|y|1');
    const seqA = [a(), a(), a()];
    const seqB = [b(), b(), b()];
    expect(seqA).toEqual(seqB);
    for (const v of seqA) {
      expect(v).toBeGreaterThanOrEqual(0);
      expect(v).toBeLessThan(1);
    }
  });

  it('sessionWindow buckets time', () => {
    expect(sessionWindow(0, 6)).toBe(0);
    expect(sessionWindow(6 * 3_600_000 - 1, 6)).toBe(0);
    expect(sessionWindow(6 * 3_600_000, 6)).toBe(1);
  });
});

describe('remote config influence', () => {
  it('popularWeight 0 forces pure exploration', () => {
    const catalog = mkCatalog([
      mkApp({ packageName: 'com.popular.app', estimatedMinimumInstalls: 100_000_000 }),
      mkApp({ packageName: 'com.tiny.app', estimatedMinimumInstalls: 10 }),
    ]);
    const cfg: EngineConfig = { ...DEFAULT_CONFIG, popularWeight: 0 };
    const counts: Record<string, number> = { 'com.popular.app': 0, 'com.tiny.app': 0 };
    for (let i = 0; i < 4000; i++) {
      const r = recommend({ catalog, config: cfg, appConfig: null, targetConfigs: new Map(), request: mkRequest({ sessionId: `p0-${i}`, limit: 1 }) });
      counts[r.apps[0]!.packageName]++;
    }
    expect(counts['com.tiny.app']).toBeGreaterThan(counts['com.popular.app'] * 0.7);
  });

  it('promotionMultiplier amplifies exposure', () => {
    const catalog = mkCatalog([
      mkApp({ packageName: 'com.a.app', estimatedMinimumInstalls: 1_000_000 }),
      mkApp({ packageName: 'com.b.app', estimatedMinimumInstalls: 1_000, promotionMultiplier: 25 }),
    ]);
    const counts: Record<string, number> = { 'com.a.app': 0, 'com.b.app': 0 };
    for (let i = 0; i < 6000; i++) {
      const r = recommend({
        catalog,
        config: DEFAULT_CONFIG,
        appConfig: null,
        targetConfigs: new Map(),
        request: mkRequest({ sessionId: `mult-${i}`, limit: 1 }),
      });
      counts[r.apps[0]!.packageName]++;
    }
    expect(counts['com.b.app'] / 6000).toBeGreaterThan(0.35);
  });
});
