import type { LoadedFont } from "./fonts.js";

// Resolve glifos que a fonte base (Inter) não possui: scripts especiais e emojis.
// Usado como `loadAdditionalAsset` do satori.

type SatoriAsset = string | LoadedFont[];

// Códigos de idioma que o satori detecta -> família correspondente no Google Fonts
const NOTO_FAMILY_BY_LANGUAGE: Record<string, string> = {
  "ja-JP": "Noto Sans JP",
  "ko-KR": "Noto Sans KR",
  "zh-CN": "Noto Sans SC",
  "zh-TW": "Noto Sans TC",
  "zh-HK": "Noto Sans HK",
  "th-TH": "Noto Sans Thai",
  "bn-IN": "Noto Sans Bengali",
  "ar-AR": "Noto Sans Arabic",
  "ta-IN": "Noto Sans Tamil",
  "ml-IN": "Noto Sans Malayalam",
  "he-IL": "Noto Sans Hebrew",
  "te-IN": "Noto Sans Telugu",
  devanagari: "Noto Sans Devanagari",
  kannada: "Noto Sans Kannada",
  symbol: "Noto Sans Symbols 2",
  math: "Noto Sans Math",
};

// Faixas Unicode que o satori agrupa como "unknown" mas que possuem fonte Noto dedicada.
// Cobre caracteres decorativos comuns em nomes do Discord, como ꧁ ꧂ (Javanês).
const NOTO_FAMILY_BY_RANGE: { start: number; end: number; family: string }[] = [
  { start: 0x0f00, end: 0x0fff, family: "Noto Serif Tibetan" },
  { start: 0x16a0, end: 0x16ff, family: "Noto Sans Runic" },
  { start: 0x1700, end: 0x171f, family: "Noto Sans Tagalog" },
  { start: 0xa980, end: 0xa9df, family: "Noto Sans Javanese" },
];

const FALLBACK_SYMBOL_FAMILY = "Noto Sans Symbols 2";

const TWEMOJI_BASE =
  "https://cdn.jsdelivr.net/gh/jdecked/twemoji@15.1.0/assets/svg";

const fontCache = new Map<string, Promise<ArrayBuffer | null>>();
const emojiCache = new Map<string, Promise<string | null>>();

function familyForCodePoint(codePoint: number): string {
  const match = NOTO_FAMILY_BY_RANGE.find(
    (range) => codePoint >= range.start && codePoint <= range.end,
  );
  return match?.family ?? FALLBACK_SYMBOL_FAMILY;
}

// Um segmento "unknown" pode misturar scripts (ex.: ꧁ Javanês + ༒ Tibetano).
// Retornamos uma família por script presente para que cada glifo seja coberto.
function resolveFontFamilies(languageCode: string, segment: string): string[] {
  const mapped = NOTO_FAMILY_BY_LANGUAGE[languageCode];
  if (mapped) return [mapped];

  // Caracteres ASCII/Latin-1 já são cobertos pela fonte base; mapeamos o restante
  const families = new Set<string>();
  for (const char of segment) {
    const codePoint = char.codePointAt(0)!;
    if (codePoint > 0x00ff) families.add(familyForCodePoint(codePoint));
  }
  if (families.size === 0) families.add(FALLBACK_SYMBOL_FAMILY);
  return Array.from(families);
}

async function fetchGoogleFont(family: string, text: string): Promise<ArrayBuffer | null> {
  const url = `https://fonts.googleapis.com/css2?family=${encodeURIComponent(family)}&text=${encodeURIComponent(text)}`;
  const cssResponse = await fetch(url);
  if (!cssResponse.ok) return null;

  const css = await cssResponse.text();
  // O satori (opentype) só lê ttf/otf, por isso filtramos por truetype/opentype
  const fontUrl = css.match(/src:\s*url\((.+?)\)\s*format\(['"](?:truetype|opentype)['"]\)/);
  if (!fontUrl) return null;

  const fontResponse = await fetch(fontUrl[1]);
  if (!fontResponse.ok) return null;

  return fontResponse.arrayBuffer();
}

function loadFontCached(family: string, segment: string): Promise<ArrayBuffer | null> {
  const uniqueChars = Array.from(new Set(Array.from(segment))).sort().join("");
  const cacheKey = `${family}|${uniqueChars}`;

  const cached = fontCache.get(cacheKey);
  if (cached) return cached;

  const pending = fetchGoogleFont(family, uniqueChars).catch(() => null);
  fontCache.set(cacheKey, pending);
  return pending;
}

// Twemoji remove o seletor de variação U+FE0F, exceto em sequências com ZWJ (U+200D)
function twemojiFileName(grapheme: string): string {
  const codePoints = Array.from(grapheme).map((char) => char.codePointAt(0)!);
  const hasZeroWidthJoiner = codePoints.includes(0x200d);
  const relevant = hasZeroWidthJoiner
    ? codePoints
    : codePoints.filter((codePoint) => codePoint !== 0xfe0f);
  return relevant.map((codePoint) => codePoint.toString(16)).join("-");
}

async function fetchEmojiDataUri(grapheme: string): Promise<string | null> {
  const fileName = twemojiFileName(grapheme);
  const response = await fetch(`${TWEMOJI_BASE}/${fileName}.svg`);
  if (!response.ok) return null;

  const svg = await response.text();
  const base64 = Buffer.from(svg).toString("base64");
  return `data:image/svg+xml;base64,${base64}`;
}

function loadEmojiCached(grapheme: string): Promise<string | null> {
  const cached = emojiCache.get(grapheme);
  if (cached) return cached;

  const pending = fetchEmojiDataUri(grapheme).catch(() => null);
  emojiCache.set(grapheme, pending);
  return pending;
}

export async function loadAdditionalAsset(
  languageCode: string,
  segment: string,
): Promise<SatoriAsset> {
  if (!segment) return [];

  if (languageCode === "emoji") {
    const dataUri = await loadEmojiCached(segment);
    return dataUri ?? [];
  }

  const families = resolveFontFamilies(languageCode, segment);
  const loaded = await Promise.all(
    families.map(async (family): Promise<LoadedFont | null> => {
      const data = await loadFontCached(family, segment);
      return data ? { name: family, data, weight: 400, style: "normal" } : null;
    }),
  );

  return loaded.filter((font): font is LoadedFont => font !== null);
}
