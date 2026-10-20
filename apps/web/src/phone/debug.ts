/* Developer readout for the device gates (M-PHONE-2). It is shown on the phone only and never sent. */
export type Delegate = "GPU" | "CPU";
export type FrameSnapshot = {
  fps: number;
  inference_ms: number;
  delegate: Delegate;
  frames: number;
};
export function sensorOptions(search: string): {
  debug: boolean;
  delegate: Delegate;
} {
  const params = new URLSearchParams(search);
  return {
    debug: params.get("debug") === "1",
    delegate: params.get("delegate")?.toLowerCase() === "cpu" ? "CPU" : "GPU",
  };
}
/** Throughput from processed frame times over a rolling window; 1000 / inference time would overstate it. */
export class FrameStats {
  private recent: { timestamp: number; inferenceMs: number }[] = [];
  private total = 0;
  private delegate: Delegate = "GPU";
  constructor(private windowMs = 2000) {}
  record(timestamp: number, inferenceMs: number, delegate: Delegate): void {
    this.total++;
    this.delegate = delegate;
    this.recent.push({ timestamp, inferenceMs });
    while (this.recent[0].timestamp < timestamp - this.windowMs)
      this.recent.shift();
  }
  snapshot(): FrameSnapshot {
    const n = this.recent.length,
      span =
        n > 1 ? this.recent[n - 1].timestamp - this.recent[0].timestamp : 0;
    return {
      fps: span > 0 ? ((n - 1) * 1000) / span : 0,
      inference_ms: n
        ? this.recent.reduce((sum, f) => sum + f.inferenceMs, 0) / n
        : 0,
      delegate: this.delegate,
      frames: this.total,
    };
  }
}
export function debugText(
  s: FrameSnapshot,
  options: {
    requested: Delegate;
    width?: number;
    height?: number;
    replay?: boolean;
  },
): string {
  const fps = `${s.fps.toFixed(1)} fps`,
    frames = `${s.frames} frames`;
  if (options.replay)
    return ["REPLAY", "no pose model", fps, frames].join(" · ");
  const delegate =
    options.requested === "CPU"
      ? "CPU (forced)"
      : s.delegate === "CPU"
        ? "CPU (GPU unavailable)"
        : "GPU";
  return [
    "Lite model",
    delegate,
    fps,
    `${Math.round(s.inference_ms)} ms inference`,
    ...(options.width && options.height
      ? [`${options.width}×${options.height}`]
      : []),
    frames,
  ].join(" · ");
}
