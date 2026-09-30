/**
 * Recommendation engine.
 *
 * Selection is a two-phase draw:
 *   roll < popularWeight → popularity-weighted pool
 *   else                 → uniform random (exploration)
 *
 * Per-selection, a per-target "exploration multiplier" may promote an app from
 * the exploration draw into the weighted draw (currently: new-app boost).
 *
 * Guarantees enforced here:
 *   - current app and explicit excludes can never be selected
 *   - disabled apps (engine or per-app/target config) can never be selected
 *   - no duplicates within one response
 *   - selection is stable within a sessionRotationHours window for a session
 */
import type { CatalogApp, Catalog, EngineConfig, SelectionType, AppConfig, TargetConfig } from './types';

export interface EngineApp {
  packageName: string;
  name: string | null;
  iconUrl: string | null;
  shortDescription: string | null;
  rating: number | null;
  ratingCount: number | null;
  installText: string | null;
  estimatedMinimumInstalls: number | null;
  storeUrl: string;
  firstDiscoveredAt: string | null;
  promotionMultiplier: number;
  enabledForPromotion: boolean;
}

export interface RecommendRequest {
  sourcePackage: string;
  placement: string;
  limit: number;
  sessionId: string | null;
  exclude: string[];
  locale: string | null;
  now: number;
  seedSalt: string;
}

export interface RecommendInput {
  catalog: Catalog;
  config: EngineConfig;
  appConfig: AppConfig | null;
  targetConfigs: Map<string, TargetConfig>;
  request: RecommendRequest;
}

export interface SelectedApp extends EngineApp {
  selectionType: SelectionType;
  rankPosition: number;
}

export interface RecommendResult {
  apps: SelectedApp[];
  skippedReason: string | null;
}

export const HOURS_MS = 3_600_000;
const DAY_MS = 86_400_000;

/** Share of exploration draws a new app can win (bounded visibility spike). */
const NEW_APP_DRAW_P = 0.15;

/**
 * No single app may hold more than this share of the total weighted-pool
 * weight. Popularity gaps (e.g. one app with 5,000+ installs over a field
 * of 0–100 install apps) otherwise let one app dominate every weighted draw.
 * Iteratively clamped so the cap stays meaningful after renormalization.
 */
const MAX_APP_WEIGHT_SHARE = 0.25;

/** Popularity: installs first; only trust ratingCount/rating when installs are absent. */
export function popularityScore(a: EngineApp): number {
  const installMin = a.estimatedMinimumInstalls ?? null;
  let installScore: number | null = null;
  if (installMin != null) {
    // Normalized log scale: 100 → 0.0, 100k → 0.4, 10M → 0.8, 1B → 1.0.
    // Keeps the popularity gap visible without flattening the long tail.
    const log = Math.log10(Math.max(installMin, 1));
    installScore = Math.min(1, Math.max(0, (log - 2) / 7));
  }

  let reviewScore: number | null = null;
  if (a.ratingCount != null) {
    const log = Math.log10(Math.max(a.ratingCount, 1));
    reviewScore = Math.min(1, Math.max(0, log / 5));
  }

  let ratingScore: number | null = null;
  if (a.rating != null && a.ratingCount != null && a.ratingCount >= 5) {
    // Bayesian-ish shrink toward 3.5 for low counts.
    const k = 10;
    const shrunk = (a.rating * a.ratingCount + 3.5 * k) / (a.ratingCount + k);
    ratingScore = (shrunk - 1) / 4;
  }

  const parts: [number, number][] = [];
  if (installScore != null) parts.push([installScore, 0.6]);
  if (reviewScore != null) parts.push([reviewScore, 0.25]);
  if (ratingScore != null) parts.push([ratingScore, 0.15]);

  if (parts.length === 0) return 0.1; // no public signals at all → floor weight

  const totalW = parts.reduce((s, [, w]) => s + w, 0);
  return parts.reduce((s, [v, w]) => s + v * (w / totalW), 0);
}

export function isNewApp(app: EngineApp, config: EngineConfig, now: number): boolean {
  if (!app.firstDiscoveredAt) return false;
  const age = now - Date.parse(app.firstDiscoveredAt);
  if (!Number.isFinite(age)) return false;
  return age >= 0 && age < config.newAppBoostDays * DAY_MS;
}

export function explorationMultiplier(
  app: EngineApp,
  config: EngineConfig,
  now: number
): { multiplier: number; type: SelectionType } {
  if (isNewApp(app, config, now)) {
    return { multiplier: config.newAppBoostMultiplier, type: 'new_app_boost' };
  }
  return { multiplier: 1, type: 'random' };
}

/** Deterministic per (session, window) RNG: stable picks inside the window. */
export function seededRandom(seedText: string): () => number {
  // FNV-1a inspired; xoshiro-ish multiply-wrap output in [0,1).
  let h = 2166136261 >>> 0;
  for (let i = 0; i < seedText.length; i++) {
    h ^= seedText.charCodeAt(i);
    h = Math.imul(h, 16777619) >>> 0;
  }
  return () => {
    h ^= h << 13;
    h >>>= 0;
    h ^= h >>> 17;
    h ^= h << 5;
    h >>>= 0;
    return h / 4294967296;
  };
}

export function sessionWindow(now: number, hours: number): number {
  return Math.floor(now / (hours * HOURS_MS));
}

