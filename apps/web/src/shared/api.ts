/* Native bridge and scoped session storage adapted from RoomQuest (MIT). */
import type { ApiEnvelope, SavedSession } from "./views";
declare global {
  interface Window {
    CleanRepNative?: {
      requestAsync(
        id: string,
        method: string,
        path: string,
        body: string,
        token: string,
      ): void;
      saveSession(value: string): void;
      loadSession(): string;
      clearSession(): void;
      apiOrigin(): string;
      exitApp?(): void;
      /** Phone app only: pins the TV; false means the app rejected the address. */
      setServer?(host: string, port: number, fingerprint: string): boolean;
      /** Phone app only: one camera frame (standard base64 JPEG) for `/v1/sessions/<id>/mirror`. */
      requestMirror?(
        id: string,
        path: string,
        jpegBase64: string,
        token: string,
      ): void;
      /** TV app only: "tv" when the session server runs inside this app. */
      serverPlace?(): string;
      /** TV app only: the pairing certificate's SHA-256 as 43 base64url characters, or "". */
      tlsFingerprint?(): string;
    };
  }
}
/** The page's own 13 s timeout text; the phone app's envelopes never carry it. */
export const TIMEOUT_MESSAGE =
  "Connection timed out. Retry to recover the saved action.";
export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
  ) {
    super(message);
  }
}
const pending = new Map<
  string,
  {
    resolve: (value: unknown) => void;
    reject: (e: Error) => void;
    timer: ReturnType<typeof setTimeout>;
  }
>();
if (typeof window !== "undefined")
  window.addEventListener("cleanrep-native-response", (event) => {
    const detail = (event as CustomEvent).detail;
    if (
      !detail ||
      typeof detail.id !== "string" ||
      typeof detail.payload !== "string"
    )
      return;
    const p = pending.get(detail.id);
    if (!p) return;
    clearTimeout(p.timer);
    pending.delete(detail.id);
    try {
      p.resolve(JSON.parse(detail.payload));
    } catch {
      p.reject(new Error("Unreadable TV response"));
    }
  });
export const key = (): string =>
  crypto.randomUUID?.() ??
  Array.from(crypto.getRandomValues(new Uint8Array(16)), (x) =>
    x.toString(16).padStart(2, "0"),
  ).join("");
/**
 * Registers `id` for the bridge's answer before the call is made, so an answer that comes straight back is not missed.
 * `forget()` drops the entry at once and settles the promise with `null`; a late answer then finds nothing.
 */
export function awaitNative(
  id: string,
  timeoutMs: number,
  onTimeout: () => Error,
): { promise: Promise<unknown>; forget: () => void } {
  let forget = () => {};
  const promise = new Promise<unknown>((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(onTimeout());
    }, timeoutMs);
    pending.set(id, { resolve, reject, timer });
    forget = () => {
      if (!pending.delete(id)) return;
      clearTimeout(timer);
      resolve(null);
    };
  });
  return { promise, forget };
}
export async function request<T>(
  path: string,
  body?: unknown,
  token?: string,
  method = body === undefined ? "GET" : "POST",
): Promise<ApiEnvelope<T>> {
  let raw: unknown;
  if (window.CleanRepNative) {
    const id = key();
    const answer = awaitNative(
      id,
      13000,
      () => new ApiError("unavailable", TIMEOUT_MESSAGE),
    );
    try {
      window.CleanRepNative.requestAsync(
        id,
        method,
        path,
        body === undefined ? "" : JSON.stringify(body),
        token ?? "",
      );
    } catch (err) {
      answer.forget();
      throw err;
    }
    raw = await answer.promise;
  } else {
    const response = await fetch(path, {
      method,
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: "Bearer " + token } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: "no-store",
      signal: AbortSignal.timeout(13000),
    });
    raw = await response.json();
  }
  if (!raw || typeof raw !== "object" || !("status" in raw) || !("data" in raw))
    throw new ApiError(
      "invalid_response",
      "The server response was unreadable.",
    );
  const result = raw as ApiEnvelope<T>;
  if (result.status !== "ok")
    throw new ApiError(
      result.error?.code ?? result.status,
      result.error?.message ?? "Action unavailable.",
    );
  return result;
}
export function saveSession(s: SavedSession): void {
  const text = JSON.stringify(s);
  if (window.CleanRepNative) window.CleanRepNative.saveSession(text);
  else sessionStorage.setItem("cleanrep.session", text);
}
export function readSession(role: SavedSession["role"]): SavedSession | null {
  try {
    const raw =
      window.CleanRepNative?.loadSession() ??
      sessionStorage.getItem("cleanrep.session");
    const s = JSON.parse(raw ?? "null");
    return s?.role === role &&
      typeof s.token === "string" &&
      typeof s.session_id === "string"
      ? s
      : null;
  } catch {
    return null;
  }
}
export function clearSession(): void {
  window.CleanRepNative?.clearSession();
  sessionStorage.removeItem("cleanrep.session");
}
export const escape = (text: unknown): string =>
  String(text ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
