/**
 * Hartmann Studios Dynamic Cross-Promotion API — v1
 *
 * Endpoints:
 *   GET  /api/v1/health
 *   GET  /api/v1/catalog
 *   GET  /api/v1/recommendations
 *   POST /api/v1/events
 *   GET  /api/v1/admin/status        (admin token required)
 *   POST /api/v1/admin/refresh       (admin token required)
 *   POST /api/v1/admin/config        (admin token required)
 *   POST /api/v1/admin/app-config    (admin token required)
 *
 * Failure behavior: every handler returns well-formed JSON, never partial
 * garbage. Cross-promotion is never mission critical.
 */
import { recommend, toResponseApps, seededRandom } from './engine';
import { PlayStoreCatalogProvider } from './discovery/PlayStoreCatalogProvider';
import { refreshCatalog } from './refresh';
import { Store, sanitizeConfig } from './store';
import { validateEvent, validateRecommendations, MAX_EVENT_BYTES } from './validation';
import type { Env, TargetConfig } from './types';
import { DEFAULT_CONFIG } from './types';

const JSON_HEADERS = {
  'content-type': 'application/json; charset=utf-8',
  'cache-control': 'no-store',
};

function json(data: unknown, status = 200, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), { status, headers: { ...JSON_HEADERS, ...extraHeaders } });
}

function err(status: number, code: string, message: string): Response {
  return json({ error: { code, message } }, status);
}

/** Fixed-window per-IP rate limiter backed by the analytics KV. */
class RateLimiter {
  constructor(private readonly kv: KVNamespace | null) {}

  async check(key: string, limit: number, windowSeconds: number): Promise<boolean> {
    if (!this.kv) return true;
    const bucket = Math.floor(Date.now() / (windowSeconds * 1000));
    const k = `rl:${key}:${bucket}`;
    const cur = Number((await this.kv.get(k)) ?? '0');
    if (cur >= limit) return false;
    await this.kv.put(k, String(cur + 1), { expirationTtl: Math.max(windowSeconds * 2, 60) });
    return true;
  }
}

function clientIp(req: Request): string {
  return (
    req.headers.get('cf-connecting-ip') ??
    req.headers.get('x-forwarded-for')?.split(',')[0]?.trim() ??
    'unknown'
  );
}

function requestId(seedSalt: string): string {
  const rnd = seededRandom(seedSalt + Date.now().toString(36) + Math.random().toString(36).slice(2));
  const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789';
  let id = 'req_';
  for (let i = 0; i < 12; i++) id += alphabet[Math.floor(rnd() * alphabet.length)];
  return id;
}

