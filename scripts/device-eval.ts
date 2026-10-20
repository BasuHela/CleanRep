/** Pure parts of `rep-eval.mjs --device`: the hand-count CSV and the agreement summary. */
export type DeviceRow = {
  set_index: string;
  lighting: string;
  hand_count: number;
  recording_file: string;
  exercise_id: string;
};

const round = (n: number) => Math.round(n * 1000) / 1000;

/** Rows with both a recording and a hand count; the last column (notes) may contain commas. */
export function parseDeviceCsv(text: string): DeviceRow[] {
  const [header, ...lines] = text.trim().split(/\r?\n/);
  const keys = header.split(",");
  return lines
    .map((line) => {
      const cells = line.split(",");
      const row: Record<string, string> = {};
      keys.forEach((key, i) => {
        row[key] = (
          i === keys.length - 1 ? cells.slice(i).join(",") : (cells[i] ?? "")
        ).trim();
      });
      return row;
    })
    .filter((row) => row.recording_file && row.hand_count)
    .map((row) => ({
      set_index: row.set_index,
      lighting: row.lighting,
      hand_count: Number(row.hand_count),
      recording_file: row.recording_file,
      exercise_id: row.exercise_id || "squat",
    }));
}

/** Exact-count agreement with the hand count, and the share of counted reps left unrated. */
export function deviceSummary(
  sets: { hand_count: number; events: { rated: boolean }[] }[],
) {
  const errors = sets.map((s) => Math.abs(s.events.length - s.hand_count));
  const reps = sets.reduce((n, s) => n + s.events.length, 0);
  const unrated = sets.reduce(
    (n, s) => n + s.events.filter((e) => !e.rated).length,
    0,
  );
  return {
    sets: sets.length,
    hand_count: sets.reduce((n, s) => n + s.hand_count, 0),
    reps_counted: reps,
    exact_rate: round(errors.filter((e) => e === 0).length / sets.length),
    mean_absolute_error: round(errors.reduce((a, b) => a + b, 0) / sets.length),
    abstention_rate: reps ? round(unrated / reps) : null,
  };
}
