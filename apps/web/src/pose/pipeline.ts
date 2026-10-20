import { frameChecks, CheckWindow } from "./checks";
import { RepEngine, HoldEngine } from "./engine";
import { PoseFilter } from "./filter";
import {
  angle,
  depthRatio,
  distance,
  hipOffset,
  NearSide,
  trunkLean,
} from "./geometry";
import type {
  AttemptRejection,
  Catalog,
  Frame,
  Observation,
  Point,
} from "./types";
import type {
  RepEvent,
  HoldEvent,
} from "../../../../packages/contracts/generated";
export class PosePipeline {
  private filter: PoseFilter;
  private side = new NearSide();
  private thigh: number[] = [];
  private reps = 0;
  private engine: RepEngine;
  private hold: HoldEngine;
  readonly checks: CheckWindow;
  private lastTimestamp = -1;
  constructor(
    readonly catalog: Catalog,
    readonly exerciseId: string,
    readonly setId: string,
    private onRep: (event: RepEvent) => void,
    onPartial: (reason: AttemptRejection) => void = () => {},
  ) {
    const s = catalog.smoothing;
    this.filter = new PoseFilter(s.min_cutoff, s.beta, s.d_cutoff);
    this.engine = new RepEngine(
      exerciseId,
      setId,
      catalog.exercises[exerciseId],
      catalog.checks,
      onPartial,
    );
    this.hold = new HoldEngine(
      exerciseId,
      setId,
      catalog.checks,
      catalog.exercises[exerciseId].alignment,
    );
    this.checks = new CheckWindow(catalog.checks);
  }
  /** Null only for a stale timestamp. A frame with no person still returns the check window, with `points: null`. */
  push(frame: Frame): {
    points: Point[] | null;
    observation: Observation;
    checks: ReturnType<CheckWindow["push"]>;
  } | null {
    if (frame.timestamp <= this.lastTimestamp) return null;
    this.lastTimestamp = frame.timestamp;
    if (
      frame.landmarks.length !== 33 ||
      frame.landmarks.some(
        (p) => ![p.x, p.y, p.z, p.visibility].every(Number.isFinite),
      )
    ) {
      const missing: Observation = {
        timestamp: frame.timestamp,
        angle: null,
        metrics: {},
        confidence: 0,
        full_body: false,
        side_view: false,
        light: false,
      };
      this.engine.push(missing);
      this.hold.push(missing);
      return {
        points: null,
        observation: missing,
        checks: this.checks.push(missing),
      };
    }
    const points = this.filter.push(frame.landmarks, frame.timestamp),
      side = this.side.choose(points);
    const ex = this.catalog.exercises[this.exerciseId];
    // Landmarks are normalized per axis; scale x by width/height so angles and ratios are true.
    const aspect =
      frame.aspect !== undefined &&
      Number.isFinite(frame.aspect) &&
      frame.aspect > 0
        ? frame.aspect
        : 1;
    const square = (p: Point): Point => ({ ...p, x: p.x * aspect });
    const shoulder = square(points[11 + side]),
      elbow = square(points[13 + side]),
      wrist = square(points[15 + side]),
      hip = square(points[23 + side]),
      knee = square(points[25 + side]),
      ankle = square(points[27 + side]);
    const kneeAngle = angle(hip, knee, ankle),
      elbowAngle = angle(shoulder, elbow, wrist),
      hipAngle = angle(shoulder, hip, knee);
    const check = frameChecks(
      points,
      ex.family,
      side,
      frame.luma,
      this.catalog.checks,
      aspect,
    );
    if (
      this.reps < 2 &&
      this.engine.state === "TOP" &&
      [hip, knee, ankle].every(
        (p) => p.visibility >= this.catalog.checks.calibration_visibility,
      ) &&
      distance(hip, knee) > 1e-8 &&
      (kneeAngle ?? 0) >= (ex.machine?.top ?? 160)
    ) {
      this.thigh.push(distance(hip, knee));
      if (this.thigh.length > 60) this.thigh.shift();
    }
    const sorted = [...this.thigh].sort((a, b) => a - b);
    const thigh = sorted.length ? sorted[Math.floor(sorted.length / 2)] : 0;
    const metrics = {
      knee_min_deg: kneeAngle ?? 180,
      ...(thigh > 0 ? { depth_ratio: depthRatio(hip, knee, thigh) } : {}),
      trunk_lean_deg: trunkLean(hip, shoulder),
      descent_s: 0,
      elbow_min_deg: elbowAngle ?? 180,
      hip_offset: hipOffset(shoulder, hip, ankle),
      hip_ext_max_deg: hipAngle ?? 0,
      top_hold_s: 0,
    };
    const observation: Observation = {
      timestamp: frame.timestamp,
      angle:
        ex.family === "pushup"
          ? elbowAngle
          : ex.family === "bridge"
            ? hipAngle
            : kneeAngle,
      metrics,
      ...check,
    };
    if (ex.family === "hold") this.hold.push(observation);
    else {
      const event = this.engine.push(observation);
      if (event) {
        this.reps++;
        this.onRep(event);
      }
    }
    return { points, observation, checks: this.checks.push(check) };
  }
  finishHold(): HoldEvent {
    return this.hold.finish();
  }
}
