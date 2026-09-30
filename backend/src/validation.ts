/**
 * Input validation for all public endpoints. Never trust client values.
 */
export interface RecQueryParams {
  sourcePackage: string;
  placement: string;
  limit: number;
  sessionId: string | null;
  /** Client variety seed; the server nonce-fills when absent. */
  seed: string | null;
  exclude: string[];
  locale: string | null;
}

const PKG_RE = /^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$/i;
const PLACEMENT_RE = /^[a-z0-9_-]{1,40}$/;
const SESSION_RE = /^[A-Za-z0-9_-]{1,64}$/;
const LOCALE_RE = /^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8}){0,2}$/;

export function validateRecommendations(url: URL, maxLimit: number, defaultLimit: number, maxExclude: number): { ok: true; params: RecQueryParams } | { ok: false; error: string; status: number } {
  const sp = url.searchParams.get('sourcePackage');
  if (!sp) return { ok: false, error: 'sourcePackage is required', status: 400 };
  if (sp.length > 200 || !PKG_RE.test(sp)) return { ok: false, error: 'sourcePackage invalid', status: 400 };

  const placement = (url.searchParams.get('placement') ?? 'default').slice(0, 40);
  if (!PLACEMENT_RE.test(placement)) return { ok: false, error: 'placement invalid', status: 400 };

  const limitRaw = url.searchParams.get('limit');
  let limit = defaultLimit;
  if (limitRaw != null) {
    const n = Number.parseInt(limitRaw, 10);
    if (!Number.isFinite(n) || n < 1 || `${n}` !== limitRaw.trim()) {
      return { ok: false, error: 'limit invalid', status: 400 };
    }
    limit = Math.min(n, maxLimit);
  }

  let sessionId: string | null = url.searchParams.get('sessionId');
  if (sessionId != null) {
    sessionId = sessionId.slice(0, 64);
    if (!SESSION_RE.test(sessionId)) sessionId = null; // tolerate junk sessions
  }

  // Optional client seed for pick variety; validated like sessionId.
  let seed: string | null = url.searchParams.get('seed');
  if (seed != null) {
    seed = seed.slice(0, 64);
    if (!SESSION_RE.test(seed)) seed = null;
  }

  const excludeRaw = url.searchParams.get('exclude') ?? '';
  const exclude = excludeRaw
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0 && s.length <= 200 && PKG_RE.test(s))
    .slice(0, maxExclude);

  const localeRaw = url.searchParams.get('locale');
  let locale: string | null = localeRaw;
  if (locale != null && (locale.length > 35 || !LOCALE_RE.test(locale))) locale = null;

  return { ok: true, params: { sourcePackage: sp, placement, limit, sessionId, seed, exclude, locale } };
}

const MAX_EVENT_BYTES = 8 * 1024;

export interface IncomingEvent {
  event: string;
  sourcePackage: string;
  targetPackage: string;
  placement: string;
  rankPosition?: number;
  selectionType?: string;
  sessionId?: string;
  recommendationRequestId?: string;
  sdkVersion?: string;
  ts?: number;
}

/** Validate a single event object against catalog membership and schema. */
export function validateEvent(
  raw: unknown,
  catalogPackages: Set<string>
): { ok: true; event: IncomingEvent } | { ok: false; error: string } {
  if (!raw || typeof raw !== 'object') return { ok: false, error: 'event must be object' };
  const e = raw as Record<string, unknown>;

  const event = typeof e.event === 'string' ? e.event : '';
  if (event !== 'promo_impression' && event !== 'promo_click') return { ok: false, error: 'event type invalid' };

  const sourcePackage = typeof e.sourcePackage === 'string' ? e.sourcePackage : '';
  const targetPackage = typeof e.targetPackage === 'string' ? e.targetPackage : '';
  if (!PKG_RE.test(sourcePackage) || sourcePackage.length > 200) return { ok: false, error: 'sourcePackage invalid' };
  if (!PKG_RE.test(targetPackage) || targetPackage.length > 200) return { ok: false, error: 'targetPackage invalid' };
  // Bot/spam protection: both packages must be Hartmann catalog members.
  if (!catalogPackages.has(sourcePackage)) return { ok: false, error: 'sourcePackage not in catalog' };
  if (!catalogPackages.has(targetPackage)) return { ok: false, error: 'targetPackage not in catalog' };

  const placement = typeof e.placement === 'string' ? e.placement.slice(0, 40) : '';
  if (!PLACEMENT_RE.test(placement)) return { ok: false, error: 'placement invalid' };

  const sessionId = typeof e.sessionId === 'string' ? e.sessionId.slice(0, 64) : undefined;
  if (sessionId != null && sessionId.length > 0 && !SESSION_RE.test(sessionId)) return { ok: false, error: 'sessionId invalid' };

  const recommendationRequestId =
    typeof e.recommendationRequestId === 'string' ? e.recommendationRequestId.slice(0, 64) : undefined;
  if (recommendationRequestId != null && recommendationRequestId.length > 0 && !SESSION_RE.test(recommendationRequestId)) {
    return { ok: false, error: 'recommendationRequestId invalid' };
  }

  let rankPosition: number | undefined;
  if (e.rankPosition != null) {
    if (typeof e.rankPosition !== 'number' || !Number.isFinite(e.rankPosition) || e.rankPosition < 1 || e.rankPosition > 20) {
      return { ok: false, error: 'rankPosition invalid' };
    }
    rankPosition = Math.round(e.rankPosition);
  }

  const selectionType = typeof e.selectionType === 'string' ? e.selectionType.slice(0, 24) : undefined;
  if (selectionType != null && !['popular', 'random', 'new_app_boost'].includes(selectionType)) {
    return { ok: false, error: 'selectionType invalid' };
  }

  const sdkVersion = typeof e.sdkVersion === 'string' ? e.sdkVersion.slice(0, 24) : undefined;

  let ts: number | undefined;
  if (e.ts != null) {
    if (typeof e.ts !== 'number' || !Number.isFinite(e.ts)) return { ok: false, error: 'ts invalid' };
    const now = Date.now();
    // Reject timestamps outside a 48h-back / 1min-forward window (replay/spam guard).
    if (e.ts < now - 48 * 3600_000 || e.ts > now + 60_000) return { ok: false, error: 'ts out of range' };
    ts = Math.round(e.ts);
  }

  return { ok: true, event: { event, sourcePackage, targetPackage, placement, rankPosition, selectionType, sessionId, recommendationRequestId, sdkVersion, ts } };
}

export { MAX_EVENT_BYTES, PKG_RE, PLACEMENT_RE, SESSION_RE };
