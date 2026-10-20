import type { CoachEvent } from "../../../../packages/contracts/generated";

type CoachView = {
  phase: string;
  paused: boolean;
  active_set: { set_id: string } | null;
};

/** One independent best-effort request, with only the latest waiting notice retained. */
export class CoachSender {
  private pending: CoachEvent | null = null;
  private sending = false;
  private setId = "";
  private last: Partial<Record<CoachEvent["kind"], number>> = {};
  constructor(
    private readonly send: (event: CoachEvent) => Promise<unknown>,
    private readonly view: () => CoachView | null,
    private readonly now = Date.now,
  ) {}
  private active(): string | null {
    const view = this.view();
    return view?.phase === "IN_SET" && !view.paused
      ? (view.active_set?.set_id ?? null)
      : null;
  }
  show(kind: CoachEvent["kind"]): void {
    const set_id = this.active();
    if (!set_id) return;
    this.pending = { set_id, kind, client_ts_ms: Math.round(this.now()) };
    void this.flush();
  }
  clear(): void {
    this.pending = null;
  }
  private async flush(): Promise<void> {
    if (this.sending) return;
    const event = this.pending;
    this.pending = null;
    if (!event || this.active() !== event.set_id) return;
    if (event.set_id !== this.setId) {
      this.setId = event.set_id;
      this.last = {};
    }
    const now = this.now();
    if (now - (this.last[event.kind] ?? -Infinity) < 1500) return;
    this.last[event.kind] = now;
    this.sending = true;
    try {
      await this.send(event);
    } catch {
      // The local notice remains available; delivery must never hold up reps.
    } finally {
      this.sending = false;
      void this.flush();
    }
  }
}
