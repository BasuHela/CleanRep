import type { Point } from "./types";
const alpha = (cutoff: number, dt: number) =>
  1 / (1 + 1 / (2 * Math.PI * cutoff * dt));
export class OneEuro {
  private last?: number;
  private filtered = 0;
  private derivative = 0;
  private timestamp?: number;
  constructor(
    private minCutoff = 1,
    private beta = 0.01,
    private dCutoff = 1,
  ) {}
  push(value: number, timeMs: number): number {
    if (this.last === undefined || this.timestamp === undefined) {
      this.last = this.filtered = value;
      this.timestamp = timeMs;
      return value;
    }
    const dt = Math.max(0.001, Math.min(1, (timeMs - this.timestamp) / 1000));
    const raw = (value - this.last) / dt;
    this.derivative += alpha(this.dCutoff, dt) * (raw - this.derivative);
    this.filtered +=
      alpha(this.minCutoff + this.beta * Math.abs(this.derivative), dt) *
      (value - this.filtered);
    this.last = value;
    this.timestamp = timeMs;
    return this.filtered;
  }
}
export class PoseFilter {
  private filters: OneEuro[][];
  constructor(min = 1, beta = 0.01, d = 1) {
    this.filters = Array.from({ length: 33 }, () =>
      Array.from({ length: 3 }, () => new OneEuro(min, beta, d)),
    );
  }
  push(points: Point[], timestamp: number): Point[] {
    return points.map((p, i) => ({
      x: this.filters[i][0].push(p.x, timestamp),
      y: this.filters[i][1].push(p.y, timestamp),
      z: this.filters[i][2].push(p.z, timestamp),
      visibility: p.visibility,
    }));
  }
}
