import { STATUS_BAR } from "../theme";
import type { ClipBeat } from "./types";

// Chrome's toolbar (home, localhost URL, tabs, menu) sits under the status
// bar in every clip filmed over the chat canvas: y 140-278 in the 1080x2400
// source, ending in a 1-2 px separator. Cropping at 280 hides it in every
// look and on the covers. Add new chat clips here; a beat's own `cropTop`
// wins over this table.
export const CHROME_TOP = 280;

export const CLIP_CROP_TOP: Record<string, number> = {
  "shorts/clips/s01-typing.mp4": CHROME_TOP,
  "shorts/clips/s03-avro.mp4": CHROME_TOP,
  "shorts/clips/s04-calc.mp4": CHROME_TOP,
  "shorts/clips/s06-emoji.mp4": CHROME_TOP,
  "shorts/clips/s07-clipboard.mp4": CHROME_TOP,
  "shorts/clips/s08-spacebar.mp4": CHROME_TOP,
  "shorts/clips/s09-themes.mp4": CHROME_TOP, // Themes tool over the chat canvas
  "shorts/clips/s11-langs.mp4": CHROME_TOP,
  "shorts/clips/s15-undo.mp4": CHROME_TOP,
  "shorts/clips/s16-otp.mp4": CHROME_TOP,
  "shorts/clips/s17-hindi.mp4": CHROME_TOP,
  "shorts/clips/s18-snippets.mp4": CHROME_TOP,
  "shorts/clips/s19-flick.mp4": CHROME_TOP,
  "shorts/clips/s20-translate.mp4": CHROME_TOP,
  "shorts/clips/s22-dictionary.mp4": CHROME_TOP,
  "shorts/clips/s23-glide.mp4": CHROME_TOP,
  "shorts/clips/s24-korean.mp4": CHROME_TOP,
  "shorts/clips/s25-rotate.mp4": CHROME_TOP,
  "shorts/clips/s26-scan.mp4": CHROME_TOP,
  "shorts/clips/s27-wordcard.mp4": CHROME_TOP,
  "shorts/clips/s28-pinyin.mp4": CHROME_TOP,
  "shorts/clips/s30-emoji.mp4": CHROME_TOP,
};

// First source row that may show: never above the status bar's end.
export const cropTop = (beat: Pick<ClipBeat, "clip" | "cropTop">): number =>
  Math.max(STATUS_BAR, beat.cropTop ?? CLIP_CROP_TOP[beat.clip] ?? STATUS_BAR);