function verifyAdmin(req: Request, env: Env): boolean {
  const header = req.headers.get('authorization') ?? '';
  const bearer = header.startsWith('Bearer ') ? header.slice(7) : '';
  const token = bearer || req.headers.get('x-admin-token') || '';
  if (token.length === 0) return false;
  return timingSafeEqual(token, env.ADMIN_TOKEN);
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// ---------------------------------------------------------------------------

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(req.url);
    const path = url.pathname;
    const store = new Store(env);
    const limiter = new RateLimiter(env.ANALYTICS_KV);

    try {
      if (!path.startsWith('/api/v1/')) {
        return err(404, 'not_found', 'unknown route (use /api/v1/*)');
      }

      // ---------- health ----------
      if (path === '/api/v1/health' && req.method === 'GET') {
        const { catalog, degraded } = await store.getServeCatalog();
        const meta = await store.getRefreshMeta();
        return json({
          status: catalog && catalog.apps.length > 0 ? 'ok' : 'degraded',
          catalogApps: catalog?.apps.length ?? 0,
          lastCatalogRefresh: meta?.lastRefreshAttempt ?? null,
          lastSuccessfulRefresh: meta?.lastSuccessfulRefresh ?? null,
          cacheStatus: degraded ? 'last-known-good' : catalog ? 'fresh' : 'empty',
        });
      }

      // ---------- catalog ----------
      if (path === '/api/v1/catalog' && req.method === 'GET') {
        if (!(await limiter.check(`catalog:${clientIp(req)}`, 30, 60))) {
          return err(429, 'rate_limited', 'too many requests');
        }
        const { catalog, degraded } = await store.getServeCatalog();
        if (!catalog) {
          // Valid empty response — clients must treat this as "nothing to show".
          return json({ version: 1, generatedAt: new Date().toISOString(), apps: [] });
        }
        return json(
          {
            version: 1,
            generatedAt: catalog.generatedAt,
            source: catalog.source,
            degraded,
            apps: catalog.apps.map((a) => ({
              packageName: a.packageName,
              name: a.name,
              iconUrl: a.iconUrl,
              storeUrl: a.storeUrl,
              shortDescription: a.shortDescription,
              rating: a.rating,
              ratingCount: a.ratingCount,
              installText: a.installText,
              estimatedMinimumInstalls: a.estimatedMinimumInstalls,
              category: a.category,
              priceText: a.priceText,
              isFree: a.isFree,
              developer: a.developer,
              enabledForPromotion: a.enabledForPromotion,
              firstDiscoveredAt: a.firstDiscoveredAt,
              lastMetadataRefresh: a.lastMetadataRefresh,
            })),
          },
          200,
          { 'cache-control': 'public, max-age=300' }
        );
      }

      // ---------- recommendations ----------
      if (path === '/api/v1/recommendations' && req.method === 'GET') {
        if (!(await limiter.check(`rec:${clientIp(req)}`, 60, 60))) {
          return err(429, 'rate_limited', 'too many requests');
        }
        const config = await store.getConfig();
        if (!config.enabled) {
          return json(emptyResponse());
        }
        const v = validateRecommendations(url, config.maxLimit, config.defaultLimit, config.maxExclude);
        if (!v.ok) return err(v.status, 'invalid_param', v.error);
        const p = v.params;

        const { catalog, degraded } = await store.getServeCatalog();
        if (!catalog || catalog.apps.length === 0) return json(emptyResponse());

        const appConfig = await store.getAppConfig(p.sourcePackage);
        if (appConfig?.enabled === false) return json(emptyResponse());
        if (appConfig?.placements && p.placement !== 'default' && !appConfig.placements.includes(p.placement)) {
          return json(emptyResponse());
        }

        const targetConfigs = new Map<string, TargetConfig>();
        await Promise.all(
          catalog.apps.map(async (a) => {
            const tc = await store.getTargetConfig(a.packageName);
            if (tc) targetConfigs.set(a.packageName, tc);
          })
        );

        const result = recommend({
          catalog,
          config,
          appConfig,
          targetConfigs,
          request: {
            sourcePackage: p.sourcePackage,
            placement: p.placement,
            limit: Math.min(p.limit, appConfig?.maxCards ?? config.maxLimit),
            sessionId: p.sessionId,
            exclude: p.exclude,
            locale: p.locale,
            now: Date.now(),
            seedSalt: p.placement,
          },
        });

        const rid = requestId(p.sourcePackage + p.placement + p.sessionId);
        const generatedAt = new Date().toISOString();
        const expiresAt = new Date(Date.now() + config.sessionRotationHours * 3_600_000).toISOString();
        return json(
          {
            version: 1,
            requestId: rid,
            generatedAt,
            expiresAt,
            apps: toResponseApps(result.apps),
          },
          200,
          degraded ? { 'x-promo-degraded': 'last-known-good' } : {}
        );
      }

      // ---------- events ----------
      if (path === '/api/v1/events' && req.method === 'POST') {
        if (!(await limiter.check(`events:${clientIp(req)}`, 120, 60))) {
          return err(429, 'rate_limited', 'too many requests');
        }
        const contentLength = Number(req.headers.get('content-length') ?? '0');
        if (contentLength > MAX_EVENT_BYTES) {
          return err(413, 'payload_too_large', 'event batch too large');
        }
        let body: unknown;
        try {
          body = await req.json();
        } catch {
          return err(400, 'invalid_json', 'body must be JSON');
        }
        const events: unknown[] = Array.isArray(body) ? body.slice(0, 20) : [body];

        const { catalog } = await store.getServeCatalog();
        const catalogPackages = new Set((catalog?.apps ?? []).map((a) => a.packageName));

        const accepted: string[] = [];
        const rejected: { index: number; error: string }[] = [];
        const normalized = [];
        for (let i = 0; i < events.length; i++) {
          const v = validateEvent(events[i], catalogPackages);
          if (v.ok) {
            accepted.push(`evt_${i}`);
            normalized.push({
              event: v.event.event as 'promo_impression' | 'promo_click',
              sourcePackage: v.event.sourcePackage,
              targetPackage: v.event.targetPackage,
              placement: v.event.placement,
              ts: v.event.ts ?? Date.now(),
              sdkVersion: v.event.sdkVersion ?? null,
            });
          } else {
            rejected.push({ index: i, error: v.error });
          }
        }
        if (normalized.length > 0) {
          await store.applyEvents(normalized);
        }
        return json({ accepted: accepted.length, rejected });
      }

      // ---------- admin ----------
      if (path.startsWith('/api/v1/admin/')) {
        if (!verifyAdmin(req, env)) {
          return err(401, 'unauthorized', 'admin token required');
        }
        if (path === '/api/v1/admin/status' && req.method === 'GET') {
          return json(await adminStatus(store));
        }
        if (path === '/api/v1/admin/refresh' && req.method === 'POST') {
          const outcome = await refreshCatalog(env);
          return json({ outcome });
        }
        if (path === '/api/v1/admin/config' && req.method === 'POST') {
          const body = (await req.json().catch(() => null)) as Record<string, unknown> | null;
          if (!body) return err(400, 'invalid_json', 'body must be a JSON object');
          const next = await store.putConfig(sanitizeConfig({ ...DEFAULT_CONFIG, ...(body as object) }));
          return json({ config: next });
        }
        if (path === '/api/v1/admin/app-config' && req.method === 'POST') {
          const body = (await req.json().catch(() => null)) as Record<string, unknown> | null;
          const pkg = typeof body?.['packageName'] === 'string' ? body['packageName'] : '';
          if (!pkg || pkg.length > 200) return err(400, 'invalid_param', 'packageName required');
          const scope = typeof body?.['scope'] === 'string' && body['scope'] === 'target' ? 'target' : 'source';
          const cfg = (body?.['config'] ?? {}) as Record<string, unknown>;
          if (scope === 'source') {
            await store.putAppConfig(pkg, cfg as never);
          } else {
            await store.putTargetConfig(pkg, cfg as never);
          }
          return json({ ok: true, scope, packageName: pkg, config: cfg });
        }
        return err(404, 'not_found', 'unknown admin route');
      }

      return err(404, 'not_found', 'unknown route');
    } catch (e) {
      console.error('unhandled', e instanceof Error ? e.message : e);
      return err(500, 'internal_error', 'internal error');
    }

    function emptyResponse() {
      return {
        version: 1,
        generatedAt: new Date().toISOString(),
        apps: [] as unknown[],
      };
    }
  },

  async scheduled(_event: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(
      refreshCatalog(env).then((outcome) => {
        console.log(
          JSON.stringify({
            lvl: 'info',
            msg: 'cron_refresh',
            status: outcome.status,
            reason: outcome.reason,
            apps: outcome.apps,
            discovered: outcome.discovered,
            added: outcome.added.length,
            removed: outcome.removed.length,
            metadataFailures: outcome.metadataFailures,
          })
        );
      })
    );
  },
};

