import type { NetworkInterfaceInfo } from "node:os";

export function isPrivateIPv4(host: unknown): boolean;
export function lanAddress(
  interfaces?: NodeJS.Dict<NetworkInterfaceInfo[]>,
): string | null;
export function isApiTarget(target: string): boolean | null;
export function startRelay(options: {
  host: string;
  httpPort?: number;
  httpsPort?: number;
  upstreamHost?: string;
  upstreamHttpPort?: number;
  upstreamHttpsPort?: number;
  log?: (line: string) => void;
}): Promise<{ httpPort: number; httpsPort: number; close(): Promise<void> }>;
