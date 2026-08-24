const cache = new Map<string, ArrayBuffer>();

async function fetchFont(url: string): Promise<ArrayBuffer> {
  const hit = cache.get(url);
  if (hit) return hit;
  const res = await fetch(url);
  const data = await res.arrayBuffer();
  cache.set(url, data);
  return data;
}

// Inter é o substituto livre mais próximo da fonte proprietária "gg sans"
const BASE = "https://unpkg.com/@fontsource/inter@5.0.0/files";

export interface LoadedFont {
  name: string;
  data: ArrayBuffer;
  weight: 400 | 500 | 600 | 700;
  style: "normal";
}

export async function getFonts(): Promise<LoadedFont[]> {
  const [regular, medium, semibold, bold] = await Promise.all([
    fetchFont(`${BASE}/inter-latin-400-normal.woff`),
    fetchFont(`${BASE}/inter-latin-500-normal.woff`),
    fetchFont(`${BASE}/inter-latin-600-normal.woff`),
    fetchFont(`${BASE}/inter-latin-700-normal.woff`),
  ]);

  return [
    { name: "gg sans", data: regular, weight: 400, style: "normal" },
    { name: "gg sans", data: medium, weight: 500, style: "normal" },
    { name: "gg sans", data: semibold, weight: 600, style: "normal" },
    { name: "gg sans", data: bold, weight: 700, style: "normal" },
  ];
}
