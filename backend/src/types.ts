/**
 * Internal data model for the Hartmann Studios cross-promotion platform.
 *
 * IMPORTANT: nothing in this system hardcodes the Hartmann app catalog.
 * Every app in a Catalog is discovered at runtime from Google Play.
 */

/** A single normalized Play Store app. Optional fields stay null when Google
 *  does not expose them — a missing field must never fail the catalog. */
export interface CatalogApp {
  packageName: string;
  name: string | null;
  iconUrl: string | null;
  storeUrl: string;
  shortDescription: string | null;
  fullDescription: string | null;
  /** 1.0 – 5.0, or null when the app has no ratings yet. */
  rating: number | null;
  ratingCount: number | null;
  /** Raw Google Play install bucket label, e.g. "100K+". */
  installText: string | null;
  estimatedMinimumInstalls: number | null;
  estimatedMaximumInstalls: number | null;
  category: string | null;
  priceText: string | null;
  isFree: boolean | null;
  developer: string | null;
  developerPageUrl: string | null;
  version: string | null;
  /** ISO 8601 "last updated on Play". */
  updatedIso: string | null;
  contentRating: string | null;
  /** ISO 8601. First time this package appeared in a refresh. */
  firstDiscoveredAt: string | null;
  /** ISO 8601. Last refresh in which this package was still listed. */
  lastSeenAt: string;
  lastMetadataRefresh: string | null;
  /** Remote kill-switch per app (backend-managed). Default true. */
  enabledForPromotion: boolean;
  /** Optional manual weighting override (backend-managed). Default 1.0. */
  promotionMultiplier: number;
}

export interface Catalog {
  version: 1;
  generatedAt: string;
  /** Which discovery source produced this catalog. */
  source: string;
  apps: CatalogApp[];
}

export interface RefreshDiagnostics {
  attemptedAt: string;
  durationMs: number;
  source: string;
  discovered: number;
  rejectedPackageIds: string[];
  metadataFailures: { packageName: string; reason: string }[];
  keptStaleMetadata: string[];
  removed: string[];
  added: string[];
}

export interface RefreshMeta {
  lastRefreshAttempt: string | null;
  lastSuccessfulRefresh: string | null;
  lastKnownGoodRefresh: string | null;
  lastResult: 'ok' | 'rejected' | 'error' | null;
  rejectReason: string | null;
  lastCounts: {
    discovered: number;
    apps: number;
    metadataFailures: number;
  } | null;
  lastDiagnostics: RefreshDiagnostics | null;
}

/** Runtime engine configuration. Stored in KV, remotely editable via admin API. */
export interface EngineConfig {
  enabled: boolean;
  /** Share of selections drawn from the popularity-weighted pool (0..1). */
  popularWeight: number;
  newAppBoostDays: number;
  newAppBoostMultiplier: number;
  defaultLimit: number;
  maxLimit: number;
  /** Safety: a refresh finding fewer than this ratio of the previous catalog is rejected. */
  minKeepRatio: number;
  /** Server-side rotation window: same session sees stable picks within one window. */
  sessionRotationHours: number;
  maxExclude: number;
  maxRecent: number;
}

export const DEFAULT_CONFIG: EngineConfig = {
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

/** Per-source-app configuration (key: config:app:<package>). */
export interface AppConfig {
  /** Master switch: disable cross-promotion inside one specific app. */
  enabled?: boolean;
  maxCards?: number;
  placements?: string[];
}

/** Per-target-app configuration (key: config:target:<package>). */
export interface TargetConfig {
  enabledForPromotion?: boolean;
  promotionMultiplier?: number;
  /** Never promote this app inside the listed source apps. */
  excludedFromSources?: string[];
}

/** Aggregated analytics counters for one UTC day (stored in ANALYTICS_KV). */
export interface DailyAggregate {
  /** "<target>|<source>" -> count */
  impressions: Record<string, number>;
  clicks: Record<string, number>;
  /** placement -> count */
  impressionsPlacement: Record<string, number>;
  clicksPlacement: Record<string, number>;
  /** target -> count (convenience rollup) */
  impressionsTarget: Record<string, number>;
  clicksTarget: Record<string, number>;
  /** sdkVersion -> event count */
  sdk: Record<string, number>;
  totalEvents: number;
  firstTs: number | null;
  lastTs: number | null;
}

export type SelectionType = 'popular' | 'random' | 'new_app_boost';

export interface Env {
  CATALOG_KV: KVNamespace;
  ANALYTICS_KV: KVNamespace;
  ADMIN_TOKEN: string;
  DEVELOPER_ID: string;
  DEVELOPER_PAGE_URL: string;
  ENVIRONMENT: string;
}
