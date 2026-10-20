export type Point = { x: number; y: number; z: number; visibility: number };
export type AttemptRejection = "shallow" | "uncalibrated";
/** `aspect` is the processed image's width / height; landmarks are normalized per axis, so geometry needs it. Missing means 1. */
export type Frame = {
  timestamp: number;
  landmarks: Point[];
  luma: number;
  aspect?: number;
};
export type MachineConfig = {
  top: number;
  enter: number;
  minimum: number;
  reversal: number;
  min_s: number;
  max_s: number;
  min_depth_ratio?: number | null;
};
export type Exercise = {
  name: string;
  cue: string;
  family: "squat" | "pushup" | "bridge" | "hold";
  metrics: string[];
  machine: MachineConfig | null;
  rated: boolean;
  alignment: Record<string, number>;
  rep_seconds: number;
};
export type Catalog = {
  exercises: Record<string, Exercise>;
  checks: Record<string, number>;
  smoothing: { min_cutoff: number; beta: number; d_cutoff: number };
};
export type Observation = {
  timestamp: number;
  angle: number | null;
  metrics: Record<string, number>;
  confidence: number;
  full_body: boolean;
  side_view: boolean;
  light: boolean;
};
