export interface Box {
  x: number;
  y: number;
  width: number;
  height: number;
}
export type Direction = "ArrowUp" | "ArrowDown" | "ArrowLeft" | "ArrowRight";

const ROW_TOLERANCE = 24;

/**
 * Pick the next focus target for a D-pad direction. Returns an index into `boxes` or -1.
 * Up/Down moves to the nearest row first, then the horizontally closest element in that row, so a far-left
 * "Continue" is chosen over a footer button that happens to sit below the current column.
 * Left/Right prefers elements in the same row (vertical overlap) and falls back to the nearest one in that direction.
 */
export function chooseTarget(from: Box, boxes: Box[], key: Direction): number {
  const cx = (b: Box) => b.x + b.width / 2,
    cy = (b: Box) => b.y + b.height / 2;
  const vertical = key === "ArrowUp" || key === "ArrowDown";
  const sign = key === "ArrowDown" || key === "ArrowRight" ? 1 : -1;
  const ahead = boxes
    .map((box, index) => ({ box, index }))
    .filter(({ box }) =>
      vertical
        ? (cy(box) - cy(from)) * sign > 8
        : (cx(box) - cx(from)) * sign > 8,
    );
  if (!ahead.length) return -1;
  if (vertical) {
    // Edge distance to the next row: top edge going down, bottom edge going up.
    const gap = (b: Box) =>
      sign > 0 ? b.y - (from.y + from.height) : from.y - (b.y + b.height);
    const nearest = Math.min(...ahead.map(({ box }) => gap(box)));
    const row = ahead.filter(({ box }) => gap(box) <= nearest + ROW_TOLERANCE);
    row.sort(
      (a, b) => Math.abs(cx(a.box) - cx(from)) - Math.abs(cx(b.box) - cx(from)),
    );
    return row[0].index;
  }
  const overlaps = (b: Box) =>
    b.y < from.y + from.height && b.y + b.height > from.y;
  const sameRow = ahead.filter(({ box }) => overlaps(box));
  const pool = sameRow.length ? sameRow : ahead;
  pool.sort((a, b) => {
    const score = (c: Box) =>
      Math.abs(cx(c) - cx(from)) + Math.abs(cy(c) - cy(from)) * 3;
    return score(a.box) - score(b.box);
  });
  return pool[0].index;
}

export function installRemoteNavigation(onBack: () => void): void {
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" || event.key === "BrowserBack") {
      event.preventDefault();
      if (!event.repeat) onBack();
      return;
    }
    if (
      ![
        "ArrowUp",
        "ArrowDown",
        "ArrowLeft",
        "ArrowRight",
        "Enter",
        " ",
        "Tab",
      ].includes(event.key)
    )
      return;
    const active = document.activeElement as HTMLElement | null;
    if (active?.matches('input:not([type="checkbox"]),select,textarea')) return;
    if (event.repeat) {
      event.preventDefault();
      return;
    }
    const modal = document.querySelector(".modal"),
      container = modal ?? document;
    const nodes = Array.from(
      container.querySelectorAll<HTMLElement>(
        "button:not([disabled]),a[href],input:not([disabled]),select:not([disabled])",
      ),
    ).filter((el) => el.getClientRects().length > 0);
    if (event.key === "Tab") {
      if (modal) {
        event.preventDefault();
        const index = nodes.indexOf(active!);
        nodes[
          (index + (event.shiftKey ? -1 : 1) + nodes.length) % nodes.length
        ]?.focus();
      }
      return;
    }
    if (event.key === "Enter" || event.key === " ") {
      if (active?.matches("button,a")) {
        event.preventDefault();
        active.click();
      } else if (
        active instanceof HTMLInputElement &&
        active.type === "checkbox"
      ) {
        event.preventDefault();
        active.checked = !active.checked;
        active.dispatchEvent(new Event("change", { bubbles: true }));
      }
      return;
    }
    if (!nodes.length) return;
    event.preventDefault();
    if (!active || !nodes.includes(active)) {
      nodes[0].focus();
      return;
    }
    const others = nodes.filter((el) => el !== active);
    const target = chooseTarget(
      active.getBoundingClientRect(),
      others.map((el) => el.getBoundingClientRect()),
      event.key as Direction,
    );
    if (target >= 0) {
      others[target].focus();
      others[target].scrollIntoView?.({ block: "nearest" });
    }
  });
}
