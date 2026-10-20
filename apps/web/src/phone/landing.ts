/* Chrome landing page for a home-mode QR code: one button that opens the phone app, no API, camera or session. */
import { pairIntent } from "./policy";
import { escape as e } from "../shared/api";

/** Shows the landing view and removes the fragment from the address; the code and fingerprint live on in the link. */
export function landing(
  root: HTMLElement,
  pair: { code: string; fp: string },
): void {
  const apkUrl = location.origin + "/cleanrep-phone.apk",
    intent = pairIntent({
      host: location.hostname,
      code: pair.code,
      fp: pair.fp,
      apkUrl,
    });
  history.replaceState(null, "", location.pathname + location.search);
  root.innerHTML = `<header class="bar"><a class="brand" href="/phone">Clean<span>Rep</span></a></header><main class="phone landing"><h1>Open CleanRep on your phone</h1><div class="stage"><a id="open-app" class="button primary" href="${e(intent)}">Open in the CleanRep app</a><p class="small">No app yet? Tapping the button downloads it from your TV. Allow Chrome to install apps when Android asks, install CleanRep, then scan the QR code again.</p><p class="small"><a id="download-app" href="${e(apkUrl)}">Download the app</a></p></div></main>`;
}
