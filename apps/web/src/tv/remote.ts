/* What the remote's Back key does on each TV screen, and the footer hints that describe it. */
export type BackAction =
  | "close-drawer"
  | "close-about"
  | "pause"
  | "resume"
  | "exit";
type BackState = {
  drawer: boolean;
  about: boolean;
  view?: { phase: string; paused: boolean } | null;
};
// Screens with nothing to pause: Back leaves the app, as on any Fire TV top-level screen.
const TOP_LEVEL_PHASES = ["LOBBY", "ENDED"];
export function backAction({ drawer, about, view }: BackState): BackAction {
  if (drawer) return "close-drawer";
  if (about) return "close-about";
  if (!view || TOP_LEVEL_PHASES.includes(view.phase)) return "exit";
  return view.paused ? "resume" : "pause";
}
// Only the keys whose effect changes: the arrows and Select need no legend on a TV.
export function footerHint(state: BackState): string[] {
  const back = {
    "close-drawer": "Back to close",
    "close-about": "Back to return",
    pause: "Back to pause",
    resume: "Back to resume",
    exit: "Back to exit",
  }[backAction(state)];
  return state.view ? [back, "Menu for details"] : [back];
}
