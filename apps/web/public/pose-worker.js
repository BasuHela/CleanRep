/* Classic MediaPipe worker adapted from RoomQuest (MIT). Pixels stay local. */
let detector = null;
let delegate = "GPU";
const canvas = new OffscreenCanvas(64, 36);
const context = canvas.getContext("2d", { willReadFrequently: true });
self.onmessage = async ({ data }) => {
  try {
    if (data.type === "init") {
      const { PoseLandmarker, FilesetResolver } = await import("/vision/vision_bundle.mjs");
      const vision = await FilesetResolver.forVisionTasks(data.origin + "/vision/wasm");
      delegate = data.delegate === "CPU" ? "CPU" : "GPU"; // "/phone?delegate=cpu" skips the GPU attempt
      const options = { baseOptions: { modelAssetPath: data.origin + "/models/pose_landmarker_lite.task", delegate }, runningMode: "VIDEO", numPoses: 1 };
      try { detector = await PoseLandmarker.createFromOptions(vision, options); }
      catch (error) { if (delegate === "CPU") throw error; delegate = "CPU"; options.baseOptions.delegate = delegate; detector = await PoseLandmarker.createFromOptions(vision, options); }
      self.postMessage({ type: "ready", delegate });
    } else if (data.type === "frame" && detector) {
      const start = performance.now();
      const result = detector.detectForVideo(data.bitmap, data.timestamp);
      context.drawImage(data.bitmap, 0, 0, 64, 36);
      const bytes = context.getImageData(0, 0, 64, 36).data;
      let luma = 0;
      for (let i = 0; i < bytes.length; i += 4) luma += .2126*bytes[i] + .7152*bytes[i+1] + .0722*bytes[i+2];
      self.postMessage({ type: "result", timestamp: data.timestamp, landmarks: (result.landmarks[0] || []).map(p => ({ x:p.x,y:p.y,z:p.z,visibility:p.visibility })), delegate, inference_ms: performance.now()-start, luma: luma/(64*36), width: data.bitmap.width, height: data.bitmap.height });
      context.clearRect(0, 0, 64, 36);
    }
  } catch { self.postMessage({ type:"error" }); }
  finally { data.bitmap?.close(); }
};