// ---------------------------------------------------------------------------

async function adminStatus(store: Store) {
  const { catalog, degraded } = await store.getServeCatalog();
  const meta = await store.getRefreshMeta();
  const config = await store.getConfig();
  const days = await store.listDailyKeys();
  const totals = { impressions: 0, clicks: 0 };
  const perTarget: Record<string, { impressions: number; clicks: number }> = {};
  const perSource: Record<string, { clicks: number }> = {};
  const perPlacement: Record<string, { impressions: number; clicks: number }> = {};

  for (const day of days.slice(0, 7)) {
    const agg = await store.getDailyAggregate(day.slice('daily:'.length));
    if (!agg) continue;
    totals.impressions += Object.entries(agg.impressions).reduce((s, [, v]) => s + v, 0);
    totals.clicks += Object.entries(agg.clicks).reduce((s, [, v]) => s + v, 0);
    for (const [k, v] of Object.entries(agg.impressionsTarget)) {
      perTarget[k] ??= { impressions: 0, clicks: 0 };
      perTarget[k].impressions += v;
    }
    for (const [k, v] of Object.entries(agg.clicksTarget)) {
      perTarget[k] ??= { impressions: 0, clicks: 0 };
      perTarget[k].clicks += v;
    }
    for (const [pair, v] of Object.entries(agg.clicks)) {
      const src = pair.split('|')[1] ?? '?';
      perSource[src] ??= { clicks: 0 };
      perSource[src].clicks += v;
    }
    for (const [k, v] of Object.entries(agg.impressionsPlacement)) {
      perPlacement[k] ??= { impressions: 0, clicks: 0 };
      perPlacement[k].impressions += v;
    }
    for (const [k, v] of Object.entries(agg.clicksPlacement)) {
      perPlacement[k] ??= { impressions: 0, clicks: 0 };
      perPlacement[k].clicks += v;
    }
  }
  const ctr = totals.impressions > 0 ? totals.clicks / totals.impressions : 0;
  return {
    environment: 'served-by-worker',
    catalog: {
      apps: catalog?.apps.length ?? 0,
      degraded,
      generatedAt: catalog?.generatedAt ?? null,
      appsList:
        catalog?.apps.map((a) => ({
          packageName: a.packageName,
          name: a.name,
          enabled: a.enabledForPromotion,
          multiplier: a.promotionMultiplier,
          firstDiscoveredAt: a.firstDiscoveredAt,
          installs: a.installText,
          rating: a.rating,
        })) ?? [],
    },
    refresh: meta,
    config,
    analytics: {
      windowDays: 7,
      totals: { ...totals, ctr },
      perTarget,
      perSource,
      perPlacement,
    },
  };
}
