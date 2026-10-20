/* QR Code (ISO/IEC 18004) for the TV pairing screen: byte mode, error correction level M, versions 1-10.
 * Adapted and condensed from Project Nayuki's QR Code generator library (TypeScript). On 20 Oct its symbols
 * for versions 1-10 decoded with zxing-cpp and matched segno module for module, once segno's padding was
 * patched to the standard; tests/qr.test.ts pins two of them.
 *
 * Copyright (c) Project Nayuki. (MIT License)
 * https://www.nayuki.io/page/qr-code-generator-library
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 * - The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 * - The Software is provided "as is", without warranty of any kind, express or
 *   implied, including but not limited to the warranties of merchantability,
 *   fitness for a particular purpose and noninfringement. In no event shall the
 *   authors or copyright holders be liable for any claim, damages or other
 *   liability, whether in an action of contract, tort or otherwise, arising from,
 *   out of or in connection with the Software or the use or other dealings in the
 *   Software. */

// Level M, indexed by version (index 0 unused).
const ECC_PER_BLOCK = [0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26];
const BLOCKS = [0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5];
const FORMAT_M = 0;

function rawDataModules(ver: number): number {
  let result = (16 * ver + 128) * ver + 64;
  if (ver >= 2) {
    const align = Math.floor(ver / 7) + 2;
    result -= (25 * align - 10) * align - 55;
    if (ver >= 7) result -= 36;
  }
  return result;
}

const dataCodewords = (ver: number): number =>
  Math.floor(rawDataModules(ver) / 8) - ECC_PER_BLOCK[ver] * BLOCKS[ver];

function gfMultiply(x: number, y: number): number {
  let z = 0;
  for (let i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d);
    z ^= ((y >>> i) & 1) * x;
  }
  return z;
}

function rsDivisor(degree: number): number[] {
  const result = new Array<number>(degree).fill(0);
  result[degree - 1] = 1;
  let root = 1;
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < degree; j++) {
      result[j] = gfMultiply(result[j], root);
      if (j + 1 < degree) result[j] ^= result[j + 1];
    }
    root = gfMultiply(root, 0x02);
  }
  return result;
}

function rsRemainder(data: number[], divisor: number[]): number[] {
  const result = divisor.map(() => 0);
  for (const b of data) {
    const factor = b ^ (result.shift() as number);
    result.push(0);
    divisor.forEach((coef, i) => (result[i] ^= gfMultiply(coef, factor)));
  }
  return result;
}

/** Data codewords for `bytes` in byte mode, terminated and padded to the version's capacity. */
function codewords(bytes: Uint8Array, ver: number): number[] {
  const bits: number[] = [];
  const put = (value: number, length: number) => {
    for (let i = length - 1; i >= 0; i--) bits.push((value >>> i) & 1);
  };
  put(0b0100, 4);
  put(bytes.length, ver < 10 ? 8 : 16);
  bytes.forEach((b) => put(b, 8));
  const capacity = dataCodewords(ver) * 8;
  put(0, Math.min(4, capacity - bits.length));
  put(0, (8 - (bits.length % 8)) % 8);
  for (let pad = 0xec; bits.length < capacity; pad ^= 0xec ^ 0x11) put(pad, 8);
  const out: number[] = [];
  for (let i = 0; i < bits.length; i += 8)
    out.push(bits.slice(i, i + 8).reduce((acc, bit) => (acc << 1) | bit, 0));
  return out;
}

/** Split into blocks, add Reed-Solomon error correction to each, and interleave. */
function interleave(data: number[], ver: number): number[] {
  const blocks = BLOCKS[ver],
    eccLen = ECC_PER_BLOCK[ver],
    raw = Math.floor(rawDataModules(ver) / 8),
    short = blocks - (raw % blocks),
    shortLen = Math.floor(raw / blocks),
    divisor = rsDivisor(eccLen),
    all: number[][] = [];
  for (let i = 0, k = 0; i < blocks; i++) {
    const dat = data.slice(k, k + shortLen - eccLen + (i < short ? 0 : 1));
    k += dat.length;
    const ecc = rsRemainder(dat, divisor);
    if (i < short) dat.push(0);
    all.push(dat.concat(ecc));
  }
  const result: number[] = [];
  for (let i = 0; i < all[0].length; i++)
    all.forEach((block, j) => {
      if (i !== shortLen - eccLen || j >= short) result.push(block[i]);
    });
  return result;
}

