import { expect, test, type Page } from "@playwright/test";
import { spawn, type ChildProcess } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { chooseTarget, type Direction } from "../../src/tv/focus";
// Navigate the actual focus graph using only D-pad keys. DOM reads choose a route;
// no click(), focus() or DOM mutation is used to operate the TV.
async function choose(page: Page, label: string) {
  await expect(
    page.getByRole("button", { name: label, exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: label, exact: true }),
  ).toBeEnabled();
  // Read the live geometry, then plan the route with the app's own chooseTarget so test and app cannot drift.
  const layout = await page.evaluate((target) => {
    const nodes = Array.from(
      document.querySelectorAll<HTMLElement>(
        "button:not([disabled]),a[href],input:not([disabled]),select:not([disabled])",
      ),
    ).filter((el) => el.getClientRects().length > 0);
    return {
      boxes: nodes.map((n) => {
        const r = n.getBoundingClientRect();
        return { x: r.x, y: r.y, width: r.width, height: r.height };
      }),
      destination: nodes.findIndex((n) => n.textContent?.trim() === target),
      start: nodes.indexOf(document.activeElement as HTMLElement),
    };
  }, label);
  const keys = ((): Direction[] | null => {
    const queue: [number, Direction[]][] = [[layout.start, []]],
      seen = new Set<number>();
    while (queue.length) {
      const [index, path] = queue.shift()!;
      if (index === layout.destination) return path;
      if (seen.has(index)) continue;
      seen.add(index);
      if (index < 0) {
        queue.push([0, [...path, "ArrowDown"]]);
        continue;
      }
      const others = layout.boxes
        .map((box, i) => ({ box, i }))
        .filter(({ i }) => i !== index);
      for (const key of [
        "ArrowDown",
        "ArrowRight",
        "ArrowUp",
        "ArrowLeft",
      ] as Direction[]) {
        const next = chooseTarget(
          layout.boxes[index],
          others.map((o) => o.box),
          key,
        );
        if (next >= 0) queue.push([others[next].i, [...path, key]]);
      }
    }
    return null;
  })();
  expect(keys, "D-pad route to " + label).not.toBeNull();
  for (const key of keys!) await page.keyboard.press(key);
  await expect(
    page.getByRole("button", { name: label, exact: true }),
  ).toBeFocused();
  await page.keyboard.press("Enter");
}
async function state(page: Page) {
  return page.evaluate(async () => {
    const s = JSON.parse(sessionStorage.getItem("cleanrep.session")!);
    return (
      await fetch("/v1/sessions/" + s.session_id, {
        headers: { Authorization: "Bearer " + s.token },
      })
    ).json();
  });
}
async function setup(tv: Page, phone?: Page, quiet = true, phoneQuery = "") {
  await tv.goto("/tv");
  await choose(tv, "Start a session");
  if (phone) {
    await phone.goto(
      "/phone?source=replay&fixture=squat_shallow_10" + phoneQuery,
    );
    const code = (await tv.locator(".pair-code").textContent())!.trim();
    await phone.locator("#code").fill(code);
    await phone.getByRole("button", { name: "Pair with TV" }).click();
    await expect(tv.getByText(/Verify both screens/)).toBeVisible();
    // Emulator finding: a new pairing request must take focus so one OK approves it.
    await expect(
      tv.getByRole("button", { name: "Approve phone", exact: true }),
    ).toBeFocused();
    await choose(tv, "Approve phone");
    await expect(phone.locator("#sensor")).toBeVisible();
  } else await choose(tv, "Use remote only");
  await expect(
    tv.getByRole("heading", { name: "Before we start" }),
  ).toBeVisible();
  // Readiness starts on the first safety question, not on Continue.
  await expect(tv.locator("button:focus")).toHaveText("No");
  await choose(tv, "Continue");
  await choose(tv, "Chair");
  if (!quiet) await choose(tv, "✓ Quiet");
  await choose(tv, "Make plan");
  await expect(tv.locator("[data-source]")).toHaveAttribute(
    "data-source",
    "fixture",
  );
  await expect(tv.locator("[data-source]")).toHaveText("Built-in plan");
  await choose(tv, "Start");
  await choose(tv, "Continue");
  await expect(
    tv.getByRole("button", { name: "Start set", exact: true }),
  ).toBeVisible();
}
async function replaySet(tv: Page, phone: Page, fixture = "squat_good_10") {
  await choose(tv, "Start set");
  await phone.locator("#fixture").selectOption(fixture);
  await expect.poll(async () => (await state(tv)).data.phase).toBe("IN_SET");
  // Allow the phone poll to receive the new set id before feeding the same pipeline.
  await expect(phone.locator("#status")).toHaveText("Ready to run the replay.");
  await phone.waitForTimeout(1100);
  await phone.getByRole("button", { name: "Run replay" }).click();
  await expect(
    tv.getByRole("heading", { name: /Set complete|Re-test complete/ }),
  ).toBeVisible({ timeout: 15000 });
  await expect(
    tv.getByText("10 reps counted · 10 rated (estimated)", { exact: true }),
  ).toBeVisible();
}
async function finishBySkipping(tv: Page) {
  for (let i = 0; i < 30; i++) {
    const s = await state(tv);
    if (s.data.phase === "RECAP") return;
    if (s.data.phase === "DEMO") await choose(tv, "Skip exercise");
    else await choose(tv, "Continue");
  }
  throw new Error("Session did not reach recap");
}
test("J-1 paired shallow set → drill → improved re-test → recap, TV D-pad only", async ({
  browser,
  page: tv,
}) => {
  const context = await browser.newContext(),
    phone = await context.newPage();
  // Deterministically exercise upload recovery before continuing into the drill.
  let throttleFirstRep = true;
  await phone.route("**/v1/sessions/*/reps", async (route) => {
    if (throttleFirstRep) {
      throttleFirstRep = false;
      await route.fulfill({
        status: 429,
        contentType: "application/json",
        body: JSON.stringify({
          status: "rejected",
          version: null,
          data: {},
          error: { code: "rate_limited", message: "Rate limited." },
        }),
      });
    } else await route.continue();
  });
  await setup(tv, phone);
  await replaySet(tv, phone, "squat_shallow_10");
  expect(throttleFirstRep).toBe(false);
  expect((await state(tv)).data.summary.dominant_fault).toBe("shallow_depth");
  // The set's wording shows on the summary itself; there is no button to press for it.
  await expect(tv.locator("[data-explanation]")).toContainText(
    "rated reps were above about-parallel depth (estimated). Next: Box squat to chair.",
  );
  await expect(tv.locator("[data-rest]")).toContainText(/^Rest (\d:\d\d|done)/);
  await choose(tv, "Try Box squat to chair");
  await expect(
    tv.getByRole("heading", { name: "Box squat to chair", exact: true }),
  ).toBeVisible();
  await replaySet(tv, phone);
  await expect(
    tv.getByRole("heading", { name: "Clean set: no faults flagged" }),
  ).toBeVisible();
  await choose(tv, "Continue");
  // Emulator finding: after Continue the next screen focuses Start set, never Skip exercise
  // (both screens reuse the "advance" action id).
  await expect(
    tv.getByRole("button", { name: "Start set", exact: true }),
  ).toBeFocused();
  await replaySet(tv, phone);
  await expect(tv.locator("[data-comparison]")).toHaveAttribute(
    "data-verdict",
    "improved",
  );
  await expect(tv.locator("[data-comparison]")).toContainText(
    "Improved: flagged in",
  );
  await finishBySkipping(tv);
  await expect(
    tv.getByRole("heading", { name: "Practice, noticed." }),
  ).toBeVisible();
  await tv.screenshot({ path: "../../evals/tv-browser-recap.png" });
  await choose(tv, "End and delete");
  await context.close();
});
test("J-2 discomfort replan preserves completed blocks and excludes jumps", async ({
  page: tv,
}) => {
  await setup(tv, undefined, false);
  for (let i = 0; i < 2; i++) {
    await choose(tv, "Start set");
    await choose(tv, "End set");
    await choose(tv, "Continue");
  }
  const before = (await state(tv)).data;
  expect(
    before.plan.blocks.some(
      (b: { exercise_id: string }) => b.exercise_id === "squat_jump",
    ),
  ).toBe(true);
  await choose(tv, "Pause");
  // Paused screen, "Go easy on": choosing an area reports discomfort there.
  await choose(tv, "Knees");
  await expect(
    tv.getByRole("button", { name: "Start set", exact: true }),
  ).toBeVisible();
  const after = (await state(tv)).data;
  expect(after.completed_sets).toEqual(before.completed_sets);
  expect(after.plan.blocks[0]).toEqual(before.plan.blocks[0]);
  expect(
    after.plan.blocks.some(
      (b: { exercise_id: string }) => b.exercise_id === "squat_jump",
    ),
  ).toBe(false);
});
test("J-3 phone closes mid-set, remote-only keeps history and completes", async ({
  browser,
  page: tv,
}) => {
  const context = await browser.newContext(),
    phone = await context.newPage();
  await setup(tv, phone);
  await replaySet(tv, phone);
  await choose(tv, "Continue");
  const before = (await state(tv)).data.completed_sets;
  await choose(tv, "Start set");
  await context.close();
  await choose(tv, "Pause");
  await choose(tv, "Use remote only");
  await choose(tv, "Resume");
  await choose(tv, "End set");
  const after = (await state(tv)).data;
  expect(after.completed_sets.slice(0, before.length)).toEqual(before);
  expect(after.summary.rated_reps).toBe(0);
  await finishBySkipping(tv);
});
async function launch(data: string): Promise<ChildProcess> {
  const root = path.resolve("../..");
  // The Java server the TV app runs, as `make server` starts it, with its store in `data`.
  const server = spawn(
    "java",
    [
      `-Dcleanrep.root=${root}`,
      "-cp",
      path.join(root, "apps/core/build/classes"),
      "org.cleanrep.core.Main",
      "--port",
      "8126",
      "--origin",
      "http://127.0.0.1:8126",
      "--data",
      data,
      "--static",
      path.join(root, "apps/web/dist"),
      "--root",
      root,
      "--max-seconds",
      "120",
    ],
    { cwd: root, stdio: "pipe" },
  );
  for (let i = 0; i < 100; i++) {
    let health: Response | undefined;
    try {
      health = await fetch("http://127.0.0.1:8126/v1/healthz");
    } catch {}
    if (health?.ok) {
      // The JSON store names the Java server, so no other server on this port can pass for it.
      const store = (await health.json()).data.store;
      if (store === "json") return server;
      server.kill("SIGKILL");
      throw new Error(`Restart test server has store ${store}, not json`);
    }
    await new Promise((r) => setTimeout(r, 100));
  }
  server.kill("SIGKILL");
  throw new Error("Restart test server startup failed");
}
async function stop(server: ChildProcess) {
  if (server.exitCode !== null) return;
  await new Promise<void>((resolve) => {
    server.once("exit", () => resolve());
    server.kill("SIGTERM");
    setTimeout(() => server.kill("SIGKILL"), 3000).unref();
  });
}
test("J-4 server restart resumes same phase and version", async ({
  page: tv,
}) => {
  const dir = await mkdtemp(path.join(tmpdir(), "cleanrep-restart-"));
  let server = await launch(path.join(dir, "home-data"));
  try {
    await tv.goto("http://127.0.0.1:8126/tv");
    await choose(tv, "Start a session");
    await choose(tv, "Use remote only");
    const before = await state(tv);
    await stop(server);
    server = await launch(path.join(dir, "home-data"));
    await tv.reload();
    await expect(
      tv.getByRole("heading", { name: "Before we start" }),
    ).toBeVisible();
    const after = await state(tv);
    expect(after.version).toBe(before.version);
    expect(after.data.phase).toBe(before.data.phase);
  } finally {
    await stop(server);
    await rm(dir, { recursive: true, force: true });
  }
});
test("J-4b a saved session that no longer exists opens a clean home screen, and pairing starts without an error", async ({
  page: tv,
}) => {
  // Emulator take, 19 Oct: the APK kept the previous run's session, and "Not found." stayed on home and pairing.
  await tv.addInitScript(() =>
    sessionStorage.setItem(
      "cleanrep.session",
      JSON.stringify({
        session_id: "gone-session",
        token: "stale-token",
        role: "tv",
      }),
    ),
  );
  const refreshed = tv.waitForResponse(
    (r) => r.url().endsWith("/v1/sessions/gone-session") && r.status() >= 400,
  );
  await tv.goto("/tv");
  await refreshed;
  await expect(
    tv.getByRole("heading", { name: "A little practice. A clearer picture." }),
  ).toBeVisible();
  await expect(tv.getByRole("alert")).toHaveCount(0);
  await choose(tv, "Start a session");
  await expect(tv.locator(".pair-code")).toBeVisible();
  await expect(tv.getByRole("alert")).toHaveCount(0);
});
test("J-5 phone request bodies contain only allowed numeric actions under 2 KB", async ({
  browser,
  page: tv,
}) => {
  const context = await browser.newContext(),
    phone = await context.newPage();
  const bodies: {
    url: string;
    body: Record<string, unknown>;
    bytes: number;
  }[] = [];
  const mirrorPosts: string[] = [];
  phone.on("request", (r) => {
    if (r.url().endsWith("/mirror")) mirrorPosts.push(r.url());
    else if (r.url().includes("/v1/") && r.postData())
      bodies.push({
        url: r.url(),
        body: JSON.parse(r.postData()!),
        bytes: Buffer.byteLength(r.postData()!),
      });
  });
  // The debug readout is on here: it must show on the phone and add nothing to any request.
  await setup(tv, phone, true, "&debug=1");
  // The camera view is on by default; with it turned off only numbers leave the phone.
  await phone.getByRole("button", { name: "Stop showing on TV" }).click();
  await replaySet(tv, phone);
  expect(mirrorPosts).toEqual([]);
  await expect(phone.locator("#debug")).toHaveText(
    /^Debug · REPLAY · no pose model · \d+\.\d fps · \d+ frames$/,
  );
  expect(bodies.some((b) => b.url.endsWith("/reps"))).toBe(true);
  const allowed = new Set([
    "pairing_code",
    "request_id",
    "claim_token",
    "set_id",
    "rep_index",
    "exercise_id",
    "rated",
    "confidence",
    "metrics",
    "duration_s",
    "client_ts_ms",
    "full_body",
    "side_view",
    "light",
    "fps",
    "delegate",
    "aligned_s",
    "total_s",
  ]);
  for (const { body, bytes } of bodies) {
    expect(bytes).toBeLessThanOrEqual(2048);
    expect(Object.keys(body).every((k) => allowed.has(k))).toBe(true);
    expect(JSON.stringify(body)).not.toMatch(
      /image|frame|landmark|pixel|video|base64/i,
    );
    if (body.metrics)
      expect(Object.keys(body.metrics as object).sort()).toEqual(
        ["depth_ratio", "descent_s", "knee_min_deg", "trunk_lean_deg"].sort(),
      );
  }
  await context.close();
});
test("J-6 camera view on by default in replay mode sends frames only to /mirror, shows live panel and rep checkmark on TV, stops on toggle off and remembers it", async ({
  browser,
  page: tv,
}) => {
  const context = await browser.newContext(),
    phone = await context.newPage();
  const mirrorPosts: { url: string; contentType: string }[] = [],
    jsonBodies: { url: string; raw: string; bytes: number }[] = [];
  phone.on("request", (r) => {
    if (!r.url().includes("/v1/") || r.method() !== "POST") return;
    if (r.url().endsWith("/mirror")) {
      mirrorPosts.push({
        url: r.url(),
        contentType: r.headers()["content-type"] ?? "",
      });
    } else if (r.postData()) {
      jsonBodies.push({
        url: r.url(),
        raw: r.postData()!,
        bytes: Buffer.byteLength(r.postData()!),
      });
    }
  });
  await setup(tv, phone);
  expect(mirrorPosts.length).toBe(0);
  await expect(phone.locator("#mirror-indicator")).toBeHidden();
  await expect(tv.locator("[data-mirror-panel]")).toHaveCount(0);

  await choose(tv, "Start set");
  await phone.locator("#fixture").selectOption("squat_good_10");
  await expect.poll(async () => (await state(tv)).data.phase).toBe("IN_SET");
  await expect(phone.locator("#status")).toHaveText("Ready to run the replay.");
  await phone.waitForTimeout(1100);

  // No tap: the view is on by default, and the indicator appears once frames may go.
  await expect(phone.locator("#mirror")).toHaveText("Stop showing on TV");
  await phone.getByRole("button", { name: "Run replay" }).click();
  await expect(phone.locator("#mirror-indicator")).toBeVisible();
  await expect(phone.locator("#mirror-indicator")).toHaveText(
    "Camera visible on your TV",
  );

  await expect(tv.locator("[data-mirror-panel]")).toBeVisible();
  await expect(tv.locator("[data-mirror-panel] figcaption")).toHaveText(
    "Live camera from your phone",
  );
  await expect(tv.locator("[data-mirror-panel] img")).toHaveAttribute(
    "src",
    /^blob:/,
  );
  await expect
    .poll(async () =>
      tv
        .locator("[data-mirror-panel] img")
        .evaluate((el: HTMLImageElement) => el.naturalWidth),
    )
    .toBeGreaterThan(0);
  await expect(
    tv.locator('[data-mirror-panel] [data-rep-feedback="clean"]'),
  ).toContainText("✓");

  // Turn mirror off mid-set and verify frame uploads stop immediately.
  await phone.getByRole("button", { name: "Stop showing on TV" }).click();
  await expect(phone.locator("#mirror-indicator")).toBeHidden();
  await phone.waitForTimeout(150);
  const countAfterOff = mirrorPosts.length;
  expect(countAfterOff).toBeGreaterThan(0);
  await phone.waitForTimeout(450);
  expect(mirrorPosts.length).toBe(countAfterOff);

  for (const post of mirrorPosts) {
    expect(post.url).toMatch(/\/v1\/sessions\/[^/]+\/mirror$/);
    expect(post.contentType).toBe("image/jpeg");
  }
  for (const jb of jsonBodies) {
    expect(jb.bytes).toBeLessThanOrEqual(2048);
    expect(jb.raw).not.toMatch(/image|frame|landmark|pixel|video|base64/i);
  }

  await expect(
    tv.getByRole("heading", { name: /Set complete|Re-test complete/ }),
  ).toBeVisible({ timeout: 15000 });
  // The phone remembers that the view was turned off.
  await phone.reload();
  await expect(phone.locator("#mirror")).toHaveText("Show camera on TV");
  await context.close();
});

