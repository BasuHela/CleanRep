/**
 * The emulator's TV on the laptop's Wi-Fi, so a phone with no debugging turned on can reach it (device test 3b).
 *
 * The emulator's own address (10.0.2.x) is private to the laptop. `adb forward` brings its 8105 and 8443 to the
 * laptop's 127.0.0.1, and this relay puts them on the laptop's Wi-Fi address:
 * - 8443: TCP passed through untouched, so the phone's TLS stays pinned end to end to the TV's certificate;
 * - 8105: GET and HEAD for the pages and the phone APK only. `/v1/*` is refused here, because every connection
 *   through `adb forward` reaches the TV from loopback, and the TV lets loopback use the API over plain HTTP.
 *
 * Usage: node scripts/lan-relay.mjs [wifi-ip]   (default: this laptop's Wi-Fi address)
 */
import http from "node:http";
import net from "node:net";
import os from "node:os";
import { pathToFileURL } from "node:url";

/** The phone app's rule (PhonePolicy.isPrivateNonLoopbackHost): a strict dotted quad in 10/8, 172.16/12 or 192.168/16. */
export function isPrivateIPv4(host) {
  if (typeof host !== "string" || !/^(0|[1-9]\d{0,2})(\.(0|[1-9]\d{0,2})){3}$/.test(host)) return false;
  const [a, b] = host.split(".").map(Number);
  if (host.split(".").some((part) => Number(part) > 255)) return false;
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

/** This machine's private IPv4 address that phones dial: Wi-Fi first, then Ethernet, as the TV's Listeners.lanAddress. */
export function lanAddress(interfaces = os.networkInterfaces()) {
  const rank = (name) =>
    name.startsWith("wl") ? 0 : name.startsWith("eth") || name.startsWith("en") ? 1 : /^(docker|br-|veth|virbr)/.test(name) ? 3 : 2;
  let best = null;
  let bestRank = Infinity;
  for (const [name, addresses] of Object.entries(interfaces)) {
    const address = (addresses ?? []).find((a) => a.family === "IPv4" && !a.internal && isPrivateIPv4(a.address));
    if (address && rank(name) < bestRank) {
      best = address.address;
      bestRank = rank(name);
    }
  }
  return best;
}

/** The TV's own rule (Router): a request is for the API when its percent-decoded path starts with /v1/. Null: undecodable. */
export function isApiTarget(target) {
  const path = target.split("?")[0];
  let decoded;
  try {
    decoded = decodeURIComponent(path);
  } catch {
    return null;
  }
  return decoded.startsWith("/v1/");
}

const HOP_BY_HOP = new Set(["connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade"]);
const endToEnd = (headers) => Object.fromEntries(Object.entries(headers).filter(([k]) => !HOP_BY_HOP.has(k.toLowerCase())));

function refuse(res, status, code, extra = {}) {
  const body = JSON.stringify({ status: "rejected", version: null, data: {}, error: { code, message: code.replace(/_/g, " ") } });
  res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store", ...extra });
  res.end(body);
}

function listen(server, port, host) {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, host, () => {
      server.off("error", reject);
      resolve(server.address().port);
    });
  });
}

/**
 * Starts both relays on `host`. Ports 0 pick free ones (tests). Resolves to the bound ports and a close().
 * `upstreamHost` is where `adb forward` listens, 127.0.0.1.
 */
export async function startRelay({ host, httpPort = 8105, httpsPort = 8443, upstreamHost = "127.0.0.1", upstreamHttpPort = 8105, upstreamHttpsPort = 8443, log = () => {} }) {
  const sockets = new Set();
  const track = (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  };

  const pages = http.createServer((req, res) => {
    const api = req.url.startsWith("/") ? isApiTarget(req.url) : null;
    if (api === null) return refuse(res, 400, "bad_request");
    if (api) return refuse(res, 403, "https_required");
    if (req.method !== "GET" && req.method !== "HEAD") return refuse(res, 405, "method_not_allowed", { Allow: "GET, HEAD" });
    const upstream = http.request(
      { host: upstreamHost, port: upstreamHttpPort, method: req.method, path: req.url, headers: endToEnd(req.headers) },
      (up) => {
        res.writeHead(up.statusCode ?? 502, endToEnd(up.headers));
        up.pipe(res);
      },
    );
    upstream.on("error", (error) => {
      log(`relay: 8105 upstream: ${error.message} (is adb forward tcp:${upstreamHttpPort} running?)`);
      if (!res.headersSent) refuse(res, 502, "tv_unreachable");
      else res.destroy();
    });
    req.pipe(upstream);
  });
  pages.on("connection", track);

  const secure = net.createServer((client) => {
    track(client);
    const tv = net.connect(upstreamHttpsPort, upstreamHost);
    track(tv);
    const drop = (error) => {
      if (error) log(`relay: 8443: ${error.message}`);
      client.destroy();
      tv.destroy();
    };
    client.on("error", drop);
    tv.on("error", drop);
    client.on("close", () => tv.destroy());
    tv.on("close", () => client.destroy());
    client.pipe(tv);
    tv.pipe(client);
  });

  const boundHttp = await listen(pages, httpPort, host);
  let boundHttps;
  try {
    boundHttps = await listen(secure, httpsPort, host);
  } catch (error) {
    pages.close();
    throw error;
  }
  return {
    httpPort: boundHttp,
    httpsPort: boundHttps,
    close: () =>
      new Promise((resolve) => {
        for (const socket of sockets) socket.destroy();
        let open = 2;
        const done = () => --open === 0 && resolve();
        pages.close(done);
        secure.close(done);
      }),
  };
}

async function main() {
  const host = process.argv[2] ?? lanAddress();
  if (!isPrivateIPv4(host)) {
    console.error(host ? `lan-relay: ${host} is not a private IPv4 address` : "lan-relay: no Wi-Fi address found; pass it: node scripts/lan-relay.mjs <wifi-ip>");
    process.exit(2);
  }
  let relay;
  try {
    relay = await startRelay({ host, log: (line) => console.error(line) });
  } catch (error) {
    console.error(`lan-relay: cannot listen on ${host}: ${error.message}`);
    process.exit(1);
  }
  console.log(`lan-relay: the emulator's TV is at http://${host}:${relay.httpPort}/phone (pages and the APK; /v1 refused) and ${host}:${relay.httpsPort} (pinned HTTPS, passed through)`);
  console.log(`lan-relay: launch the TV app with: adb -s emulator-5554 shell am start -S -n org.cleanrep.tv/.MainActivity --es cleanrep.lan_host ${host}`);
  console.log("lan-relay: Ctrl-C stops it");
  const stop = () => relay.close().then(() => process.exit(0));
  process.on("SIGINT", stop);
  process.on("SIGTERM", stop);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) main();
