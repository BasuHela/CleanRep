import { expect, test } from "vitest";
import { CLAIM_POLL_MS, claimWaiting } from "../src/phone/policy";
import { phoneUrl } from "../src/tv/labels";

test("W-CFG-2 the TV pairing screen names the full phone address, not just /phone", () => {
  // Stick and phone both over adb reverse: the TV's API origin is the phone's address too.
  expect(phoneUrl("http://localhost:8105")).toBe("http://localhost:8105/phone");
  // 10.0.2.2 is the emulator's name for the host; a phone reaches the host as localhost.
  expect(phoneUrl("http://10.0.2.2:8105")).toBe("http://localhost:8105/phone");
  expect(phoneUrl("https://cleanrep.example")).toBe(
    "https://cleanrep.example/phone",
  );
});

test("W-CFG-3 when the server names a phone address, the TV shows it instead of its own origin", () => {
  // The TV app reaches its API over loopback; a Wi-Fi phone opens the TV's Wi-Fi address.
  expect(
    phoneUrl("http://127.0.0.1:8105", "http://192.168.1.20:8105/phone"),
  ).toBe("http://192.168.1.20:8105/phone");
  expect(phoneUrl("http://localhost:8105", null)).toBe(
    "http://localhost:8105/phone",
  );
});

test("W-CFG-1 the phone polls its pairing claim under the server limit and treats rate_limited as still waiting", () => {
  // The server allows 40 claims per minute per address (apps/core: Sessions).
  expect(60_000 / CLAIM_POLL_MS).toBeLessThan(40);
  expect(claimWaiting("not_approved")).toBe(true);
  expect(claimWaiting("rate_limited")).toBe(true);
  expect(claimWaiting("not_found")).toBe(false);
});
