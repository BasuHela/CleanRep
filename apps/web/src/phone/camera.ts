/* Camera lifecycle adapted from RoomQuest (MIT). No upload path exists here. */
import type { Frame } from "../pose/types";
export class LocalCamera {
  private stream: MediaStream | null = null;
  private worker: Worker | null = null;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private stopped = true;
  private generation = 0;
  private wake: WakeLockSentinel | null = null;
  /** The rear camera the user picked; reopening keeps it until another is chosen ("" = default). */
  lens: string | undefined;
  private onHidden = () => {
    if (document.visibilityState === "hidden") this.stop();
  };
  private onLeave = () => this.stop();
  /** The phone app went to the background (not for the Android camera permission dialog). */
  private onNativePause = () => this.stop();
  constructor(
    private video: HTMLVideoElement,
    private result: (
      frame: Frame,
      delegate: "GPU" | "CPU",
      inferenceMs: number,
    ) => void,
    private status: (text: string) => void,
    private delegate: "GPU" | "CPU" = "GPU",
    private onStop: () => void = () => {},
  ) {
    window.addEventListener("pagehide", this.onLeave);
    document.addEventListener("visibilitychange", this.onHidden);
    document.addEventListener("cleanrep-native-pause", this.onNativePause);
  }
  async start(deviceId?: string): Promise<void> {
    if (!window.isSecureContext)
      throw new Error(
        "The camera needs the CleanRep phone app. Scan the QR code on your TV, then tap Open in the CleanRep app.",
      );
    if (!navigator.mediaDevices?.getUserMedia)
      throw new Error(
        "This browser can't use the camera. Select Use remote only on the TV.",
      );
    if (deviceId !== undefined) this.lens = deviceId || undefined;
    this.stop();
    this.stopped = false;
    const generation = this.generation;
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: {
          facingMode: "environment",
          width: { ideal: 1280 },
          height: { ideal: 720 },
          frameRate: { ideal: 30 },
          ...(this.lens ? { deviceId: { exact: this.lens } } : {}),
        },
      });
      if (this.stopped || generation !== this.generation) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }
      this.stream = stream;
      stream.getTracks().forEach((track) =>
        track.addEventListener("ended", () => {
          this.stop();
          this.onStop();
          this.status(
            "The camera stopped: permission was removed or another app took it. Tap Enable camera.",
          );
        }),
      );
      this.video.srcObject = stream;
      await this.video.play();
      if (this.stopped || generation !== this.generation) return;
      await this.wakeLock();
      if (this.stopped || generation !== this.generation) return;
      this.worker = new Worker("/pose-worker.js");
      this.worker.onmessage = ({ data }) => {
        if (this.stopped) return;
        if (data.type === "ready") {
          this.status("Tracking on this phone.");
          this.schedule();
        } else if (data.type === "result") {
          this.result(
            {
              timestamp: data.timestamp,
              landmarks: data.landmarks,
              luma: data.luma,
              ...(data.width > 0 && data.height > 0
                ? { aspect: data.width / data.height }
                : {}),
            },
            data.delegate,
            data.inference_ms,
          );
          this.schedule();
        } else if (data.type === "error") {
          this.stop();
          this.onStop();
          this.status(
            "Pose tracking can't run on this phone. Tap Enable camera to retry, or select Use remote only on the TV.",
          );
        }
      };
      this.worker.onerror = () => {
        this.stop();
        this.onStop();
        this.status("Pose tracking stopped. Tap Enable camera to retry.");
      };
      this.worker.postMessage({
        type: "init",
        origin: location.origin,
        delegate: this.delegate,
      });
    } catch (error) {
      if (generation === this.generation) this.stop();
      throw error;
    }
  }
  async cameras(): Promise<MediaDeviceInfo[]> {
    return (await navigator.mediaDevices.enumerateDevices()).filter(
      (d) => d.kind === "videoinput" && !/front|user/i.test(d.label),
    );
  }
  async wide(): Promise<void> {
    const devices = await this.cameras();
    const wide = devices.find((d) => /wide/i.test(d.label));
    if (wide) {
      await this.start(wide.deviceId);
      return;
    }
    const track = this.stream?.getVideoTracks()[0];
    const caps = track?.getCapabilities() as MediaTrackCapabilities & {
      zoom?: { min: number; max: number };
    };
    if (caps?.zoom && caps.zoom.min < 1) {
      try {
        await track?.applyConstraints({
          advanced: [{ zoom: caps.zoom.min } as MediaTrackConstraintSet],
        });
      } catch {
        /* Unsupported lens stays selected. */
      }
    }
  }
  private async wakeLock(): Promise<void> {
    try {
      this.wake = await navigator.wakeLock.request("screen");
      if (this.stopped) {
        await this.wake.release();
        this.wake = null;
      }
    } catch {
      this.status("Keep the phone's screen on while the camera runs.");
    }
  }
  private schedule(): void {
    if (!this.stopped) this.timer = setTimeout(() => void this.tick(), 33);
  }
  private async tick(): Promise<void> {
    if (this.stopped || !this.worker) return;
    try {
      const bitmap = await createImageBitmap(this.video);
      if (this.stopped || !this.worker) {
        bitmap.close();
        return;
      }
      this.worker.postMessage(
        { type: "frame", bitmap, timestamp: performance.now() },
        [bitmap],
      );
    } catch {
      this.schedule();
    }
  }
  stop(): void {
    this.stopped = true;
    this.generation++;
    clearTimeout(this.timer);
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
    this.worker?.terminate();
    this.worker = null;
    void this.wake?.release();
    this.wake = null;
    this.video.srcObject = null;
  }
  dispose(): void {
    this.stop();
    window.removeEventListener("pagehide", this.onLeave);
    document.removeEventListener("visibilitychange", this.onHidden);
    document.removeEventListener("cleanrep-native-pause", this.onNativePause);
  }
}
