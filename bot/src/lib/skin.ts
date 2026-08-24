import sharp from "sharp";
import { mc } from "../minecraft/index.js";

/**
 * Renders a 2D Minecraft head (face + hat overlay) from a player's real skin — the skin the player
 * set via SkinsRestorer, resolved on the Java side and delivered over the `skin.get` RPC as a Mojang
 * texture URL. The head is composited with `sharp` and returned as a `data:` URI so it can be embedded
 * directly in a satori `<img>`. Returns `null` when the player has no resolvable skin.
 */

// Skin PNG face/hat sample regions (works for both 64x64 and legacy 64x32 skins).
const FACE = { left: 8, top: 8, width: 8, height: 8 } as const;
const HAT = { left: 40, top: 8, width: 8, height: 8 } as const;

const cache = new Map<string, string | null>();

async function fetchSkinPng(url: string): Promise<Buffer | null> {
  try {
    const res = await fetch(url);
    if (!res.ok) return null;
    return Buffer.from(await res.arrayBuffer());
  } catch {
    return null;
  }
}

async function buildHead(target: string, size: number): Promise<string | null> {
  let url: string | null;
  try {
    url = (await mc.skinGet(target)).url;
  } catch {
    return null; // bridge offline or no skin resolver
  }
  if (!url) return null;

  const png = await fetchSkinPng(url);
  if (!png) return null;

  try {
    const face = await sharp(png)
      .extract(FACE)
      .resize(size, size, { kernel: "nearest" })
      .png()
      .toBuffer();
    const hat = await sharp(png)
      .extract(HAT)
      .resize(size, size, { kernel: "nearest" })
      .png()
      .toBuffer();
    const composed = await sharp(face).composite([{ input: hat }]).png().toBuffer();
    return `data:image/png;base64,${composed.toString("base64")}`;
  } catch {
    return null; // malformed / undersized skin texture
  }
}

/** Cached head render for a Minecraft UUID or name. `size` is the output pixel size (square). */
export async function skinHeadDataUri(target: string, size = 96): Promise<string | null> {
  const key = `${target}:${size}`;
  const hit = cache.get(key);
  if (hit !== undefined) return hit;
  const uri = await buildHead(target, size);
  cache.set(key, uri);
  return uri;
}

/** Drop cached renders (call when a skin changes). No argument clears everything. */
export function clearSkinCache(target?: string): void {
  if (!target) {
    cache.clear();
    return;
  }
  for (const key of cache.keys()) {
    if (key.startsWith(`${target}:`)) cache.delete(key);
  }
}
