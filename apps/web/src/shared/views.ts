import type {
  Block,
  Profile,
  Explanation,
  CoachNotice,
} from "../../../../packages/contracts/generated";
import type { Catalog } from "../pose/types";
export type SavedSession = {
  session_id: string;
  token: string;
  role: "tv" | "phone";
};
export type PlanBlock = Block & { block_id: string };
export type Summary = {
  set_id: string;
  exercise_id: string;
  reps_counted: number;
  rated_reps: number;
  faults: Record<string, number>;
  dominant_fault: string | null;
  drill_offer: { drill_id: string | null; reason?: string } | null;
  comparison: {
    before_count: number;
    after_count: number;
    before_rated: number;
    after_rated: number;
    verdict: string;
    enough_data: boolean;
  } | null;
  hold?: { rated: boolean; aligned_s: number; total_s: number };
  /** One mark per rep of the set, in order; empty for a hold or a remote-only set. */
  rep_marks?: RepMark[];
};
/** One rep on the TV tally. */
export type RepMark = "clean" | "fault" | "unrated";
export type View = {
  session_id: string;
  phase: string;
  paused: boolean;
  coach_notice?: CoachNotice | null;
  sensor_mode: "paired" | "remote_only";
  active_set: {
    set_id: string;
    exercise_id: string;
    target: number;
    kind: "hold" | "reps";
    reps_counted: number;
    rated: boolean;
    hold?: unknown;
  } | null;
  catalog?: Catalog;
  profile: Required<Profile>;
  plan: {
    blocks: PlanBlock[];
    source: string;
    reason: string | null;
    seconds: number;
  } | null;
  summary: Summary | null;
  explanation: (Explanation & { source: string }) | null;
  recap: {
    estimated_movement_min: number;
    sets_done: number;
    reps_counted: number;
    rated_reps: number;
    comparisons: Summary["comparison"][];
    next_time: string;
  } | null;
  block_index: number;
  set_index: number;
  pairing_code: string;
  /** The address a Wi-Fi phone opens, when the server knows it (the TV app names its Wi-Fi address). */
  phone_url: string | null;
  pairing_requests: {
    request_id: string;
    verification_code: string;
    approved: boolean;
  }[];
  /** Present only in READY; fps and delegate stay server-side. */
  camera_check: {
    full_body: boolean;
    side_view: boolean;
    light: boolean;
  } | null;
  phone_connected: boolean;
  /** A camera check arrived in the last 4 s: the phone camera is running. */
  phone_sensing: boolean;
  /** A fresh mirror frame (< 2 s old) is held in server memory. */
  mirror_live: boolean;
  /** Every rep of the active set for the tally (TV view only); null outside a set. */
  rep_marks?: RepMark[] | null;
  /** Latest rep in the active set (TV view only), for the live rep checkmark or fault marker. */
  last_rep: {
    index: number;
    rated: boolean;
    faults: string[];
  } | null;
  completed_sets: Summary[];
  traces: Record<string, unknown>[];
};
export type ApiEnvelope<T> = {
  status: string;
  version: number;
  data: T;
  error: { code: string; message: string } | null;
};
