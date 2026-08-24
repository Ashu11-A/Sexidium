// Tokens visuais do Discord (Dark Mode) compartilhados entre os cards
export const discordColors = {
  backgroundPrimary: "#313338",
  backgroundSecondary: "#2b2d31",
  backgroundTertiary: "#1e1f22",
  textNormal: "#dbdee1",
  textMuted: "#949ba4",
  brandAccent: "#5865F2",
  onlineStatus: "#23a55a",
} as const;

export const discordFontFamily =
  'gg sans, "Helvetica Neue", Helvetica, Arial, sans-serif';

// Cor do score por faixa de valor, preservando a lógica original
export function getScoreColor(score: number): string {
  if (score >= -1) return "#f0b232";
  if (score >= -2) return "#f0883e";
  return "#f23f43";
}
