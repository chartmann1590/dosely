/**
 * KV-backed storage layer: catalog, engine config, per-app/target config,
 * refresh metadata, and daily analytics aggregates.
 *
 * KV is chosen over D1 deliberately: this deployment's API token scopes cover
 * Workers+KV; all analytics state is aggregate-only, and every write path is
 * batched, size-capped, and CONFLICT-FREE (per-request shards merged on
 * read — see applyEvents), so KV's consistency semantics are sufficient.
 */
import type {
  AppConfig,
  Catalog,
  DailyAggregate,
  EngineConfig,
  Env,
  RefreshDiagnostics,
  RefreshMeta,
  TargetConfig,
} from './types';
import { DEFAULT_CONFIG } from './types';

export const KV_KEYS = {
  currentCatalog: 'catalog:current',
  lastKnownGood: 'catalog:last-known-good',
  refreshMeta: 'catalog:refresh-meta',
  config: 'config:engine',
  appConfigPrefix: 'config:app:',
  targetConfigPrefix: 'config:target:',
};

function dayKey(ts = Date.now()): string {
  return new Date(ts).toISOString().slice(0, 10);
}

export function emptyAggregate(): DailyAggregate {
  return {
    impressions: {},
    clicks: {},
    impressionsPlacement: {},
    clicksPlacement: {},
    impressionsTarget: {},
    clicksTarget: {},
    sdk: {},
    totalEvents: 0,
    firstTs: null,
    lastTs: null,
  };
}

/** 'daily:<day>:<shardId>' — per-request analytics shard. */
function dailyShardKey(day: string, shardId: string): string {
  return `daily:${day}:${shardId}`;
}

/** All shard keys for a day (base key included when present). */
async function listShards(env: Env, day: string): Promise<DailyAggregate[]> {
  const shards: DailyAggregate[] = [];
  const base = await env.ANALYTICS_KV.get<DailyAggregate>('daily:' + day, 'json');
  if (base) shards.push(base); // pre-sharding data
  const list = await env.ANALYTICS_KV.list({ prefix: `daily:${day}:` });
  for (const k of list.keys) {
    const s = await env.ANALYTICS_KV.get<DailyAggregate>(k.name, 'json');
    if (s) shards.push(s);
  }
  return shards;
}

/** G-counter style merge: numeric fields add; min/max timestamps fold. */
function mergeShards(shards: DailyAggregate[]): DailyAggregate | null {
  if (shards.length === 0) return null;
  const out = emptyAggregate();
  for (const s of shards) {
    for (const [k, v] of Object.entries(s.impressions ?? {})) out.impressions[k] = (out.impressions[k] ?? 0) + v;
    for (const [k, v] of Object.entries(s.clicks ?? {})) out.clicks[k] = (out.clicks[k] ?? 0) + v;
    for (const [k, v] of Object.entries(s.impressionsPlacement ?? {})) out.impressionsPlacement[k] = (out.impressionsPlacement[k] ?? 0) + v;
    for (const [k, v] of Object.entries(s.clicksPlacement ?? {})) out.clicksPlacement[k] = (out.clicksPlacement[k] ?? 0) + v;
    for (const [k, v] of Object.entries(s.impressionsTarget ?? {})) out.impressionsTarget[k] = (out.impressionsTarget[k] ?? 0) + v;
    for (const [k, v] of Object.entries(s.clicksTarget ?? {})) out.clicksTarget[k] = (out.clicksTarget[k] ?? 0) + v;
    for (const [k, v] of Object.entries(s.sdk ?? {})) out.sdk[k] = (out.sdk[k] ?? 0) + v;
    out.totalEvents = Math.min(out.totalEvents + (s.totalEvents ?? 0), 2_000_000);
    if (s.firstTs != null && (out.firstTs === null || s.firstTs < out.firstTs)) out.firstTs = s.firstTs;
    if (s.lastTs != null && (out.lastTs === null || s.lastTs > out.lastTs)) out.lastTs = s.lastTs;
  }
  return out;
}

export class Store {
  constructor(private readonly env: Env) {}

  // ---------------- catalog ----------------

  async getCurrentCatalog(): Promise<Catalog | null> {
    return this.env.CATALOG_KV.get<Catalog>(KV_KEYS.currentCatalog, 'json');
  }

  async getLastKnownGoodCatalog(): Promise<Catalog | null> {
    return this.env.CATALOG_KV.get<Catalog>(KV_KEYS.lastKnownGood, 'json');
  }

  /** Catalog chosen for serving: current if valid, else last-known-good. */
  async getServeCatalog(): Promise<{ catalog: Catalog | null; degraded: boolean }> {
    const current = await this.getCurrentCatalog();
    if (current && current.apps.length > 0) return { catalog: current, degraded: false };
    const lkg = await this.getLastKnownGoodCatalog();
    if (lkg && lkg.apps.length > 0) return { catalog: lkg, degraded: true };
    return { catalog: null, degraded: false };
  }

