// Adapted from RoomQuest (MIT); local assets only, with a pinned model checksum.
import { cp, mkdir, readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
const provenance = JSON.parse(
  await readFile("public/models/provenance.json", "utf8"),
);
const model = await readFile("public/models/" + provenance.asset);
if (createHash("sha256").update(model).digest("hex") !== provenance.sha256)
  throw new Error("Model checksum mismatch");
await mkdir("public/vision/wasm", { recursive: true });
await cp("node_modules/@mediapipe/tasks-vision/wasm", "public/vision/wasm", {
  recursive: true,
});
await cp(
  "node_modules/@mediapipe/tasks-vision/vision_bundle.mjs",
  "public/vision/vision_bundle.mjs",
);
