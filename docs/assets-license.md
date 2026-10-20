# Asset and code licenses

CleanRep's own code is MIT. It adapts code from two earlier projects of ours, whose MIT notices are kept.

| Source | What CleanRep uses | Notice / provenance |
|---|---|---|
| RoomQuest | The Java TV shell, the store with compare-and-swap, receipts, the camera, worker, focus and API patterns | MIT notice in `LICENSE` and attribution comments |
| ReplayLab | Grounded wording and selection patterns; the Gradle wrapper (TV app, and the same files in the phone app) | MIT notice in `LICENSE.replaylab`; the wrapper's Apache 2.0 header is kept |
| Project Nayuki QR Code generator library (TypeScript) | Adapted and condensed into `apps/web/src/shared/qr.ts` (byte mode, level M, versions 1–10) for the TV's pairing QR code | MIT; copyright and permission notice kept in the file header |
| MediaPipe | `@mediapipe/tasks-vision` 0.10.32 and the PoseLandmarker Lite float16 model, version 1 | Apache 2.0; `apps/web/public/models/LICENSE.apache-2.0.txt` and `provenance.json` |
| MakeHuman system assets (CC0-1.0) | The demonstrator body, skin `middleage_african_female`, `female_sportsuit01`, `shoes06`, `ponytail01`, `eyebrow001` | "All core assets (the base mesh, targets, skins…) are shared under CC0" ([MPFB FAQ](https://static.makehumancommunity.org/mpfb/faq/can_i_sell_models.html)); the pack page lists every item as CC0 ([asset pack](https://static.makehumancommunity.org/assets/assetpacks/makehuman_system_assets.html)) |
| MPFB 2.0.18 (GPL-3.0-or-later), Blender `bpy` 4.5.14 (GPL) | Build tools that posed and rendered the demonstrator | Used only to render, in the development repo; no GPL code or Blender files are shipped, only the rendered MP4 and JPEG files |
| CleanRep | Exercise demonstrations and posters (`apps/web/public/demos/*.mp4`, `*.jpg`) | Side-view Cycles renders of the synthetic CC0 demonstrator (not a real person, no stock footage); hashes and inputs in `apps/web/public/demos/provenance.json` |
| CleanRep | The phone app's launcher icon (`apps/phone/android/app/src/main/res/drawable/icon.xml`) | An original vector of four plain shapes |
| Figtree (SIL OFL 1.1, © 2022 The Figtree Project Authors) | One variable-weight (400–800) Latin-subset WOFF2, `apps/web/public/fonts/figtree-latin.woff2` (20 KB) | Licence text in `apps/web/public/fonts/OFL.txt`. Served from the app itself; no font CDN at runtime |

The Lite model's SHA-256 is `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a`. The web build checks it before it copies the installed MediaPipe JS and wasm next to the pages, so nothing loads from a CDN at run time. The replay fixtures (`fixtures/landmarks/`, `evals/fixtures/`) are generated geometry, not camera recordings of anyone.
