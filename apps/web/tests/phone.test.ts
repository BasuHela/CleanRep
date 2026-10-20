import { expect, test } from "vitest";
import { cameraError, pairError, phoneTitle } from "../src/phone/policy";
import { serverPlace } from "../src/shared/place";
import { cameraPrivacy } from "../src/tv/labels";

const at = (phase: string, paused = false, sensor_mode = "paired") => ({
  phase,
  paused,
  sensor_mode,
});

test("W-PHN-1 the phone's heading names where the person is in the session", () => {
  expect(phoneTitle(at("READINESS"), false)).toBe("Paired with your TV");
  expect(phoneTitle(at("PLANNING"), false)).toBe("Paired with your TV");
  expect(phoneTitle(at("READY"), false)).toBe("Turn on the camera");
  expect(phoneTitle(at("IN_SET"), true)).toBe("Camera on");
  // A running camera between sets is still on; a stopped one is off.
  expect(phoneTitle(at("SET_SUMMARY"), true)).toBe("Camera on");
  expect(phoneTitle(at("SET_SUMMARY"), false)).toBe("Camera off");
  expect(phoneTitle(at("IN_SET", true), true)).toBe("Paused on the TV");
  expect(phoneTitle(at("RECAP"), false)).toBe("All done");
  expect(phoneTitle(at("ENDED"), false)).toBe("All done");
});

test("W-PHN-2 pairing failures say what to do, never a raw error code", () => {
  const codes = [
    "pairing_expired",
    "rate_limited",
    "pairing_limit",
    "claim_used",
    "network",
    "validation_error",
  ];
  for (const code of codes) {
    const text = pairError(code);
    expect(text).not.toContain(code);
    expect(text).not.toMatch(/^Error/);
  }
  expect(pairError("pairing_expired")).toContain("six digits on the TV");
  expect(pairError("rate_limited")).toBe(
    "Too many tries. Wait a minute, then try again.",
  );
  expect(pairError("network")).toContain("same Wi-Fi as the CleanRep server");
});

test("W-PHN-3 camera failures name the fix for a blocked, missing or busy camera", () => {
  const named = (name: string) =>
    Object.assign(new Error("raw browser text"), { name });
  expect(cameraError(named("NotAllowedError"))).toContain("allow Camera");
  expect(cameraError(named("NotFoundError"))).toContain("No rear camera");
  expect(cameraError(named("NotReadableError"))).toContain(
    "Another app is using the camera",
  );
  // The camera's own messages (secure connection, no camera API) pass through unchanged.
  expect(cameraError(new Error("This browser can't use the camera."))).toBe(
    "This browser can't use the camera.",
  );
});

test("W-PHN-5 privacy and connection wording follows where the server runs: this computer or elsewhere online", () => {
  for (const origin of [
    "http://127.0.0.1:8105",
    "http://localhost:8105",
    "http://10.0.2.2:8105",
    "https://10.217.25.162:8443",
  ])
    expect(serverPlace(origin)).toBe("home");
  expect(serverPlace("https://cleanrep.example.com")).toBe("online");
  // Unreadable: never claim the frames stay at home.
  expect(serverPlace("not an origin")).toBe("online");

  expect(cameraPrivacy("home")).toContain("on your computer");
  expect(cameraPrivacy("home")).toContain("never stored or sent to AWS");
  expect(cameraPrivacy("online")).not.toContain("computer");
  expect(cameraPrivacy("online")).not.toContain("never stored or sent to AWS");
  for (const place of ["home", "online"] as const)
    expect(cameraPrivacy(place)).toContain("only numbers leave the phone");

  expect(pairError("network", "online")).toBe(
    "Can't reach CleanRep. Check this phone's internet connection.",
  );
  expect(pairError("network", "home")).toContain(
    "same Wi-Fi as the CleanRep server",
  );
});