  async writeRefreshedCatalog(catalog: Catalog): Promise<void> {
    const now = new Date().toISOString();
    const prevLkg = await this.getLastKnownGoodCatalog();
    await this.env.CATALOG_KV.put(KV_KEYS.currentCatalog, JSON.stringify(catalog));
    await this.env.CATALOG_KV.put(KV_KEYS.lastKnownGood, JSON.stringify(catalog));
    const meta = (await this.getRefreshMeta()) ?? emptyRefreshMeta();
    meta.lastSuccessfulRefresh = now;
    meta.lastKnownGoodRefresh = now;
    meta.lastResult = 'ok';
    meta.rejectReason = null;
    meta.lastCounts = {
      discovered: catalog.apps.length,
      apps: catalog.apps.length,
      metadataFailures: 0,
    };
    await this.env.CATALOG_KV.put(KV_KEYS.refreshMeta, JSON.stringify(meta));
    void prevLkg;
  }

  async getRefreshMeta(): Promise<RefreshMeta | null> {
    return this.env.CATALOG_KV.get<RefreshMeta>(KV_KEYS.refreshMeta, 'json');
  }

  async recordRejectedRefresh(
    reason: string,
    diagnostics: RefreshDiagnostics | null,
    prevAppCount: number,
    discovered: number
  ): Promise<void> {
    const meta = await this.getRefreshMeta();
    if (meta?.lastSuccessfulRefresh != null) {
      // Preserve the healthy history; only record this attempt's diagnostics.
      meta.lastRefreshAttempt = diagnostics?.attemptedAt ?? new Date().toISOString();
      meta.lastResult = 'rejected';
      meta.rejectReason = reason;
      meta.lastCounts = {
        discovered,
        apps: prevAppCount,
        metadataFailures: diagnostics?.metadataFailures.length ?? 0,
      };
      if (diagnostics) meta.lastDiagnostics = diagnostics;
      await this.env.CATALOG_KV.put(KV_KEYS.refreshMeta, JSON.stringify(meta));
      return;
    }
    const fresh = emptyRefreshMeta();
    fresh.lastRefreshAttempt = diagnostics?.attemptedAt ?? new Date().toISOString();
    fresh.lastResult = 'rejected';
    fresh.rejectReason = reason;
    fresh.lastCounts = {
      discovered,
      apps: prevAppCount,
      metadataFailures: diagnostics?.metadataFailures.length ?? 0,
    };
    if (diagnostics) fresh.lastDiagnostics = diagnostics;
    await this.env.CATALOG_KV.put(KV_KEYS.refreshMeta, JSON.stringify(fresh));
  }

  async recordRejectedRefreshLegacy(): Promise<void> {
    // Deprecated placeholder kept for API stability; use recordRejectedRefresh.
  }

  // ---------------- config ----------------

  async getConfig(): Promise<EngineConfig> {
    const stored = await this.env.CATALOG_KV.get<Partial<EngineConfig>>(KV_KEYS.config, 'json');
    if (!stored) return { ...DEFAULT_CONFIG };
    return sanitizeConfig({ ...DEFAULT_CONFIG, ...stored });
  }

  async putConfig(patch: Partial<EngineConfig>): Promise<EngineConfig> {
    const next = sanitizeConfig({ ...(await this.getConfig()), ...patch });
    await this.env.CATALOG_KV.put(KV_KEYS.config, JSON.stringify(next));
    return next;
  }

  async getAppConfig(pkg: string): Promise<AppConfig | null> {
    return this.env.CATALOG_KV.get<AppConfig>(KV_KEYS.appConfigPrefix + pkg, 'json');
  }

  async putAppConfig(pkg: string, cfg: AppConfig): Promise<void> {
    await this.env.CATALOG_KV.put(KV_KEYS.appConfigPrefix + pkg, JSON.stringify(cfg));
  }

  async getTargetConfig(pkg: string): Promise<TargetConfig | null> {
    return this.env.CATALOG_KV.get<TargetConfig>(KV_KEYS.targetConfigPrefix + pkg, 'json');
  }

  async putTargetConfig(pkg: string, cfg: TargetConfig): Promise<void> {
    await this.env.CATALOG_KV.put(KV_KEYS.targetConfigPrefix + pkg, JSON.stringify(cfg));
  }

  // ---------------- analytics ----------------

  async getDailyAggregate(day = dayKey()): Promise<DailyAggregate | null> {
    return mergeShards(await listShards(this.env, day));
  }

