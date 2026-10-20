import { defineConfig } from "@playwright/test";
import { existsSync } from "node:fs";
// The journeys run against the Java server the TV app runs (`make browser-test` compiles it first). Its data folder
// starts empty on every run.
const server =
  "rm -rf var/browser-data && exec java -Dcleanrep.root=. -cp apps/core/build/classes org.cleanrep.core.Main" +
  " --port 8125 --origin http://127.0.0.1:8125 --data var/browser-data --static apps/web/dist --allow-replay" +
  " --max-seconds 300";
export default defineConfig({
  testDir: "tests/browser",
  timeout: 60000,
  workers: 1,
  use: {
    baseURL: "http://127.0.0.1:8125",
    viewport: { width: 1920, height: 1080 },
    browserName: "chromium",
    launchOptions: {
      ...(existsSync("/usr/bin/google-chrome")
        ? { executablePath: "/usr/bin/google-chrome" }
        : {}),
      args: ["--no-sandbox"],
    },
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  reporter: [
    ["list"],
    ["json", { outputFile: "../../evals/browser-results.json" }],
  ],
  webServer: {
    command: server,
    cwd: "../..",
    url: "http://127.0.0.1:8125/v1/healthz",
    timeout: 20000,
    reuseExistingServer: false,
    gracefulShutdown: { signal: "SIGTERM", timeout: 2000 },
  },
});
