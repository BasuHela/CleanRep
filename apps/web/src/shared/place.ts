/** Where the CleanRep server runs, read from its origin. Privacy and connection wording depend on it. */
export type ServerPlace = "home" | "online" | "tv";

/** A private LAN IPv4 address (10/8, 172.16/12, 192.168/16). */
export function lanIPv4(hostname: string): boolean {
  const m = /^(\d+)\.(\d+)\.\d+\.\d+$/.exec(hostname);
  if (!m) return false;
  const [a, b] = [Number(m[1]), Number(m[2])];
  return (
    a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168)
  );
}

/** The parts of `window.CleanRepNative` that say where the server runs. */
type PlaceBridge = {
  setServer?: unknown;
  serverPlace?: () => string;
} | null;

/** "tv": the server is the Fire TV app (the TV app says so; the phone app reaches it over HTTPS);
 * "home": the server on a computer (`make server`: loopback, the emulator's 10.0.2.2, a LAN address);
 * "online": any other host. Anything unreadable counts as online, so no text over-promises. */
export function serverPlace(origin: string, bridge?: PlaceBridge): ServerPlace {
  if (bridge?.serverPlace?.() === "tv") return "tv";
  let url: URL;
  try {
    url = new URL(origin);
  } catch {
    return "online";
  }
  if (bridge?.setServer && url.protocol === "https:") return "tv";
  const host = url.hostname;
  if (host === "localhost" || host.startsWith("127.") || lanIPv4(host))
    return "home";
  return "online";
}
