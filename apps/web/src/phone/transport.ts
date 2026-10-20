import catalog from "../../../../packages/contracts/catalog.public.json";
import { ApiError } from "../shared/api";
import type {
  RepEvent,
  HoldEvent,
} from "../../../../packages/contracts/generated";
export type SensorEvent = RepEvent | HoldEvent;
/** Discard invalid measurements, drop an ended set, stop for a missing session, otherwise retry. */
export function sendFailure(
  code: string,
): "discard" | "drop" | "end" | "retry" {
  if (["invalid_request", "invalid_metric", "private_payload"].includes(code))
    return "discard";
  if (code === "set_not_active") return "drop";
  return ["unauthorized", "not_found", "session_expired"].includes(code)
    ? "end"
    : "retry";
}
export function allowedPayload(event: SensorEvent): SensorEvent {
  const common = {
    set_id: event.set_id,
    exercise_id: event.exercise_id,
    rated: event.rated,
    confidence: event.confidence,
  };
  if ("rep_index" in event) {
    const exercise =
      catalog.exercises[event.exercise_id as keyof typeof catalog.exercises];
    if (!exercise) throw new Error("Unknown exercise");
    const metrics = Object.fromEntries(
      exercise.metrics.map((k) => [k, event.metrics[k]]),
    );
    return {
      ...common,
      rep_index: event.rep_index,
      metrics,
      duration_s: event.duration_s,
      client_ts_ms: event.client_ts_ms,
    };
  }
  return { ...common, aligned_s: event.aligned_s, total_s: event.total_s };
}
export class RepTransport {
  private queue: SensorEvent[] = [];
  private sending = false;
  private stopped = false;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private retries = 0;
  constructor(
    private send: (event: SensorEvent) => Promise<void>,
    private notice: (text: string) => void = () => {},
    private sleep = (fn: () => void, ms: number) => setTimeout(fn, ms),
  ) {}
  get pending(): number {
    return this.queue.length;
  }
  enqueue(event: SensorEvent): boolean {
    if (this.stopped) return false;
    if (this.queue.length >= 30) {
      this.notice(
        "Too many reps waiting to send. Tap Stop camera, check the Wi-Fi, then start again.",
      );
      return false;
    }
    this.queue.push(allowedPayload(event));
    if (!this.timer) void this.flush();
    return true;
  }
  async flush(): Promise<void> {
    if (this.sending || this.stopped || !this.queue.length) return;
    this.sending = true;
    clearTimeout(this.timer);
    this.timer = undefined;
    try {
      await this.send(this.queue[0]);
      if (this.stopped) return;
      this.queue.shift();
      if (this.retries)
        this.notice("Connected again. Saved reps are being sent.");
      this.retries = 0;
    } catch (err) {
      if (this.stopped) return;
      if (err instanceof ApiError && sendFailure(err.code) === "discard") {
        this.queue.shift();
        this.retries = 0;
        this.notice("One rep couldn't be saved and was skipped.");
      } else {
        this.retries++;
        // Covers both a lost connection and the server's rate limit; either way the rep is retried.
        this.notice(
          "Reps couldn't be sent yet. They're kept on this phone and sent again shortly.",
        );
      }
    } finally {
      this.sending = false;
      if (!this.stopped && this.queue.length)
        this.timer = this.sleep(
          () => void this.flush(),
          this.retries
            ? Math.min(8000, 500 * 2 ** Math.min(this.retries, 4))
            : 260,
        );
    }
  }
  stop(): void {
    this.stopped = true;
    this.queue = [];
    clearTimeout(this.timer);
  }
}