export function eligibleApps(
  catalog: Catalog,
  config: EngineConfig,
  appConfig: AppConfig | null,
  targetConfigs: Map<string, TargetConfig>,
  request: RecommendRequest
): { eligible: EngineApp[]; excludedCurrent: number; excludedConfig: number; excludedManual: number } {
  const appEnabled = appConfig?.enabled !== false;
  const excludeSet = new Set([request.sourcePackage.toLowerCase(), ...request.exclude.map((e) => e.toLowerCase())]);
  const eligible: EngineApp[] = [];
  let excludedCurrent = 0;
  let excludedConfig = 0;
  let excludedManual = 0;

  for (const a of catalog.apps) {
    const t = targetConfigs.get(a.packageName);
    const engine: EngineApp = {
      packageName: a.packageName,
      name: a.name,
      iconUrl: a.iconUrl,
      shortDescription: a.shortDescription,
      rating: a.rating,
      ratingCount: a.ratingCount,
      installText: a.installText,
      estimatedMinimumInstalls: a.estimatedMinimumInstalls,
      storeUrl: a.storeUrl,
      firstDiscoveredAt: a.firstDiscoveredAt,
      promotionMultiplier: t?.promotionMultiplier ?? a.promotionMultiplier,
      enabledForPromotion: a.enabledForPromotion,
    };
    if (a.packageName.toLowerCase() === request.sourcePackage.toLowerCase()) {
      excludedCurrent++;
      continue;
    }
    if (excludeSet.has(a.packageName.toLowerCase())) {
      excludedManual++;
      continue;
    }
    if (!appEnabled) {
      // Source app disabled → nothing eligible at all; handled by caller.
      continue;
    }
    if (engine.enabledForPromotion === false) {
      excludedConfig++;
      continue;
    }
    if (t?.enabledForPromotion === false) {
      excludedConfig++;
      continue;
    }
    if (t?.excludedFromSources?.some((s) => s.toLowerCase() === request.sourcePackage.toLowerCase())) {
      excludedConfig++;
      continue;
    }
    eligible.push(engine);
  }
  void config;
  return { eligible, excludedCurrent, excludedConfig, excludedManual };
}

export function recommend(input: RecommendInput): RecommendResult {
  const { catalog, config, request } = input;
  const { eligible } = eligibleApps(catalog, config, input.appConfig, input.targetConfigs, request);

  if (eligible.length === 0) {
    return { apps: [], skippedReason: 'no_eligible_apps' };
  }

  const rng = seededRandom(
    // The seed includes the request's seedSalt, which the API fills with a
    // fresh per-request nonce — every request re-rolls within the catalog.
    // (seedSalt is client-supplied and NOT trusted for security; it only
    // varies the pick. Eligibility and excludes are enforced structurally.)
    `${request.sessionId ?? 'anon'}|${request.seedSalt}|${sessionWindow(request.now, config.sessionRotationHours)}`
  );

  const limit = Math.min(request.limit, eligible.length);
  const chosen = new Map<string, SelectedApp>();

  while (chosen.size < limit) {
    const roll = rng();
    const explore = roll >= config.popularWeight;
    const pool = eligible.filter((a) => !chosen.has(a.packageName));
    if (pool.length === 0) break;

    let picked: EngineApp;
    let type: SelectionType;

    if (explore) {
      // Exploration draw: uniform. New apps get a bounded leg up: they win
      // the draw with probability NEW_APP_DRAW_P (not 50% — an unbounded
      // boost let the newest 2–3 apps dominate every exploration slot).
      const boosted = pool.filter((a) => isNewApp(a, config, request.now));
      if (boosted.length > 0 && rng() < NEW_APP_DRAW_P) {
        const idx = Math.floor(rng() * boosted.length) % boosted.length;
        picked = boosted[idx];
        type = 'new_app_boost';
      } else {
        const idx = Math.floor(rng() * pool.length) % pool.length;
        picked = pool[idx];
        type = 'random';
      }
    } else {
      // Weighted draw over pool, with a per-app share cap so a lopsided
      // install distribution can never monopolize the weighted channel.
      const weighted = pool.map((a) => {
        const base = popularityScore(a);
        const boost = explorationMultiplier(a, config, request.now);
        const mult = a.promotionMultiplier * boost.multiplier;
        return { app: a, weight: Math.max(0.01, base * mult), boost };
      });
      let total = weighted.reduce((s, w) => s + w.weight, 0);
      for (let iter = 0; iter < 4; iter++) {
        const cap = total * MAX_APP_WEIGHT_SHARE;
        let clamped = false;
        for (const w of weighted) {
          if (w.weight > cap) {
            w.weight = cap;
            clamped = true;
          }
        }
        const next = weighted.reduce((s, w) => s + w.weight, 0);
        if (!clamped || next === total) break;
        total = next;
      }
      let r = rng() * total;
      let hit = weighted[weighted.length - 1];
      for (const w of weighted) {
        r -= w.weight;
        if (r <= 0) {
          hit = w;
          break;
        }
      }
      picked = hit.app;
      type = hit.boost.multiplier > 1 ? 'new_app_boost' : 'popular';
    }

    chosen.set(picked.packageName, {
      ...picked,
      selectionType: type,
      rankPosition: chosen.size + 1,
    });
  }

  return { apps: [...chosen.values()], skippedReason: null };
}

/** Public recommendation response body. */
export function toResponseApps(apps: SelectedApp[]): unknown[] {
  return apps.map((a) => ({
    packageName: a.packageName,
    name: a.name,
    iconUrl: a.iconUrl,
    shortDescription: a.shortDescription,
    rating: a.rating,
    ratingCount: a.ratingCount,
    installText: a.installText,
    storeUrl: a.storeUrl,
    selectionType: a.selectionType,
  }));
}
