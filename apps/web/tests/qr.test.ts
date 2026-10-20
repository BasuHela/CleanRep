import { createHash } from "node:crypto";
import { expect, test } from "vitest";
import { qrModules, qrSvg } from "../src/shared/qr";
import { linkedCode } from "../src/phone/policy";
import { phoneLink, phoneUrl } from "../src/tv/labels";

const rows = (m: boolean[][]) =>
  m.map((r) => r.map((d) => (d ? "1" : "0")).join(""));

// Checked 20 Oct with zxing-cpp (decodes to the text) and against segno 1.6.6 with ISO 18004 padding
// (identical modules).
const HELLO = [
  "111111101101001111111",
  "100000100110101000001",
  "101110100111101011101",
  "101110101001001011101",
  "101110101000101011101",
  "100000101011001000001",
  "111111101010101111111",
  "000000001111100000000",
  "100010111111011111001",
  "000111001011100101111",
  "101100101011001110010",
  "111001000100011010000",
  "001011100100111000110",
  "000000001110111001011",
  "111111101100110001010",
  "100000100001100100010",
  "101110101001001110101",
  "101110100001100001011",
  "101110100111001111000",
  "100000100100011000000",
  "111111101000111110101",
];
// A version-5 reference symbol: decoded with zxing-cpp and matched module for module against the segno encoder
// when it was recorded. Its text is fixed test data (it keeps the project's earlier name), so it must not change.
const HOSTED =
  "https://formlab.a1b2c3d4e5f6g.ap-south-1.cs.amazonlightsail.com/phone#code=123456";

test("W-QR-1 the encoder matches externally decoded symbols and picks the smallest version that fits", () => {
  expect(rows(qrModules("HELLO"))).toEqual(HELLO);
  const hosted = rows(qrModules(HOSTED));
  expect(hosted.length).toBe(37); // version 5
  expect(createHash("sha256").update(hosted.join("\n")).digest("hex")).toBe(
    "f526f5331a57b1df383ac882ce44c102997deabd8f6c654d4b26df9e16c88a4b",
  );
  // Finder patterns in three corners, the dark module, and the timing pattern.
  for (const m of [qrModules(HOSTED), qrModules("0123456789".repeat(21))]) {
    const n = m.length;
    for (const [x, y] of [
      [0, 0],
      [n - 7, 0],
      [0, n - 7],
    ])
      expect(rows(m.slice(y, y + 7).map((r) => r.slice(x, x + 7))).join()).toBe(
        "1111111,1000001,1011101,1011101,1011101,1000001,1111111",
      );
    expect(m[n - 8][8]).toBe(true);
    for (let i = 8; i < n - 8; i++) expect(m[6][i]).toBe(i % 2 === 0);
  }
  expect(qrModules("0123456789".repeat(21)).length).toBe(57); // version 10, 16-bit length
  expect(() => qrModules("x".repeat(214))).toThrow(RangeError);
});

test("W-QR-2 the TV's QR opens the phone page with the code, and the phone takes only a six-digit code from it", () => {
  // The TV app's server names its Wi-Fi address; the API origin is its own loopback.
  const url = phoneUrl(
    "http://127.0.0.1:8105",
    "http://192.168.1.20:8105/phone",
  );
  const link = phoneLink(url, "123456");
  expect(link).toBe("http://192.168.1.20:8105/phone#code=123456");
  expect(linkedCode(new URL(link).hash)).toBe("123456");
  for (const hash of [
    "",
    "#",
    "#code=12345",
    "#code=1234567",
    "#code=12a456",
    "#123456",
  ])
    expect(linkedCode(hash)).toBeNull();
  // A light square with a quiet zone: the symbol plus 4 modules on each side.
  const svg = qrSvg(link, "QR code");
  const side = qrModules(link).length + 8;
  expect(svg).toContain(`viewBox="0 0 ${side} ${side}"`);
  expect(svg).toContain('role="img"');
  expect(svg).not.toContain("123456");
});
