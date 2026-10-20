import { defineConfig } from "vitest/config";
export default defineConfig({
  // The Android TV API 30 (Fire OS 8 equivalent) emulator ships WebView Chrome 90; transpile syntax for it.
  build: { target: "chrome87" },
  server: {
    port: 5175,
    strictPort: true,
    proxy: {
      "/v1": {
        target: "http://127.0.0.1:8105",
        configure(proxy) {
          proxy.on("proxyReq", (outgoing, incoming) => {
            if (
              ["http://127.0.0.1:5175", "http://localhost:5175"].includes(
                incoming.headers.origin ?? "",
              )
            )
              outgoing.setHeader("Origin", "http://127.0.0.1:8105");
          });
        },
      },
    },
  },
  test: { include: ["tests/*.test.ts"] },
});
