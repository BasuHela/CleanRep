/* The phone app's "which TV?" screen: shown until a TV is set, and again from "Use another TV". */
import { pairTarget } from "./policy";

export const LINK_REJECTED =
  "That link isn't from a CleanRep TV on this network.";

type ServerBridge = { setServer?(h: string, p: number, f: string): boolean };

/** Sets the TV from a pasted link; `code` is what to fill in on the pairing screen ("" when the link had none). */
export function connectTv(
  text: string,
  bridge: ServerBridge | undefined,
): { ok: true; code: string } | { ok: false } {
  const target = pairTarget(text);
  if (!target || !bridge?.setServer) return { ok: false };
  let accepted = false;
  try {
    accepted = bridge.setServer(target.host, target.port, target.fp) === true;
  } catch {
    /* A bridge that throws counts as a rejection. */
  }
  return accepted ? { ok: true, code: target.code } : { ok: false };
}

/** Reloads on the pairing screen: the page reads the TV's address once, when it starts. */
function openPairing(code: string): void {
  history.replaceState(null, "", "/phone" + (code ? "#code=" + code : ""));
  location.reload();
}

export function serverEntry(root: HTMLElement): void {
  root.innerHTML = `<header class="bar"><a class="brand" href="/phone">Clean<span>Rep</span></a><span class="link">Not paired</span></header><main class="phone"><h1>Scan the QR code on your TV</h1><p id="status">Scan the QR code with your phone's camera. Chrome opens; tap Open in the CleanRep app.</p><form id="server" class="stage" novalidate><label class="paste" for="tv-link">Or paste the link from your TV</label><input id="tv-link" type="text" inputmode="url" autocapitalize="off" autocomplete="off" autocorrect="off" spellcheck="false"><p id="server-error" class="small" role="alert"></p><div class="dock"><button type="submit" class="primary">Connect</button></div></form></main>`;
  const input = root.querySelector<HTMLInputElement>("#tv-link")!,
    error = root.querySelector<HTMLElement>("#server-error")!;
  root.querySelector<HTMLFormElement>("#server")!.onsubmit = (event) => {
    event.preventDefault();
    const result = connectTv(input.value, window.CleanRepNative);
    if (result.ok) openPairing(result.code);
    else error.textContent = LINK_REJECTED;
  };
}
