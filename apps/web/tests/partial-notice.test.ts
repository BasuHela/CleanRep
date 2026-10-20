import { expect, test, vi } from "vitest";
import { PartialNotice } from "../src/phone/partial";

test("W-REP-15 partial notice expires, repeated attempts restart it and reset clears it", () => {
  vi.useFakeTimers();
  try {
    const write = vi.fn(),
      notice = new PartialNotice(write);
    notice.show();
    expect(write).toHaveBeenLastCalledWith(
      "Partial rep: not counted. Bend deeper.",
    );
    vi.advanceTimersByTime(2500);
    notice.show();
    vi.advanceTimersByTime(2500);
    expect(write).toHaveBeenLastCalledWith(
      "Partial rep: not counted. Bend deeper.",
    );
    vi.advanceTimersByTime(500);
    expect(write).toHaveBeenLastCalledWith("");
    notice.show();
    notice.clear();
    expect(write).toHaveBeenLastCalledWith("");
    const calls = write.mock.calls.length;
    vi.advanceTimersByTime(3000);
    expect(write).toHaveBeenCalledTimes(calls);
  } finally {
    vi.useRealTimers();
  }
});
