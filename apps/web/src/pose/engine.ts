import type {
  RepEvent,
  HoldEvent,
} from "../../../../packages/contracts/generated";
import { rated } from "./checks";
import type { AttemptRejection, Catalog, Exercise, Observation } from "./types";
export class RepEngine {
  state: "TOP" | "DESCENDING" | "BOTTOM" | "ASCENDING" = "TOP";
  private samples: Observation[] = [];
  private min = 180;
  private max = 0;
  private extremumAt = 0;
  private index = 0;
  private expired = false;
  private expiredPartial: AttemptRejection | null = null;
  constructor(
    private exerciseId: string,
    private setId: string,
    private exercise: Exercise,
    private checks: Catalog["checks"],
    private onPartial: (reason: AttemptRejection) => void = () => {},
  ) {}
  private depth(): number {
    return Math.max(
      ...this.samples
        .filter(
          (s) => s.angle !== null && Number.isFinite(s.metrics.depth_ratio),
        )
        .map((s) => s.metrics.depth_ratio),
    );
  }
  private insufficient(): AttemptRejection | null {
    const m = this.exercise.machine!;
    if (this.exercise.family === "squat" && !Number.isFinite(this.depth()))
      return "uncalibrated";
    return (this.exercise.family !== "bridge" && this.min > m.minimum) ||
      (this.exercise.family === "squat" &&
        (m.min_depth_ratio == null || this.depth() < m.min_depth_ratio))
      ? "shallow"
      : null;
  }
  push(o: Observation): RepEvent | null {
    const m = this.exercise.machine;
    if (!m) return null;
    if (o.angle === null) {
      if (this.state !== "TOP" && !this.expired) {
        this.samples.push(o);
        if (o.timestamp - this.samples[0].timestamp > m.max_s * 1000) {
          this.expiredPartial =
            this.exercise.family === "squat" ? this.insufficient() : null;
          this.samples = [];
          this.expired = true;
        }
      }
      return null;
    }
    const bridge = this.exercise.family === "bridge";
    const value = o.angle;
    const home = bridge ? value <= m.minimum : value >= m.top;
    if (this.expired) {
      if (home) {
        this.expired = false;
        this.state = "TOP";
        if (this.expiredPartial) this.onPartial(this.expiredPartial);
        this.expiredPartial = null;
      }
      return null;
    }
    const entered = bridge ? value > m.enter : value < m.enter;
    if (this.state === "TOP") {
      if (entered) {
        this.state = "DESCENDING";
        this.samples = [o];
        this.min = value;
        this.max = value;
        this.extremumAt = o.timestamp;
      }
      return null;
    }
    this.samples.push(o);
    if (o.timestamp - this.samples[0].timestamp > m.max_s * 1000) {
      this.expiredPartial =
        this.exercise.family === "squat" ? this.insufficient() : null;
      this.samples = [];
      this.expired = !home;
      if (home) {
        this.state = "TOP";
        if (this.expiredPartial) this.onPartial(this.expiredPartial);
        this.expiredPartial = null;
      }
      return null;
    }
    if (value < this.min) {
      this.min = value;
      if (!bridge) this.extremumAt = o.timestamp;
    }
    if (value > this.max) {
      this.max = value;
      if (bridge) this.extremumAt = o.timestamp;
    }
    if (
      this.state === "DESCENDING" &&
      (bridge ? value >= m.top : value >= this.min + m.reversal)
    )
      this.state = "BOTTOM";
    if (
      this.state === "BOTTOM" &&
      (bridge ? value < m.enter : value > this.min + m.reversal)
    )
      this.state = "ASCENDING";
    if (!home) return null;
    const complete = this.state === "ASCENDING" || this.state === "BOTTOM";
    const duration = (o.timestamp - this.samples[0].timestamp) / 1000;
    this.state = "TOP";
    const depth = this.depth();
    const squat = this.exercise.family === "squat";
    const insufficient = this.insufficient();
    if (!complete || insufficient || duration < m.min_s || duration > m.max_s) {
      this.samples = [];
      if (squat && insufficient) this.onPartial(insufficient);
      return null;
    }
    const metrics: Record<string, number> = {};
    for (const key of this.exercise.metrics) {
      const values = this.samples.map((s) => s.metrics[key] ?? 0);
      if (key === "knee_min_deg" || key === "elbow_min_deg")
        metrics[key] = this.min;
      else if (key === "hip_ext_max_deg") metrics[key] = this.max;
      else if (key === "depth_ratio") metrics[key] = depth;
      else if (key === "descent_s")
        metrics[key] = (this.extremumAt - this.samples[0].timestamp) / 1000;
      else if (key === "hip_offset")
        metrics[key] = values.reduce(
          (a, b) => (Math.abs(a) > Math.abs(b) ? a : b),
          0,
        );
      else if (key === "top_hold_s") {
        let start: number | null = null,
          longest = 0;
        for (const s of this.samples) {
          if (s.angle !== null && s.angle >= m.top) {
            start ??= s.timestamp;
            longest = Math.max(longest, (s.timestamp - start) / 1000);
          } else start = null;
        }
        metrics[key] = longest;
      } else metrics[key] = Math.max(...values);
      metrics[key] = Math.round(metrics[key] * 1000) / 1000;
    }
    const event: RepEvent = {
      set_id: this.setId,
      rep_index: this.index++,
      exercise_id: this.exerciseId,
      rated: this.exercise.rated && rated(this.samples, this.checks),
      confidence:
        this.samples.reduce((s, x) => s + x.confidence, 0) /
        this.samples.length,
      metrics,
      duration_s: duration,
      client_ts_ms: Math.round(o.timestamp),
    };
    this.samples = [];
    return event;
  }
}
export class HoldEngine {
  private last: number | null = null;
  private aligned = 0;
  private total = 0;
  private samples: Observation[] = [];
  constructor(
    private exerciseId: string,
    private setId: string,
    private checks: Catalog["checks"],
    private alignment: Record<string, number>,
  ) {}
  push(o: Observation): void {
    const dt =
      this.last === null
        ? 0
        : Math.max(0, Math.min(0.2, (o.timestamp - this.last) / 1000));
    this.total += dt;
    const value =
      this.exerciseId === "bridge_hold"
        ? o.metrics.hip_ext_max_deg
        : o.metrics.hip_offset;
    const aligned =
      Number.isFinite(value) &&
      value >= this.alignment.minimum &&
      value <= this.alignment.maximum;
    if (aligned && rated([o], this.checks)) this.aligned += dt;
    this.last = o.timestamp;
    this.samples.push(o);
  }
  finish(): HoldEvent {
    return {
      set_id: this.setId,
      exercise_id: this.exerciseId,
      rated: rated(this.samples, this.checks),
      confidence: this.samples.length
        ? this.samples.reduce((s, o) => s + o.confidence, 0) /
          this.samples.length
        : 0,
      aligned_s: Math.min(60, this.aligned),
      total_s: Math.min(60, this.total),
    };
  }
}
