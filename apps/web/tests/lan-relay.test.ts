import http from "node:http";
import net from "node:net";
import type { AddressInfo } from "node:net";
import { afterEach, expect, test } from "vitest";
import {
  isApiTarget,
  isPrivateIPv4,
  lanAddress,
  startRelay,
} from "../../../scripts/lan-relay.mjs";

const closers: (() => Promise<unknown>)[] = [];
afterEach(async () => {
  while (closers.length) await closers.pop()!();
});

const port = (server: net.Server) => (server.address() as AddressInfo).port;
const listening = <T extends net.Server>(server: T) =>
  new Promise<T>((resolve) => {
    server.listen(0, "127.0.0.1", () => resolve(server));
    closers.push(
      () => new Promise((done) => server.close(() => done(undefined))),
    );
  });

/** A raw request, so targets reach the relay exactly as written (fetch would normalize them). */
const request = (p: number, method: string, path: string) =>
  new Promise<{ status: number; body: string }>((resolve, reject) => {
    const req = http.request(
      { host: "127.0.0.1", port: p, method, path, agent: false },
      (res) => {
        let body = "";
        res.on("data", (chunk) => (body += chunk));
        res.on("end", () => resolve({ status: res.statusCode ?? 0, body }));
      },
    );
    req.on("error", reject);
    req.end();
  });

async function relayWithTv() {
  const seen: string[] = [];
  const tvPages = await listening(
    http.createServer((req, res) => {
      seen.push(`${req.method} ${req.url}`);
      res.writeHead(200, { "Content-Type": "text/html" });
      res.end("tv page");
    }),
  );
  const tvSecure = await listening(
    net.createServer((socket) => socket.pipe(socket)),
  );
  const relay = await startRelay({
    host: "127.0.0.1",
    httpPort: 0,
    httpsPort: 0,
    upstreamHttpPort: port(tvPages),
    upstreamHttpsPort: port(tvSecure),
  });
  closers.push(relay.close);
  return { relay, seen };
}

test("W-RLY-1 only private IPv4 literals, by the phone app's rule", () => {
  for (const ok of [
    "10.217.25.162",
    "172.16.0.1",
    "172.31.255.255",
    "192.168.1.20",
    "10.0.0.0",
  ])
    expect(isPrivateIPv4(ok), ok).toBe(true);
  for (const bad of [
    "172.15.0.1",
    "172.32.0.1",
    "192.169.1.1",
    "127.0.0.1",
    "8.8.8.8",
    "010.1.1.1",
    "10.1.1",
    "10.256.1.1",
    "10.1.1.1 ",
    "localhost",
    "",
    null,
  ])
    expect(isPrivateIPv4(bad), String(bad)).toBe(false);
});

test("W-RLY-2 the Wi-Fi address is preferred over Ethernet, and bridges and public addresses are skipped", () => {
  const nic = (address: string, internal = false) => [
    { address, family: "IPv4", internal, netmask: "", mac: "", cidr: null },
  ];
  expect(
    lanAddress({
      lo: nic("127.0.0.1", true),
      docker0: nic("172.17.0.1"),
      enp2s0: nic("192.168.1.5"),
      wlp3s0: nic("10.217.25.162"),
    } as never),
  ).toBe("10.217.25.162");
  expect(
    lanAddress({ docker0: nic("172.17.0.1"), eth0: nic("8.8.8.8") } as never),
  ).toBe("172.17.0.1");
  expect(lanAddress({ lo: nic("127.0.0.1", true) } as never)).toBe(null);
});

test("W-RLY-3 the API test decodes the path as the TV's Router does", () => {
  for (const api of [
    "/v1/healthz",
    "/%761/healthz",
    "/v1%2Fhealthz",
    "/v1/sessions?x=1",
  ])
    expect(isApiTarget(api), api).toBe(true);
  for (const page of [
    "/phone",
    "/v1",
    "//v1/healthz",
    "/x?/v1/healthz",
    "/V1/healthz",
  ])
    expect(isApiTarget(page), page).toBe(false);
  expect(isApiTarget("/%E0%A4%A")).toBe(null);
});

test("W-RLY-4 pages and the APK pass through; the API never reaches the TV over plain HTTP", async () => {
  const { relay, seen } = await relayWithTv();
  expect(await request(relay.httpPort, "GET", "/phone")).toEqual({
    status: 200,
    body: "tv page",
  });
  expect(
    (await request(relay.httpPort, "HEAD", "/cleanrep-phone.apk")).status,
  ).toBe(200);
  for (const target of ["/v1/healthz", "/%761/healthz", "/v1%2Fsessions"]) {
    const r = await request(relay.httpPort, "GET", target);
    expect(r.status, target).toBe(403);
    expect(JSON.parse(r.body).error.code).toBe("https_required");
  }
  expect((await request(relay.httpPort, "POST", "/phone")).status).toBe(405);
  expect((await request(relay.httpPort, "GET", "/%E0%A4%A")).status).toBe(400);
  expect(seen).toEqual(["GET /phone", "HEAD /cleanrep-phone.apk"]);
});

test("W-RLY-5 the HTTPS port passes bytes through untouched, both ways", async () => {
  const { relay } = await relayWithTv();
  const bytes = Buffer.from([0x16, 0x03, 0x01, 0x00, 0xff, 0x00, 0x0a]);
  const echoed = await new Promise<Buffer>((resolve, reject) => {
    const socket = net.connect(relay.httpsPort, "127.0.0.1", () =>
      socket.write(bytes),
    );
    socket.once("data", (data) => {
      socket.destroy();
      resolve(data);
    });
    socket.on("error", reject);
  });
  expect(echoed.equals(bytes)).toBe(true);
});

test("W-RLY-6 with no TV behind it, pages answer 502 and the HTTPS port closes", async () => {
  const free = await listening(net.createServer());
  const dead = port(free);
  await closers.pop()!();
  const lines: string[] = [];
  const relay = await startRelay({
    host: "127.0.0.1",
    httpPort: 0,
    httpsPort: 0,
    upstreamHttpPort: dead,
    upstreamHttpsPort: dead,
    log: (line) => lines.push(line),
  });
  closers.push(relay.close);
  const r = await request(relay.httpPort, "GET", "/phone");
  expect(r.status).toBe(502);
  expect(JSON.parse(r.body).error.code).toBe("tv_unreachable");
  await new Promise<void>((resolve) => {
    const socket = net.connect(relay.httpsPort, "127.0.0.1");
    socket.on("close", () => resolve());
    socket.on("error", () => undefined);
  });
  expect(lines.some((l) => l.includes("adb forward"))).toBe(true);
});
