import type { CoachNotice } from "../../../../packages/contracts/generated";

export function coachLabel(kind: CoachNotice["kind"]): string {
  return kind === "shallow"
    ? "Not counted: bend deeper"
    : "Stand tall, side-on and fully in view so the phone can measure your legs";
}

/** Keep this element outside the screen's replaced markup, just like a text ticker. */
export class CoachBanner {
  private seq: number | null = null;
  constructor(private readonly element: HTMLElement) {
    element.className = "coach-banner";
    element.setAttribute("role", "status");
    element.setAttribute("aria-live", "polite");
    element.setAttribute("aria-atomic", "true");
    element.hidden = true;
  }
  update(notice: CoachNotice | null): void {
    if (!notice) {
      if (this.seq !== null) this.element.textContent = "";
      this.seq = null;
      this.element.hidden = true;
    } else if (notice.seq !== this.seq) {
      this.seq = notice.seq;
      this.element.textContent = coachLabel(notice.kind);
      this.element.hidden = false;
    }
  }
}
