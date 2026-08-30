export type PerformanceSurface = "APP" | "FEED" | "DETAIL" | "CREATE_POST" | "MEDIA" | "BACKGROUND" | "UNKNOWN";
export type PerformanceOutcome = "SUCCESS" | "FAILURE" | "QUEUED" | "CANCELLED";
export type PerformanceUnit = "MILLISECOND" | "BYTE" | "COUNT" | "PERCENT";

export interface PerformanceEvent {
  name: string;
  value: number;
  unit: PerformanceUnit;
  surface: PerformanceSurface;
  outcome: PerformanceOutcome;
  recordedAtEpochMs: number;
  attributes: Record<string, string>;
  measurements: Record<string, number>;
}

/**
 * Browser adapter for the same Worker envelope used by KMP. localStorage is a
 * bounded L2 spool; fetch keepalive/sendBeacon preserve last-page samples.
 */
export class WebPerformanceTelemetry {
  private readonly sessionId = crypto.randomUUID();
  private readonly storageKey = "sdui-performance-outbox-v1";
  private readonly events: PerformanceEvent[];
  private flushTimer: number | undefined;
  private persistTimer: number | undefined;

  constructor(
    private readonly endpoint: string,
    private readonly appVersion: string,
    private readonly buildType: "debug" | "release" | "staging" = "release",
  ) {
    this.events = this.restore();
    addEventListener("pagehide", () => this.beaconFlush());
    if (this.events.length > 0) this.flushTimer = window.setTimeout(() => void this.flush(), 1_000);
  }

  record(event: Omit<PerformanceEvent, "recordedAtEpochMs">): void {
    if (!Number.isFinite(event.value) || event.value < 0) return;
    this.events.push({ ...event, recordedAtEpochMs: Date.now() });
    if (this.events.length > 1_000) this.events.splice(0, this.events.length - 1_000);
    this.schedulePersist();
    if (this.events.length >= 20) void this.flush();
    else this.flushTimer ??= window.setTimeout(() => void this.flush(), 5_000);
  }

  timer(
    name: string,
    surface: PerformanceSurface,
    attributes: Record<string, string> = {},
  ): () => void {
    const start = performance.now();
    return () => this.record({
      name,
      value: performance.now() - start,
      unit: "MILLISECOND",
      surface,
      outcome: "SUCCESS",
      attributes,
      measurements: {},
    });
  }

  /** LCP/INP/CLS follow the Core Web Vitals session/candidate definitions. */
  installWebVitals(): void {
    let lcp = 0;
    let cls = 0;
    let clsWindow = 0;
    let clsWindowStart = 0;
    let clsLastEntry = 0;
    const interactions = new Map<number, number>();
    const supported = new Set(PerformanceObserver.supportedEntryTypes ?? []);

    if (supported.has("largest-contentful-paint")) {
      new PerformanceObserver((list) => {
        for (const entry of list.getEntries()) lcp = Math.max(lcp, entry.startTime);
      }).observe({ type: "largest-contentful-paint", buffered: true });
    }
    if (supported.has("layout-shift")) {
      new PerformanceObserver((list) => {
        for (const entry of list.getEntries() as Array<PerformanceEntry & { value: number; hadRecentInput: boolean }>) {
          if (entry.hadRecentInput) continue;
          if (entry.startTime - clsLastEntry < 1_000 && entry.startTime - clsWindowStart < 5_000) {
            clsWindow += entry.value;
          } else {
            clsWindow = entry.value;
            clsWindowStart = entry.startTime;
          }
          clsLastEntry = entry.startTime;
          cls = Math.max(cls, clsWindow);
        }
      }).observe({ type: "layout-shift", buffered: true });
    }
    if (supported.has("event")) {
      new PerformanceObserver((list) => {
        for (const entry of list.getEntries() as Array<PerformanceEntry & { interactionId: number }>) {
          if (entry.interactionId <= 0) continue;
          interactions.set(
            entry.interactionId,
            Math.max(interactions.get(entry.interactionId) ?? 0, entry.duration),
          );
        }
      }).observe({ type: "event", buffered: true, durationThreshold: 40 } as PerformanceObserverInit);
    }

    addEventListener("visibilitychange", () => {
      if (document.visibilityState !== "hidden") return;
      if (lcp > 0) this.vital("largest_contentful_paint", lcp, "MILLISECOND");
      // INP ignores one worst interaction per 50 interactions to resist outliers.
      const candidates = [...interactions.values()].sort((a, b) => b - a);
      const inp = candidates[Math.min(Math.floor(candidates.length / 50), candidates.length - 1)];
      if (inp !== undefined) this.vital("interaction_to_next_paint", inp, "MILLISECOND");
      if (supported.has("layout-shift")) this.vital("cumulative_layout_shift", cls, "COUNT");
      lcp = 0; cls = 0; clsWindow = 0; clsWindowStart = 0; clsLastEntry = 0;
      interactions.clear();
    });
  }

  async flush(): Promise<void> {
    if (this.flushTimer !== undefined) clearTimeout(this.flushTimer);
    this.flushTimer = undefined;
    const batch = this.events.splice(0, 50);
    if (batch.length === 0) return;
    this.persist();
    try {
      const response = await fetch(this.endpoint, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: this.body(batch),
        credentials: "omit",
        keepalive: true,
      });
      if (!response.ok) throw new Error(`telemetry HTTP ${response.status}`);
      if (this.events.length > 0) this.flushTimer = window.setTimeout(() => void this.flush(), 0);
    } catch {
      this.events.unshift(...batch);
      this.persist();
      this.flushTimer = window.setTimeout(() => void this.flush(), 30_000);
    }
  }

  private vital(name: string, value: number, unit: PerformanceUnit): void {
    this.record({ name, value, unit, surface: "APP", outcome: "SUCCESS", attributes: {}, measurements: {} });
  }

  private body(events: PerformanceEvent[]): string {
    return JSON.stringify({
      schemaVersion: 1,
      platform: "web",
      appVersion: this.appVersion,
      buildType: this.buildType,
      sessionId: this.sessionId,
      events,
    });
  }

  private beaconFlush(): void {
    const batch = this.events.slice(0, 50);
    if (batch.length === 0) return;
    const accepted = navigator.sendBeacon(
      this.endpoint,
      // text/plain is CORS-safelisted, avoiding an unload-time preflight. The
      // Worker still parses and validates the JSON body strictly.
      new Blob([this.body(batch)], { type: "text/plain;charset=UTF-8" }),
    );
    if (accepted) {
      this.events.splice(0, batch.length);
      this.persist();
    }
  }

  private restore(): PerformanceEvent[] {
    try {
      const parsed = JSON.parse(localStorage.getItem(this.storageKey) ?? "[]") as unknown;
      return Array.isArray(parsed) ? parsed.slice(-1_000) as PerformanceEvent[] : [];
    } catch { return []; }
  }

  private persist(): void {
    try { localStorage.setItem(this.storageKey, JSON.stringify(this.events)); } catch { /* best effort */ }
  }

  private schedulePersist(): void {
    if (this.persistTimer !== undefined) return;
    this.persistTimer = window.setTimeout(() => {
      this.persistTimer = undefined;
      this.persist();
    }, 0);
  }
}
