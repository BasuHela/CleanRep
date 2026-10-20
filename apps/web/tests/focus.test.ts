import { describe, expect, it } from "vitest";
import { chooseTarget, type Box } from "../src/tv/focus";

const box = (x: number, y: number, width = 100, height = 76): Box => ({
  x,
  y,
  width,
  height,
});

describe("TV D-pad focus (found on the Android TV API 30 emulator)", () => {
  // Readiness screen geometry measured on the emulator at 1920x1080.
  const lastNo = box(1590, 590, 92);
  const continueButton = box(96, 763, 180);
  const footerPause = box(1698, 1011, 112, 52);
  const firstNo = box(1590, 402, 92);

  it("Down from the last answer goes to Continue, not the footer Pause button", () => {
    expect(
      chooseTarget(lastNo, [continueButton, footerPause, firstNo], "ArrowDown"),
    ).toBe(0);
  });
  it("Down from Continue reaches the footer", () => {
    expect(
      chooseTarget(continueButton, [footerPause, lastNo], "ArrowDown"),
    ).toBe(0);
  });
  it("Up picks the nearest row, then the closest element in it", () => {
    expect(chooseTarget(continueButton, [firstNo, lastNo], "ArrowUp")).toBe(1);
  });
  it("Right stays in the same row before jumping rows", () => {
    const resume = box(96, 402, 170),
      knee = box(280, 402, 385),
      quiet = box(96, 495, 316),
      floor = box(427, 495, 298);
    expect(chooseTarget(resume, [knee, quiet, floor], "ArrowRight")).toBe(0);
  });
  it("Right at the end of a row falls back to the nearest element further right", () => {
    const knee = box(280, 402, 385),
      quiet = box(96, 495, 316),
      floor = box(427, 495, 298);
    expect(chooseTarget(knee, [quiet, floor], "ArrowRight")).toBe(1);
  });
  it("returns -1 when nothing lies in that direction", () => {
    expect(chooseTarget(continueButton, [lastNo], "ArrowLeft")).toBe(-1);
  });
});
