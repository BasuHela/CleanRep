/* Standalone camera + MediaPipe worker check page for https://cleanrep.phone/check.html. */
(function () {
  const el = (id) => document.getElementById(id);
  const video = el("video");
  const originEl = el("origin");
  const secureEl = el("secure");
  const statusEl = el("status");
  const settingsEl = el("settings");
  const delegateEl = el("delegate");
  const fpsEl = el("fps");
  const inferenceEl = el("inference");
  const landmarksEl = el("landmarks");
  const visibilityEl = el("visibility");
  const errorEl = el("error");

  originEl.textContent = location.origin;
  secureEl.textContent = String(window.isSecureContext);

  let stream = null;
  let worker = null;
  let timer = null;
  let stopped = true;
  let generation = 0;
  let timestamps = [];
  const visLog = [];

  function recordVisibility(tag) {
    const entry = tag + ":" + document.visibilityState;
    visLog.push(entry);
    if (visLog.length > 6) visLog.shift();
    visibilityEl.textContent = visLog.join(" -> ");
    console.log("[CleanRepCheck] " + entry);
  }

  recordVisibility("init");
  document.addEventListener("visibilitychange", () =>
    recordVisibility("visibilitychange"),
  );
  document.addEventListener("cleanrep-native-pause", () =>
    recordVisibility("native-pause"),
  );
  document.addEventListener("cleanrep-native-resume", () =>
    recordVisibility("native-resume"),
  );

  function showError(msg) {
    errorEl.hidden = false;
    errorEl.textContent = String(msg);
    console.error("[CleanRepCheck] " + msg);
  }

  function clearError() {
    errorEl.hidden = true;
    errorEl.textContent = "";
  }

  function stop() {
    stopped = true;
    generation++;
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
    if (stream) {
      stream.getTracks().forEach((t) => t.stop());
      stream = null;
    }
    if (worker) {
      worker.terminate();
      worker = null;
    }
    video.srcObject = null;
    statusEl.textContent = "Stopped";
  }

  function schedule() {
    if (!stopped) {
      timer = setTimeout(() => {
        void tick();
      }, 33);
    }
  }

  async function tick() {
    if (stopped || !worker) return;
    try {
      const bitmap = await createImageBitmap(video);
      if (stopped || !worker) {
        bitmap.close();
        return;
      }
      worker.postMessage(
        { type: "frame", bitmap, timestamp: performance.now() },
        [bitmap],
      );
    } catch {
      schedule();
    }
  }

  async function start() {
    stop();
    clearError();
    stopped = false;
    timestamps = [];
    const gen = generation;
    statusEl.textContent = "Requesting camera…";
    if (!window.isSecureContext) {
      showError("Not a secure context: isSecureContext is false.");
      statusEl.textContent = "Failed";
      return;
    }
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      showError("navigator.mediaDevices.getUserMedia is unavailable.");
      statusEl.textContent = "Failed";
      return;
    }
    try {
      const media = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: {
          facingMode: "environment",
          width: { ideal: 1280 },
          height: { ideal: 720 },
          frameRate: { ideal: 30 },
        },
      });
      if (stopped || gen !== generation) {
        media.getTracks().forEach((t) => t.stop());
        return;
      }
      stream = media;
      const track = media.getVideoTracks()[0];
      if (track) {
        const s = track.getSettings ? track.getSettings() : {};
        settingsEl.textContent = JSON.stringify({
          label: track.label || "",
          width: s.width,
          height: s.height,
          frameRate: s.frameRate,
          facingMode: s.facingMode,
        });
      }
      video.srcObject = media;
      await video.play();
      if (stopped || gen !== generation) return;

      statusEl.textContent = "Starting MediaPipe worker…";
      worker = new Worker("/pose-worker.js");
      worker.onmessage = ({ data }) => {
        if (stopped || gen !== generation) return;
        if (data.type === "ready") {
          delegateEl.textContent = String(data.delegate);
          statusEl.textContent = "Running (" + data.delegate + ")";
          schedule();
        } else if (data.type === "result") {
          const now = performance.now();
          timestamps.push(now);
          while (timestamps.length > 0 && now - timestamps[0] > 1000) {
            timestamps.shift();
          }
          const fps =
            timestamps.length > 1
              ? ((timestamps.length - 1) * 1000) /
                (timestamps[timestamps.length - 1] - timestamps[0])
              : timestamps.length;
          delegateEl.textContent = String(data.delegate);
          fpsEl.textContent = fps.toFixed(1);
          inferenceEl.textContent =
            typeof data.inference_ms === "number"
              ? data.inference_ms.toFixed(1) + " ms"
              : String(data.inference_ms);
          landmarksEl.textContent = String(
            Array.isArray(data.landmarks) ? data.landmarks.length : 0,
          );
          schedule();
        } else if (data.type === "error") {
          showError("Worker posted {type: 'error'}.");
          stop();
        }
      };
      worker.onerror = (err) => {
        if (stopped || gen !== generation) return;
        showError("Worker error: " + (err && err.message ? err.message : "unknown"));
        stop();
      };
      worker.postMessage({
        type: "init",
        origin: location.origin,
        delegate: "GPU",
      });
    } catch (err) {
      if (gen === generation) {
        showError(
          (err && err.name ? err.name + ": " : "") +
            (err && err.message ? err.message : String(err)),
        );
        stop();
      }
    }
  }

  el("start").addEventListener("click", () => {
    void start();
  });
  el("stop").addEventListener("click", () => {
    stop();
  });

  void start();
})();
