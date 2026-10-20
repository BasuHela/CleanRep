import { describe, expect, it } from "vitest";
import { backAction, footerHint } from "../src/tv/remote";

describe("remote Back key (emulator finding: Back on home could not exit)", () => {
  it("exits from the top-level screens", () => {
    expect(backAction({ drawer: false, about: false, view: null })).toBe(
      "exit",
    );
    for (const phase of ["LOBBY", "ENDED"])
      expect(
        backAction({
          drawer: false,
          about: false,
          view: { phase, paused: false },
        }),
      ).toBe("exit");
  });
  it("closes overlays before anything else", () => {
    const view = { phase: "IN_SET", paused: false };
    expect(backAction({ drawer: true, about: false, view })).toBe(
      "close-drawer",
    );
    expect(backAction({ drawer: false, about: true, view: null })).toBe(
      "close-about",
    );
  });
  it("pauses and resumes a running session", () => {
    expect(
      backAction({
        drawer: false,
        about: false,
        view: { phase: "IN_SET", paused: false },
      }),
    ).toBe("pause");
    expect(
      backAction({
        drawer: false,
        about: false,
        view: { phase: "DEMO", paused: true },
      }),
    ).toBe("resume");
  });
  it("describes Back truthfully and offers Menu only in a session", () => {
    expect(footerHint({ drawer: false, about: false, view: null })).toEqual([
      "Back to exit",
    ]);
    expect(
      footerHint({
        drawer: false,
        about: false,
        view: { phase: "IN_SET", paused: false },
      }),
    ).toEqual(["Back to pause", "Menu for details"]);
  });
});
