import type { Point } from "./types";
export const distance = (a: Point, b: Point): number =>
  Math.hypot(a.x - b.x, a.y - b.y);
export function angle(a: Point, b: Point, c: Point): number | null {
  const ab = distance(a, b),
    cb = distance(c, b);
  if (ab < 1e-8 || cb < 1e-8) return null;
  return (
    (Math.acos(
      Math.max(
        -1,
        Math.min(
          1,
          ((a.x - b.x) * (c.x - b.x) + (a.y - b.y) * (c.y - b.y)) / (ab * cb),
        ),
      ),
    ) *
      180) /
    Math.PI
  );
}
export function depthRatio(hip: Point, knee: Point, thigh: number): number {
  return thigh > 1e-8 ? (hip.y - knee.y) / thigh : 0;
}
export function trunkLean(hip: Point, shoulder: Point): number {
  return (
    (Math.atan2(Math.abs(shoulder.x - hip.x), hip.y - shoulder.y) * 180) /
    Math.PI
  );
}
export function hipOffset(shoulder: Point, hip: Point, ankle: Point): number {
  const dx = ankle.x - shoulder.x,
    dy = ankle.y - shoulder.y,
    length2 = dx * dx + dy * dy;
  if (length2 < 1e-8) return 0;
  // Orient the normal toward positive image y, independent of camera mirroring.
  const sign = dx < 0 ? -1 : 1;
  return (
    (sign * (dx * (hip.y - shoulder.y) - dy * (hip.x - shoulder.x))) / length2
  );
}
export class NearSide {
  private side: 0 | 1 = 0;
  private streak = 0;
  private locked = false;
  choose(points: Point[]): 0 | 1 {
    if (this.locked) return this.side;
    const score = (offset: number) =>
      [11, 23, 25, 27].reduce(
        (s, i) => s + (points[i + offset]?.visibility ?? 0),
        0,
      );
    const winner: 0 | 1 = score(1) > score(0) ? 1 : 0;
    this.streak = winner === this.side ? this.streak + 1 : 1;
    this.side = winner;
    this.locked = this.streak >= 10;
    return this.side;
  }
}
