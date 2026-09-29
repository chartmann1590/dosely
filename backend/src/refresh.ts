/**
 * Catalog refresh pipeline.
 *
 * cron → discover packages → merge with previous catalog (firstDiscoveredAt,
 * remote flags) → enrich stale metadata (best effort, never fatal) →
 * safety validation → only then replace current + last-known-good.
 */
import { PlayStoreCatalogProvider, type MetadataPatch } from './discovery/PlayStoreCatalogProvider';
export type { MetadataPatch } from './discovery/PlayStoreCatalogProvider';
import type { Catalog, CatalogApp, Env, RefreshDiagnostics } from './types';
import { Store } from './store';

const METADATA_MAX_AGE_MS = 24 * 3_600_000; // details refresh: 12–24h window
const METADATA_REFRESH_BUDGET_MS = 20_000; // stay far under Workers CPU limits
const METADATA_BATCH = 8;

export interface RefreshOutcome {
  status: 'ok' | 'rejected' | 'error';
  reason: string | null;
  apps: number;
  discovered: number;
  added: string[];
  removed: string[];
  metadataFailures: number;
}

export async function refreshCatalog(env: Env, now = Date.now()): Promise<RefreshOutcome> {
  const started = Date.now();
  const store = new Store(env);
  const provider = new PlayStoreCatalogProvider(env.DEVELOPER_ID, env.DEVELOPER_PAGE_URL);
  const attemptedAt = new Date(now).toISOString();

  const prev = await store.getCurrentCatalog();
  const prevApps = prev?.apps ?? [];

  try {
    const discovery = await provider.discoverApps();

    if (discovery.apps.length === 0) {
      const reason = 'discovery returned 0 apps';
      await store.recordRejectedRefresh(reason, makeDiagnostics(), prevApps.length, 0);
      return { status: 'rejected', reason, apps: prevApps.length, discovered: 0, added: [], removed: [], metadataFailures: 0 };
    }

    // Suspicious-drop guard: fewer than minKeepRatio of the previous catalog.
    const config = await store.getConfig();
    if (prevApps.length > 0) {
      const kept = discovery.apps.filter((d) => prevApps.some((p) => p.packageName === d.packageName)).length;
      const ratio = kept / prevApps.length;
      if (ratio < config.minKeepRatio) {
        const reason = `suspicious catalog drop: kept ${kept}/${prevApps.length} (${(ratio * 100).toFixed(0)}% < ${(config.minKeepRatio * 100).toFixed(0)}%)`;
        await store.recordRejectedRefresh(reason, makeDiagnostics(), prevApps.length, discovery.apps.length);
        return {
          status: 'rejected',
          reason,
          apps: prevApps.length,
          discovered: discovery.apps.length,
          added: [],
          removed: [],
          metadataFailures: 0,
        };
      }
    }

    // Merge: carry forward firstDiscoveredAt + operator-managed flags.
    const prevByPkg = new Map(prevApps.map((p) => [p.packageName, p]));
    const nowIso = new Date(now).toISOString();
    const discoveredSet = new Set(discovery.apps.map((a) => a.packageName));
    const added = discovery.apps.filter((a) => !prevByPkg.has(a.packageName)).map((a) => a.packageName);
    const removed = prevApps.filter((p) => !discoveredSet.has(p.packageName)).map((p) => p.packageName);

    const merged: CatalogApp[] = discovery.apps.map((d) => {
      const prevApp = prevByPkg.get(d.packageName);
      return {
        packageName: d.packageName,
        name: d.name ?? prevApp?.name ?? null,
        iconUrl: d.iconUrl ?? prevApp?.iconUrl ?? null,
        storeUrl: d.storeUrl,
        shortDescription: prevApp?.shortDescription ?? null,
        fullDescription: prevApp?.fullDescription ?? null,
        rating: prevApp?.rating ?? null,
        ratingCount: prevApp?.ratingCount ?? null,
        installText: prevApp?.installText ?? null,
        estimatedMinimumInstalls: prevApp?.estimatedMinimumInstalls ?? null,
        estimatedMaximumInstalls: prevApp?.estimatedMaximumInstalls ?? null,
        category: prevApp?.category ?? null,
        priceText: prevApp?.priceText ?? null,
        isFree: prevApp?.isFree ?? null,
        developer: env.DEVELOPER_ID,
        developerPageUrl: env.DEVELOPER_PAGE_URL,
        version: prevApp?.version ?? null,
        updatedIso: prevApp?.updatedIso ?? null,
        contentRating: prevApp?.contentRating ?? null,
        firstDiscoveredAt: prevApp?.firstDiscoveredAt ?? nowIso,
        lastSeenAt: nowIso,
        lastMetadataRefresh: prevApp?.lastMetadataRefresh ?? null,
        // Default newly discovered apps to enabled.
        enabledForPromotion: prevApp?.enabledForPromotion ?? true,
        promotionMultiplier: prevApp?.promotionMultiplier ?? 1.0,
      };
    });

    // Metadata enrichment for stale entries — best effort, never fatal.
    const diagnostics: RefreshDiagnostics = {
      attemptedAt,
      durationMs: 0,
      source: 'public_play_store',
      discovered: discovery.apps.length,
      rejectedPackageIds: discovery.rejectedPackageIds,
      metadataFailures: [],
      keptStaleMetadata: [],
      removed,
      added,
    };

    const stale = merged
      .filter((a) => needsMetadataRefresh(a, now))
      .sort((a, b) => (a.lastMetadataRefresh ? Date.parse(a.lastMetadataRefresh) : 0) - (b.lastMetadataRefresh ? Date.parse(b.lastMetadataRefresh) : 0))
      .slice(0, 30);

    let elapsed = Date.now() - started;
    const priorMeta = new Map<string, MetadataPatch>();
    for (const app of stale) {
      if (elapsed > METADATA_REFRESH_BUDGET_MS || priorMeta.size >= METADATA_BATCH * 4) break;
      try {
        const patch = await provider.fetchAppMetadata(app.packageName);
        priorMeta.set(app.packageName, patch);
        applyPatch(app, patch, nowIso);
      } catch (err) {
        diagnostics.metadataFailures.push({
          packageName: app.packageName,
          reason: err instanceof Error ? err.message : 'unknown',
        });
        // Keep previous metadata; just refresh the timestamp so we don't hammer.
        app.lastMetadataRefresh = nowIso;
      }
      elapsed = Date.now() - started;
    }
    for (const app of merged) {
      if (!priorMeta.has(app.packageName) && needsMetadataRefresh(app, now)) {
        diagnostics.keptStaleMetadata.push(app.packageName);
      }
    }

    diagnostics.durationMs = Date.now() - started;

    // Never write a catalog where every app lacks name AND icon (parser breakage).
    const named = merged.filter((a) => a.name != null || a.iconUrl != null).length;
    if (named === 0) {
      const reason = 'refresh produced no usable app names/icons (likely HTML structure change)';
      await store.recordRejectedRefresh(reason, diagnostics, prevApps.length, discovery.apps.length);
      return {
        status: 'rejected',
        reason,
        apps: prevApps.length,
        discovered: discovery.apps.length,
        added,
        removed,
        metadataFailures: diagnostics.metadataFailures.length,
      };
    }

    const catalog: Catalog = {
      version: 1,
      generatedAt: nowIso,
      source: 'public_play_store',
      apps: merged,
    };
    await store.writeRefreshedCatalog(catalog);

    // Persist diagnostics in refresh meta.
    const meta = await store.getRefreshMeta();
    if (meta) {
      meta.lastDiagnostics = diagnostics;
      meta.lastCounts = {
        discovered: discovery.apps.length,
        apps: merged.length,
        metadataFailures: diagnostics.metadataFailures.length,
      };
      await env.CATALOG_KV.put('catalog:refresh-meta', JSON.stringify(meta));
    }

    return {
      status: 'ok',
      reason: null,
      apps: merged.length,
      discovered: discovery.apps.length,
      added,
      removed,
      metadataFailures: diagnostics.metadataFailures.length,
    };
  } catch (err) {
    const reason = err instanceof Error ? err.message : 'unknown refresh error';
    await store.recordRejectedRefresh(reason, null, prevApps.length, 0);
    return {
      status: 'error',
      reason,
      apps: prevApps.length,
      discovered: 0,
      added: [],
      removed: [],
      metadataFailures: 0,
    };
  }

  function makeDiagnostics(): RefreshDiagnostics {
    return {
      attemptedAt,
      durationMs: Date.now() - started,
      source: 'public_play_store',
      discovered: 0,
      rejectedPackageIds: [],
      metadataFailures: [],
      keptStaleMetadata: [],
      removed: [],
      added: [],
    };
  }
}

