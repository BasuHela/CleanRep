import { PosePipeline } from "../apps/web/src/pose/pipeline";
import type { Catalog, Frame } from "../apps/web/src/pose/types";
import type { RepEvent } from "../packages/contracts/generated";

export type AttemptLabel = {
  start_ms: number;
  end_ms: number;
  counted: boolean;
  affected: boolean;
  faults: string[];
};
export function labelForEvent(
  labels: AttemptLabel[],
  timestamp: number,
): AttemptLabel | null {
  return (
    labels.find(
      (label) => timestamp >= label.start_ms && timestamp <= label.end_ms,
    ) ?? null
  );
}
/** Both synthetic fixtures and device exports run the production pipeline unchanged. */
export function replay(
  catalog: Catalog,
  exercise: string,
  fixture: { frames: Frame[]; labels?: AttemptLabel[] },
) {
  const events: RepEvent[] = [];
  let partials = 0;
  const pipeline = new PosePipeline(
    catalog,
    exercise,
    "eval_set",
    (event) => events.push(event),
    (reason) => {
      if (reason === "shallow") partials++;
    },
  );
  fixture.frames.forEach((frame) => pipeline.push(frame));
  return {
    events,
    partials,
    event_labels: events.map((event) =>
      labelForEvent(fixture.labels ?? [], event.client_ts_ms),
    ),
  };
}