const MASKS: ((x: number, y: number) => boolean)[] = [
  (x, y) => (x + y) % 2 === 0,
  (x, y) => y % 2 === 0,
  (x) => x % 3 === 0,
  (x, y) => (x + y) % 3 === 0,
  (x, y) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0,
  (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
  (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0,
  (x, y) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0,
];

/** Lower is easier to scan: long runs, 2x2 blocks, finder look-alikes and dark/light imbalance cost points. */
function penalty(m: boolean[][]): number {
  const size = m.length;
  let score = 0;
  const lines = (get: (a: number, b: number) => boolean) => {
    for (let a = 0; a < size; a++) {
      let run = 1;
      for (let b = 1; b <= size; b++) {
        if (b < size && get(a, b) === get(a, b - 1)) run++;
        else {
          if (run >= 5) score += run - 2;
          run = 1;
        }
      }
      const row = Array.from({ length: size }, (_, b) =>
        get(a, b) ? 1 : 0,
      ).join("");
      for (const finder of ["10111010000", "00001011101"])
        for (
          let at = row.indexOf(finder);
          at >= 0;
          at = row.indexOf(finder, at + 1)
        )
          score += 40;
    }
  };
  lines((y, x) => m[y][x]);
  lines((x, y) => m[y][x]);
  let dark = 0;
  for (let y = 0; y < size; y++)
    for (let x = 0; x < size; x++) {
      if (m[y][x]) dark++;
      if (x < size - 1 && y < size - 1) {
        const c = m[y][x];
        if (c === m[y][x + 1] && c === m[y + 1][x] && c === m[y + 1][x + 1])
          score += 3;
      }
    }
  const total = size * size;
  score += (Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1) * 10;
  return score;
}

/** The QR symbol for `text` (UTF-8) as rows of dark (true) and light modules, without the quiet zone. */
export function qrModules(text: string): boolean[][] {
  const bytes = new TextEncoder().encode(text);
  const ver = ECC_PER_BLOCK.findIndex(
    (_, v) =>
      v > 0 &&
      Math.ceil((4 + (v < 10 ? 8 : 16) + bytes.length * 8) / 8) <=
        dataCodewords(v),
  );
  if (ver < 1) throw new RangeError("Text too long for a version 1-10 QR code");
  const size = ver * 4 + 17;
  const modules = Array.from({ length: size }, () =>
    new Array<boolean>(size).fill(false),
  );
  const fixed = Array.from({ length: size }, () =>
    new Array<boolean>(size).fill(false),
  );
  const set = (x: number, y: number, dark: boolean) => {
    modules[y][x] = dark;
    fixed[y][x] = true;
  };

  for (let i = 0; i < size; i++) {
    set(6, i, i % 2 === 0);
    set(i, 6, i % 2 === 0);
  }
  for (const [cx, cy] of [
    [3, 3],
    [size - 4, 3],
    [3, size - 4],
  ])
    for (let dy = -4; dy <= 4; dy++)
      for (let dx = -4; dx <= 4; dx++) {
        const d = Math.max(Math.abs(dx), Math.abs(dy)),
          x = cx + dx,
          y = cy + dy;
        if (x >= 0 && x < size && y >= 0 && y < size)
          set(x, y, d !== 2 && d !== 4);
      }
  if (ver >= 2) {
    const count = Math.floor(ver / 7) + 2;
    const step = Math.ceil((ver * 4 + 4) / (count * 2 - 2)) * 2;
    const pos = [6];
    for (let p = size - 7; pos.length < count; p -= step) pos.splice(1, 0, p);
    pos.forEach((px, i) =>
      pos.forEach((py, j) => {
        const last = count - 1;
        if (
          (i === 0 && j === 0) ||
          (i === 0 && j === last) ||
          (i === last && j === 0)
        )
          return;
        for (let dy = -2; dy <= 2; dy++)
          for (let dx = -2; dx <= 2; dx++)
            set(px + dx, py + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
      }),
    );
  }
  const format = (mask: number) => {
    const data = (FORMAT_M << 3) | mask;
    let rem = data;
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    const bits = ((data << 10) | rem) ^ 0x5412;
    const bit = (i: number) => ((bits >>> i) & 1) === 1;
    for (let i = 0; i <= 5; i++) set(8, i, bit(i));
    set(8, 7, bit(6));
    set(8, 8, bit(7));
    set(7, 8, bit(8));
    for (let i = 9; i < 15; i++) set(14 - i, 8, bit(i));
    for (let i = 0; i < 8; i++) set(size - 1 - i, 8, bit(i));
    for (let i = 8; i < 15; i++) set(8, size - 15 + i, bit(i));
    set(8, size - 8, true);
  };
  format(0);
  if (ver >= 7) {
    let rem = ver;
    for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
    const bits = (ver << 12) | rem;
    for (let i = 0; i < 18; i++) {
      const dark = ((bits >>> i) & 1) === 1,
        a = size - 11 + (i % 3),
        b = Math.floor(i / 3);
      set(a, b, dark);
      set(b, a, dark);
    }
  }

  const data = interleave(codewords(bytes, ver), ver);
  let i = 0;
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let vert = 0; vert < size; vert++)
      for (let j = 0; j < 2; j++) {
        const x = right - j,
          y = ((right + 1) & 2) === 0 ? size - 1 - vert : vert;
        if (!fixed[y][x] && i < data.length * 8) {
          modules[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) === 1;
          i++;
        }
      }
  }

  const masked = (mask: number) => {
    const out = modules.map((row) => row.slice());
    for (let y = 0; y < size; y++)
      for (let x = 0; x < size; x++)
        if (!fixed[y][x] && MASKS[mask](x, y)) out[y][x] = !out[y][x];
    return out;
  };
  let best = 0,
    bestScore = Infinity;
  for (let mask = 0; mask < 8; mask++) {
    format(mask);
    const score = penalty(masked(mask));
    if (score < bestScore) [best, bestScore] = [mask, score];
  }
  format(best);
  return masked(best);
}

/** An SVG of the symbol: dark modules on a light square with the 4-module quiet zone scanners need. */
export function qrSvg(text: string, label: string): string {
  const m = qrModules(text),
    n = m.length + 8;
  let path = "";
  m.forEach((row, y) =>
    row.forEach((dark, x) => {
      if (dark) path += `M${x + 4} ${y + 4}h1v1h-1z`;
    }),
  );
  return `<svg class="qr" viewBox="0 0 ${n} ${n}" role="img" aria-label="${label}" shape-rendering="crispEdges"><rect width="${n}" height="${n}" fill="#fff"/><path d="${path}" fill="#000"/></svg>`;
}
