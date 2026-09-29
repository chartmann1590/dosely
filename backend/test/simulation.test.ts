/**
 * RECOMMENDATION SIMULATION — 100,000 selections against a sample catalog.
 *
 * Verifies at scale:
 *  - popular apps receive more exposure than small ones
 *  - lower-ranked apps still receive meaningful exposure
 *  - new apps receive a temporary boost (and not a permanent one)
 *  - no single app dominates excessively
 *  - the current app is never selected
 *  - disabled apps never appear
 *  - no duplicates inside any single response
 */
import { describe, expect, it } from 'vitest';
import type { Catalog, CatalogApp, EngineConfig } from '../src/types';
import { DEFAULT_CONFIG } from '../src/types';
import { recommend, type RecommendRequest } from '../src/engine';

const NOW = 1_790_000_000_000;
const DAY = 86_400_000;

function mkCatalog(n: number): Catalog {
  const apps: CatalogApp[] = [];
  for (let i = 0; i < n; i++) {
    const tier = i === 0 ? 10_000_000 : i < 4 ? 100_000 * (5 - i) : 100 * (i + 1);
    apps.push({
      packageName: `com.sim.app${i}`,
      name: `Sim App ${i}`,
      iconUrl: null,
      storeUrl: `https://play.google.com/store/apps/details?id=com.sim.app${i}`,
      shortDescription: null,
      fullDescription: null,
      rating: i % 3 === 0 ? 4 + (i % 10) / 10 : null,
      ratingCount: i % 3 === 0 ? 1000 * (i + 1) : null,
      installText: `${tier}+`,
      estimatedMinimumInstalls: tier,
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
      enabledForPromotion: i !== 8, // app8 is disabled
      promotionMultiplier: 1,
    });
  }
  return { version: 1, generatedAt: new Date(NOW).toISOString(), source: 'sim', apps };
}

function req(sessionId: string, overrides: Partial<RecommendRequest> = {}): RecommendRequest {
  return {
    sourcePackage: 'com.current.app',
    placement: 'sim',
    limit: 3,
    sessionId,
    exclude: [],
    locale: null,
    now: NOW,
    seedSalt: 'simulation',
    ...overrides,
  };
}

