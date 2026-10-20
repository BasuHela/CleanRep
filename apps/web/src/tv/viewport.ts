/* The TV UI is designed on a fixed 1920x1080 CSS canvas. Fire TV / Android TV WebViews report a 960x540 CSS
   viewport at 2x density (measured on the Android TV API 30 emulator), so the TV route asks for a 1920px-wide
   layout; the native shell enables wide viewport + overview mode to scale it to the screen. Desktop browsers
   ignore the meta width, and the phone route never calls this. */
export const TV_CANVAS_WIDTH = 1920;
export function applyTvViewport(doc: Document): void {
  let meta = doc.querySelector<HTMLMetaElement>('meta[name="viewport"]');
  if (!meta) {
    meta = doc.createElement("meta");
    meta.name = "viewport";
    doc.head.appendChild(meta);
  }
  meta.content = `width=${TV_CANVAS_WIDTH}, user-scalable=no`;
}