  /**
   * Merge events into the daily aggregate with hard caps (abuse containment).
   *
   * KV is last-write-wins per key, so concurrent /api/v1/events requests that
   * all wrote one shared 'daily:<day>' key would silently drop each other's
   * counters. Instead each request writes its own shard
   * ('daily:<day>:<shard-id>') and readers merge shards, making writes
   * conflict-free (crdt-style G-counter per field). shardId should be unique
   * per request; a random fallback keeps uncoordinated writers apart.
   */
  async applyEvents(
    events: NormalizedEvent[],
    day = dayKey(),
    shardId: string = 's' + Math.random().toString(36).slice(2, 10)
  ): Promise<DailyAggregate> {
    const agg = emptyAggregate();
    for (const e of events) {
      agg.totalEvents = Math.min(agg.totalEvents + 1, 2_000_000);
      const pairKey = `${e.targetPackage}|${e.sourcePackage}`;
      if (e.event === 'promo_impression') {
        bump(agg.impressions, pairKey, 5000);
        bump(agg.impressionsPlacement, e.placement, 5000);
        bump(agg.impressionsTarget, e.targetPackage, 10000);
      } else if (e.event === 'promo_click') {
        bump(agg.clicks, pairKey, 2000);
        bump(agg.clicksPlacement, e.placement, 2000);
        bump(agg.clicksTarget, e.targetPackage, 5000);
      }
      if (e.sdkVersion) bump(agg.sdk, e.sdkVersion, 20000);
      if (agg.firstTs === null || (e.ts ?? 0) < agg.firstTs) agg.firstTs = e.ts ?? null;
      if (agg.lastTs === null || (e.ts ?? 0) > agg.lastTs) agg.lastTs = e.ts ?? null;
    }
    await this.env.ANALYTICS_KV.put(
      dailyShardKey(day, shardId),
      JSON.stringify(agg),
      { expirationTtl: 60 * 60 * 24 * 40 }
    );
    return agg;
  }

  async listDailyKeys(): Promise<string[]> {
    const list = await this.env.ANALYTICS_KV.list({ prefix: 'daily:' });
    const days = new Set<string>();
    for (const k of list.keys) {
      // 'daily:<day>' or 'daily:<day>:<shard>' -> keep '<day>'.
      days.add(k.name.split(':')[1]);
    }
    return [...days].sort().reverse().slice(0, 30);
  }
}

export interface NormalizedEvent {
  event: 'promo_impression' | 'promo_click';
  sourcePackage: string;
  targetPackage: string;
  placement: string;
  ts: number | null;
  sdkVersion: string | null;
}

function bump(map: Record<string, number>, key: string, cap: number): void {
  const v = map[key] ?? 0;
  map[key] = Math.min(v + 1, cap);
}

export function sanitizeConfig(cfg: EngineConfig): EngineConfig {
  const clamp01 = (x: unknown, fallback: number) =>
    typeof x === 'number' && Number.isFinite(x) ? Math.min(1, Math.max(0, x)) : fallback;
  const positive = (x: unknown, fallback: number) =>
    typeof x === 'number' && Number.isFinite(x) && x > 0 ? x : fallback;
  const intIn = (x: unknown, min: number, max: number, fallback: number) =>
    typeof x === 'number' && Number.isFinite(x) ? Math.min(max, Math.max(min, Math.round(x))) : fallback;
  return {
    enabled: typeof cfg.enabled === 'boolean' ? cfg.enabled : DEFAULT_CONFIG.enabled,
    popularWeight: clamp01(cfg.popularWeight, DEFAULT_CONFIG.popularWeight),
    newAppBoostDays: intIn(cfg.newAppBoostDays, 0, 60, DEFAULT_CONFIG.newAppBoostDays),
    // Multiplier, not share: values above 1 are the point (default 1.8).
    newAppBoostMultiplier: positive(cfg.newAppBoostMultiplier, DEFAULT_CONFIG.newAppBoostMultiplier),
    defaultLimit: intIn(cfg.defaultLimit, 1, 10, DEFAULT_CONFIG.defaultLimit),
    maxLimit: intIn(cfg.maxLimit, 1, 10, DEFAULT_CONFIG.maxLimit),
    minKeepRatio: clamp01(cfg.minKeepRatio, DEFAULT_CONFIG.minKeepRatio),
    sessionRotationHours: intIn(cfg.sessionRotationHours, 1, 24, DEFAULT_CONFIG.sessionRotationHours),
    maxExclude: intIn(cfg.maxExclude, 0, 50, DEFAULT_CONFIG.maxExclude),
    maxRecent: intIn(cfg.maxRecent, 0, 50, DEFAULT_CONFIG.maxRecent),
  };
}

export function emptyRefreshMeta(): RefreshMeta {
  return {
    lastRefreshAttempt: null,
    lastSuccessfulRefresh: null,
    lastKnownGoodRefresh: null,
    lastResult: null,
    rejectReason: null,
    lastCounts: null,
    lastDiagnostics: null,
  };
}
