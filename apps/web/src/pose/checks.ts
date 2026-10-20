import { distance } from "./geometry";
import type { Catalog, Point } from "./types";
export type Checks = {
  full_body: boolean;
  side_view: boolean;
  light: boolean;
  confidence: number;
};
export function frameChecks(
  points: Point[],
  family: string,
  side: number,
  luma: number,
  c: Catalog["checks"],
  aspect = 1,
): Checks {
  const indices = (
    family === "pushup" || family === "hold"
      ? [11, 13, 15, 23, 27]
      : [11, 23, 25, 27]
  ).map((i) => i + side);
  const required = indices.map((i) => points[i]);
  // Framing uses normalized coordinates; the side-view ratio needs true (width/height-scaled) lengths.
  const square = (p: Point): Point => ({ ...p, x: p.x * aspect });
  const torso = distance(square(points[23 + side]), square(points[11 + side]));
  return {
    confidence: Math.min(...required.map((p) => p.visibility)),
    full_body: required.every(
      (p) =>
        p.visibility >= c.visibility &&
        p.x >= c.margin &&
        p.x <= 1 - c.margin &&
        p.y >= c.margin &&
        p.y <= 1 - c.margin,
    ),
    side_view:
      torso > 1e-6 &&
      (family === "bridge"
        ? Math.abs(points[23].y - points[24].y)
        : Math.abs(points[11].x - points[12].x) * aspect) /
        torso <
        c.side_ratio,
    light: luma >= c.luma_min && luma <= c.luma_max,
  };
}
export class CheckWindow {
  private values: Checks[] = [];
  constructor(private c: Catalog["checks"]) {}
  push(check: Checks): Checks {
    this.values.push(check);
    if (this.values.length > this.c.window) this.values.shift();
    const ratio = (key: "full_body" | "side_view" | "light") =>
      this.values.filter((v) => v[key]).length / this.values.length;
    const ready = this.values.length >= this.c.window;
    return {
      full_body: ready && ratio("full_body") >= this.c.full_body_rate,
      side_view: ready && ratio("side_view") >= this.c.side_rate,
      light: ready && ratio("light") >= this.c.light_rate,
      confidence: check.confidence,
    };
  }
}
export function rated(observations: Checks[], c: Catalog["checks"]): boolean {
  return (
    observations.length > 0 &&
    observations.reduce((s, o) => s + o.confidence, 0) / observations.length >=
      c.rating_confidence &&
    observations.filter((o) => o.side_view).length / observations.length >=
      c.side_rate &&
    observations.filter((o) => o.full_body).length / observations.length >=
      c.full_body_rate
  );
}