test("J-6b coach banner covers neither the camera panel (hint, live, or tall from an upright phone), the rep count nor End set", async ({
  browser,
  page: tv,
}) => {
  const context = await browser.newContext(),
    phone = await context.newPage();
  await setup(tv, phone);
  await choose(tv, "Start set");
  await phone.locator("#fixture").selectOption("squat_good_10");
  await expect.poll(async () => (await state(tv)).data.phase).toBe("IN_SET");
  // The replay fixtures contain no rejected attempts, so show the longest notice directly.
  // Show and measure in one synchronous step: the TV's next poll hides a banner it did not set.
  const expectNoOverlap = async (panel: string) => {
    const overlaps = await tv.evaluate(
      (selectors) => {
        const b = document.querySelector<HTMLElement>(".coach-banner")!;
        b.textContent =
          "Stand tall, side-on and fully in view so the phone can measure your legs";
        b.hidden = false;
        const banner = b.getBoundingClientRect();
        return selectors.map((selector) => {
          const other = document
            .querySelector(selector)!
            .getBoundingClientRect();
          return [
            selector,
            other.width > 0 &&
              banner.left < other.right &&
              other.left < banner.right &&
              banner.top < other.bottom &&
              other.top < banner.bottom,
          ] as const;
        });
      },
      [panel, "[data-rep-count]", '[data-action="endset"]'],
    );
    for (const [selector, overlap] of overlaps)
      expect(overlap, `coach banner overlaps ${selector}`).toBe(false);
  };
  // Before the replay runs, the phone sends nothing, so the hint holds the panel's space.
  await expect(tv.locator("[data-mirror-hint]")).toContainText(
    "Your camera view appears here when the phone camera is on",
  );
  await expectNoOverlap("[data-mirror-hint]");
  await phone.getByRole("button", { name: "Run replay" }).click();
  await expect(tv.locator("[data-mirror-panel] img")).toHaveAttribute(
    "src",
    /^blob:/,
  );
  await expect(tv.locator("[data-mirror-hint]")).toHaveCount(0);
  await expectNoOverlap("[data-mirror-panel]");

  // An upright phone sends tall frames: the panel takes their shape and still fits above the footer.
  const portraitJpeg = Buffer.from(
    await tv.evaluate(() => {
      const c = document.createElement("canvas");
      [c.width, c.height] = [360, 640];
      const ctx = c.getContext("2d")!;
      ctx.fillStyle = "#335544";
      ctx.fillRect(0, 0, 360, 640);
      return c.toDataURL("image/jpeg").split(",")[1];
    }),
    "base64",
  );
  await tv.route(/\/mirror$/, (route) =>
    route.request().method() === "GET"
      ? route.fulfill({
          status: 200,
          contentType: "image/jpeg",
          body: portraitJpeg,
        })
      : route.fallback(),
  );
  await expect(tv.locator("[data-mirror-panel]")).toHaveClass(/portrait/);
  const fit = await tv.evaluate(() => {
    const frame = document
        .querySelector("[data-mirror-panel] .mirror-frame")!
        .getBoundingClientRect(),
      panel = document
        .querySelector("[data-mirror-panel]")!
        .getBoundingClientRect(),
      footer = document.querySelector("footer")!.getBoundingClientRect();
    return {
      ratio: frame.height / frame.width,
      bottom: panel.bottom,
      footer: footer.top,
    };
  });
  expect(fit.ratio).toBeCloseTo(640 / 360, 1);
  expect(fit.bottom).toBeLessThanOrEqual(fit.footer);
  await expectNoOverlap("[data-mirror-panel]");
  await context.close();
});

