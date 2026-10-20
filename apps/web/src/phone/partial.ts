import type { AttemptRejection } from "../pose/types";
/** Local feedback only: a partial attempt never enters the rep transport. */
export class PartialNotice {
  private timer: ReturnType<typeof setTimeout> | undefined;
  constructor(private readonly write: (text: string) => void) {}
  show(reason: AttemptRejection = "shallow"): void {
    clearTimeout(this.timer);
    this.write(
      reason === "uncalibrated"
        ? "Stand tall, side-on and fully in view for a moment so CleanRep can measure your legs."
        : "Partial rep: not counted. Bend deeper.",
    );
    this.timer = setTimeout(() => this.clear(), 3000);
  }
  clear(): void {
    clearTimeout(this.timer);
    this.timer = undefined;
    this.write("");
  }
}