describe('100k recommendation simulation', () => {
  const catalog = mkCatalog(20); // 19 eligible (app8 disabled)
  const TRIALS = 100_000;
  const counts: Record<string, number> = {};
  const selectionTypes: Record<string, number> = {};
  let responses = 0;
  let currentLeak = 0;
  let disabledLeak = 0;
  let duplicateLeaks = 0;

  for (let i = 0; i < TRIALS; i++) {
    const r = recommend({
      catalog,
      config: DEFAULT_CONFIG,
      appConfig: null,
      targetConfigs: new Map(),
      request: req(`session-${i}`),
    });
    if (r.apps.length > 0) responses++;
    const seen = new Set<string>();
    for (const app of r.apps) {
      counts[app.packageName] = (counts[app.packageName] ?? 0) + 1;
      selectionTypes[app.selectionType] = (selectionTypes[app.selectionType] ?? 0) + 1;
      if (app.packageName === 'com.current.app') currentLeak++;
      if (app.packageName === 'com.sim.app8') disabledLeak++;
      if (seen.has(app.packageName)) duplicateLeaks++;
      seen.add(app.packageName);
    }
  }

  it('no disabled app is ever selected', () => {
    expect(disabledLeak).toBe(0);
  });

  it('current app can never be selected (it is not even in this catalog, belt & braces)', () => {
    expect(currentLeak).toBe(0);
  });

  it('no response contains duplicate apps', () => {
    expect(duplicateLeaks).toBe(0);
  });

  it('popular app leads the distribution but never dominates', () => {
    const slots = responses * 3;
    const top = counts['com.sim.app0'] ?? 0;
    const share = top / slots;
    // The most popular app must appear more often than a uniform share (1/19 ≈ 5.3%)…
    expect(share).toBeGreaterThan(0.08);
    // …while still leaving the majority of exposure to the rest of the catalog.
    expect(share).toBeLessThan(0.5);
    // …and no other single app may exceed the leader.
    for (const [pkg, c] of Object.entries(counts)) {
      if (pkg !== 'com.sim.app0') expect(c).toBeLessThanOrEqual(top);
    }
    console.log('distribution (share of slots):');
    for (const [pkg, c] of Object.entries(counts).sort((a, b) => b[1] - a[1]).slice(0, 8)) {
      console.log(`  ${pkg}: ${((c / slots) * 100).toFixed(2)}%`);
    }
  });

  it('tail apps all receive meaningful exposure', () => {
    for (let i = 9; i < 20; i++) {
      if (i === 8) continue;
      const share = (counts[`com.sim.app${i}`] ?? 0) / (responses * 3);
      expect(share).toBeGreaterThan(0.005); // at least ~0.5% of slots
    }
  });

  it('every response returned apps (system healthy at scale)', () => {
    expect(responses).toBe(TRIALS);
  });

  it('selection types include the exploration channel', () => {
    const total = Object.values(selectionTypes).reduce((s, v) => s + v, 0);
    const randomShare = (selectionTypes['random'] ?? 0) / total;
    console.log('selectionTypes:', selectionTypes);
    expect(randomShare).toBeGreaterThan(0.15); // ~35% minus new-app spillover
    expect(selectionTypes['popular']).toBeGreaterThan(0);
  });

  it('new app boost is temporary, not permanent', () => {
    const freshPkg = 'com.sim.app19';
    // Fresh copy of the catalog where app19 was discovered yesterday.
    const fresh: Catalog = {
      ...catalog,
      apps: catalog.apps.map((a) =>
        a.packageName === freshPkg ? { ...a, firstDiscoveredAt: new Date(NOW - DAY).toISOString() } : a
      ),
    };
    const boostCounts: Record<string, number> = {};
    for (let i = 0; i < 20_000; i++) {
      const r = recommend({
        catalog: fresh,
        config: DEFAULT_CONFIG,
        appConfig: null,
        targetConfigs: new Map(),
        request: req(`fresh-${i}`, { limit: 1 }),
      });
      const p = r.apps[0]?.packageName;
      if (p) boostCounts[p] = (boostCounts[p] ?? 0) + 1;
    }
    const baseCounts: Record<string, number> = {};
    for (let i = 0; i < 20_000; i++) {
      const r = recommend({
        catalog,
        config: DEFAULT_CONFIG,
        appConfig: null,
        targetConfigs: new Map(),
        request: req(`base-${i}`, { limit: 1 }),
      });
      const p = r.apps[0]?.packageName;
      if (p) baseCounts[p] = (baseCounts[p] ?? 0) + 1;
    }
    const boosted = boostCounts[freshPkg] ?? 0;
    const base = baseCounts[freshPkg] ?? 0;
    console.log(`new-app share: boosted=${(boosted / 20000).toFixed(3)} base=${(base / 20000).toFixed(3)}`);
    expect(boosted).toBeGreaterThan(base * 1.3); // boost while new
    // And after the boost window expires it falls back to base behavior:
    const aged: Catalog = {
      ...fresh,
      apps: fresh.apps.map((a) =>
        a.packageName === freshPkg ? { ...a, firstDiscoveredAt: new Date(NOW - 30 * DAY).toISOString() } : a
      ),
    };
    const agedCounts: Record<string, number> = {};
    for (let i = 0; i < 20_000; i++) {
      const r = recommend({
        catalog: aged,
        config: DEFAULT_CONFIG,
        appConfig: null,
        targetConfigs: new Map(),
        request: req(`aged-${i}`, { limit: 1 }),
      });
      const p = r.apps[0]?.packageName;
      if (p) agedCounts[p] = (agedCounts[p] ?? 0) + 1;
    }
    expect(agedCounts[freshPkg] ?? 0).toBeLessThan(boosted); // boost is gone
  });

  it('config override: popularWeight=1 collapses to weighted-only', () => {
    const cfg: EngineConfig = { ...DEFAULT_CONFIG, popularWeight: 1, newAppBoostDays: 0 };
    const types: Record<string, number> = {};
    for (let i = 0; i < 5000; i++) {
      const r = recommend({ catalog, config: cfg, appConfig: null, targetConfigs: new Map(), request: req(`pw1-${i}`, { limit: 1 }) });
      types[r.apps[0]?.selectionType ?? '?'] = (types[r.apps[0]?.selectionType ?? '?'] ?? 0) + 1;
    }
    expect(types['random'] ?? 0).toBe(0);
  });
});