// The server under test is whatever Playwright's baseURL names, so another server and port need no change here.
const serverAt = (baseURL: string | undefined) => new URL(baseURL!);
// Stands in for the phone app's `CleanRepNative`: the page may not reach the TV itself (its CSP is connect-src 'self'),
// so every API call and camera frame goes through here and is answered with a `cleanrep-native-response` event.
const fakePhoneApp = (origin: string) => {
  const realFetch = window.fetch.bind(window);
  const log = {
    pageFetches: [] as string[],
    asyncCalls: 0,
    mirror: [] as { id: string; path: string; head: number[]; bytes: number }[],
  };
  Object.assign(window, { __bridge: log });
  window.fetch = (input, init) => {
    log.pageFetches.push(
      typeof input === "string"
        ? input
        : input instanceof URL
          ? input.pathname
          : input.url,
    );
    return realFetch(input, init);
  };
  const forward = async (
    id: string,
    method: string,
    path: string,
    contentType: string,
    token: string,
    body?: BodyInit,
  ) => {
    let payload: string;
    try {
      const response = await realFetch(origin + path, {
        method,
        headers: {
          "Content-Type": contentType,
          ...(token ? { Authorization: "Bearer " + token } : {}),
        },
        body,
        cache: "no-store",
      });
      payload = await response.text();
    } catch {
      payload = JSON.stringify({
        status: "unavailable",
        data: {},
        error: { message: "Can't reach your TV." },
      });
    }
    window.dispatchEvent(
      new CustomEvent("cleanrep-native-response", { detail: { id, payload } }),
    );
  };
  Object.assign(window, {
    CleanRepNative: {
      apiOrigin: () => origin,
      setServer: () => true,
      saveSession: (value: string) =>
        sessionStorage.setItem("fake.native.session", value),
      loadSession: () => sessionStorage.getItem("fake.native.session") ?? "",
      clearSession: () => sessionStorage.removeItem("fake.native.session"),
      requestAsync(
        id: string,
        method: string,
        path: string,
        body: string,
        token: string,
      ) {
        log.asyncCalls++;
        void forward(
          id,
          method,
          path,
          "application/json",
          token,
          body || undefined,
        );
      },
      requestMirror(
        id: string,
        path: string,
        jpegBase64: string,
        token: string,
      ) {
        const bytes = Uint8Array.from(atob(jpegBase64), (c) => c.charCodeAt(0));
        log.mirror.push({
          id,
          path,
          head: Array.from(bytes.subarray(0, 3)),
          bytes: bytes.length,
        });
        void forward(id, "POST", path, "image/jpeg", token, bytes);
      },
    },
  });
};
type BridgeLog = {
  pageFetches: string[];
  asyncCalls: number;
  mirror: { id: string; path: string; head: number[]; bytes: number }[];
};
test("J-7 the phone page inside the phone app: camera frames and every API call go through the bridge, never a page fetch", async ({
  browser,
  baseURL,
  page: tv,
}) => {
  const server = serverAt(baseURL);
  const context = await browser.newContext(),
    phone = await context.newPage();
  await phone.addInitScript(fakePhoneApp, server.origin);
  const mirrorPosts: { url: string; contentType: string }[] = [];
  phone.on("request", (r) => {
    if (r.method() === "POST" && r.url().endsWith("/mirror"))
      mirrorPosts.push({
        url: r.url(),
        contentType: r.headers()["content-type"] ?? "",
      });
  });
  await setup(tv, phone);
  // Pairing worked through the bridge, which also holds the session; the page keeps none of its own.
  await expect(phone.locator(".tv-at")).toHaveText(`TV at ${server.hostname}`);
  expect(
    await phone.evaluate(() => sessionStorage.getItem("cleanrep.session")),
  ).toBeNull();
  expect(
    await phone.evaluate(() => sessionStorage.getItem("fake.native.session")),
  ).toContain('"role":"phone"');

  await choose(tv, "Start set");
  await phone.locator("#fixture").selectOption("squat_good_10");
  await expect.poll(async () => (await state(tv)).data.phase).toBe("IN_SET");
  await expect(phone.locator("#status")).toHaveText("Ready to run the replay.");
  await phone.waitForTimeout(1100);
  await phone.getByRole("button", { name: "Run replay" }).click();
  await expect(phone.locator("#mirror-indicator")).toBeVisible();
  await expect(tv.locator("[data-mirror-panel] img")).toHaveAttribute(
    "src",
    /^blob:/,
  );
  // The frames arrive whole: the TV decodes the JPEG that went through base64 and the bridge.
  await expect
    .poll(async () =>
      tv
        .locator("[data-mirror-panel] img")
        .evaluate((el: HTMLImageElement) => el.naturalWidth),
    )
    .toBeGreaterThan(0);
  await expect(
    tv.getByRole("heading", { name: /Set complete|Re-test complete/ }),
  ).toBeVisible({ timeout: 15000 });
  await phone.waitForTimeout(500);

  const log = (await phone.evaluate(
    () => (window as unknown as { __bridge: BridgeLog }).__bridge,
  )) as BridgeLog;
  expect(log.mirror.length).toBeGreaterThan(0);
  expect(log.mirror.length).toBe(mirrorPosts.length);
  for (const frame of log.mirror) {
    expect(frame.path).toMatch(/^\/v1\/sessions\/[^/]+\/mirror$/);
    expect(frame.head).toEqual([0xff, 0xd8, 0xff]);
    expect(frame.bytes).toBeLessThanOrEqual(150 * 1024);
  }
  for (const post of mirrorPosts) {
    expect(post.url).toMatch(/\/v1\/sessions\/[^/]+\/mirror$/);
    expect(post.contentType).toBe("image/jpeg");
  }
  // Pairing, the poll, reps and the heartbeat all used requestAsync.
  expect(log.asyncCalls).toBeGreaterThan(10);
  // The only page-level fetch under /v1/ is the replay fixture, which is a test-only route.
  const direct = log.pageFetches.filter((url) => url.includes("/v1/"));
  expect(direct.length).toBeGreaterThan(0);
  for (const url of direct) expect(url).toMatch(/^\/v1\/replay\/[a-z0-9_]+$/);
  await context.close();
});
test("J-7b a home-mode QR opened in Chrome shows only the landing page: the intent link, no API, no camera, no fragment", async ({
  browser,
  baseURL,
}) => {
  const server = serverAt(baseURL);
  const context = await browser.newContext(),
    phone = await context.newPage();
  const fp = "A".repeat(20) + "b_-".repeat(7) + "Z-";
  expect(fp).toHaveLength(43);
  const requests: string[] = [];
  phone.on("request", (r) => requests.push(r.url()));
  await phone.goto(`/phone#code=123456&fp=${fp}`);
  await expect(
    phone.getByRole("heading", { name: "Open CleanRep on your phone" }),
  ).toBeVisible();
  const apk = server.origin + "/cleanrep-phone.apk";
  await expect(
    phone.getByRole("link", { name: "Open in the CleanRep app" }),
  ).toHaveAttribute(
    "href",
    `intent://pair?host=${server.hostname}&port=8443&code=123456&fp=${fp}` +
      "#Intent;scheme=cleanrep;package=org.cleanrep.phone;" +
      `S.browser_fallback_url=${encodeURIComponent(apk)};end`,
  );
  await expect(
    phone.getByRole("link", { name: "Download the app" }),
  ).toHaveAttribute("href", apk);
  await expect(phone.getByText(/No app yet\?/)).toBeVisible();
  // The fragment is gone from the address, and nothing else is on the page.
  expect(new URL(phone.url()).hash).toBe("");
  await expect(phone.locator("#code")).toHaveCount(0);
  await phone.waitForTimeout(1500);
  expect(requests.filter((url) => url.includes("/v1/"))).toEqual([]);
  expect(requests.filter((url) => /pose-worker|\/models\//.test(url))).toEqual(
    [],
  );

  // A link without a fingerprint (from `make server`) opens the phone page as before, with the code filled in.
  // A fragment-only change would not reload the page, so start from a blank one.
  await phone.goto("about:blank");
  await phone.goto("/phone#code=654321");
  await expect(
    phone.getByRole("heading", { name: "Pair with your TV" }),
  ).toBeVisible();
  await expect(phone.locator("#code")).toHaveValue("654321");
  await expect(
    phone.getByRole("link", { name: "Open in the CleanRep app" }),
  ).toHaveCount(0);
  // The phone app with no TV set yet: no API call, an address field, and a link is checked before the app is asked.
  const fresh = await context.newPage();
  await fresh.addInitScript(() => {
    Object.assign(window, {
      CleanRepNative: {
        apiOrigin: () => sessionStorage.getItem("fake.server") ?? "",
        setServer: (host: string, port: number) => {
          sessionStorage.setItem("fake.server", `http://${host}:${port}`);
          return true;
        },
        saveSession() {},
        loadSession: () => "",
        clearSession() {},
        requestAsync() {},
      },
    });
  });
  const freshRequests: string[] = [];
  fresh.on("request", (r) => freshRequests.push(r.url()));
  await fresh.goto("/phone");
  await expect(
    fresh.getByRole("heading", { name: "Scan the QR code on your TV" }),
  ).toBeVisible();
  await expect(
    fresh.getByText(
      "Scan the QR code with your phone's camera. Chrome opens; tap Open in the CleanRep app.",
    ),
  ).toBeVisible();
  await fresh.getByLabel("Or paste the link from your TV").fill("8.8.8.8:80");
  await fresh.getByRole("button", { name: "Connect", exact: true }).click();
  await expect(fresh.getByRole("alert")).toHaveText(
    "That link isn't from a CleanRep TV on this network.",
  );
  await fresh
    .getByLabel("Or paste the link from your TV")
    .fill(`http://localhost:${server.port}/phone#code=123456`);
  await fresh.getByRole("button", { name: "Connect", exact: true }).click();
  await expect(fresh.locator("#code")).toHaveValue("123456");
  await expect(fresh.locator(".tv-at")).toHaveText("TV at localhost");
  expect(freshRequests.filter((url) => url.includes("/v1/"))).toEqual([]);
  // Use another TV goes back to the address field.
  await fresh.getByRole("button", { name: "Use another TV" }).click();
  await expect(
    fresh.getByRole("heading", { name: "Scan the QR code on your TV" }),
  ).toBeVisible();
  await context.close();
});