function needsMetadataRefresh(a: CatalogApp, now: number): boolean {
  if (!a.lastMetadataRefresh) return true;
  const t = Date.parse(a.lastMetadataRefresh);
  return !Number.isFinite(t) || now - t > METADATA_MAX_AGE_MS;
}

export function applyPatch(app: CatalogApp, patch: MetadataPatch, nowIso: string): void {
  if (patch.rating != null) app.rating = patch.rating;
  if (patch.ratingCount != null) app.ratingCount = patch.ratingCount;
  if (patch.installText != null) app.installText = patch.installText;
  if (patch.estimatedMinimumInstalls != null) app.estimatedMinimumInstalls = patch.estimatedMinimumInstalls;
  if (patch.estimatedMaximumInstalls != null) app.estimatedMaximumInstalls = patch.estimatedMaximumInstalls;
  if (patch.category != null) app.category = patch.category;
  if (patch.priceText != null) app.priceText = patch.priceText;
  if (patch.isFree != null) app.isFree = patch.isFree;
  if (patch.version != null) app.version = patch.version;
  if (patch.updatedIso != null) app.updatedIso = patch.updatedIso;
  if (patch.contentRating != null) app.contentRating = patch.contentRating;
  if (patch.iconUrl != null) app.iconUrl = patch.iconUrl;
  if (patch.shortDescription != null) app.shortDescription = patch.shortDescription;
  if (patch.fullDescription != null) app.fullDescription = patch.fullDescription;
  app.lastMetadataRefresh = nowIso;
}

/** Manual admin trigger for an immediate refresh. */
export async function manualRefresh(env: Env): Promise<RefreshOutcome> {
  return refreshCatalog(env);
}
