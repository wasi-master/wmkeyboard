import React from "react";
import {
  AbsoluteFill,
  Audio,
  Easing,
  Img,
  Sequence,
  interpolate,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";
import { Backdrop } from "../components/Backdrop";
import { Sfx } from "../components/Sfx";
import { INTER } from "../fonts";
import { CYAN, VIOLET } from "../theme";
import { Headline } from "./Headline";
import { LookContext, PAPER, resolveLook, useLook } from "./looks";
import { SafeZone } from "./SafeZone";
import { ScreenCard } from "./ScreenCard";
import { Carousel } from "./Carousel";
import { SAFE, SAFE_CX, SAFE_W, beatFrames, beatStarts, beatsFrames } from "./layout";
import type { ShortProps } from "./types";

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;

// Transition whoosh level (a third of the first cut's 0.35) and the shortest
// shot that earns one.
const WHOOSH = 0.12;
const MIN_WHOOSH_SHOT = 45; // 1.5 s

// Shared body of every short: drifting backdrop, one ScreenCard per beat,
// whoosh on each cut, outro card, the music bed (only when `bed`), the VO,
// and the safe-zone overlay. Templates pass their text layers as children;
// those play over the beats, not the outro.
export const ShortScaffold: React.FC<
  ShortProps & { children?: React.ReactNode; bedVolume?: number }
> = ({ spec, bed, safeZone, look: lookProp, children, bedVolume = 0.6 }) => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();
  const accent = spec.accent ?? VIOLET;
  const accentB = spec.accentB ?? CYAN;
  const look = { ...resolveLook(lookProp ?? spec.look), accent, accentB };
  const starts = beatStarts(spec.beats);
  const body = beatsFrames(spec);
  // A spec's own bedVolume (a track picked to sit low) caps the bed level.
  const bedLevel = spec.bedVolume ?? bedVolume;

  return (
    <LookContext.Provider value={look}>
      <Canvas>
        {bed ? (
          <Audio
            src={staticFile(spec.bed ?? "music.mp3")}
            loop
            volume={(f) =>
              // fade out music from when ending WM Keyboard text comes up till the end
              interpolate(
                f,
                [0, 8, body + 4, body + 30, durationInFrames - 2],
                [0, bedLevel, bedLevel, bedLevel * 0.15, 0],
                clamp,
              )
            }
          />
        ) : null}
        {spec.vo ? <Audio src={staticFile(spec.vo.file)} /> : null}
        {/* soft whoosh on real cuts only: a shot under 1.5 s (carousels,
            quick inserts) cuts in silence so rapid edits never stack */}
        {starts.map((s, i) =>
          i > 0 && beatFrames(spec.beats[i]) >= MIN_WHOOSH_SHOT && !("carousel" in spec.beats[i]) ? (
            <Sfx key={s} name="whoosh" at={s - 3} volume={WHOOSH} />
          ) : null,
        )}
        <Sfx name="whoosh" at={body - 3} volume={WHOOSH * 1.15} />

        <Sequence durationInFrames={body} name="Beats">
          {spec.beats.map((b, i) => (
            <Sequence key={i} from={starts[i]} durationInFrames={beatFrames(b)} name={`Beat ${i + 1}`}>
              {"carousel" in b ? (
                <Carousel beat={b} index={i} accent={accent} />
              ) : (
                <ScreenCard beat={b} index={i} accent={accent} />
              )}
            </Sequence>
          ))}
          {look.frame === "bleed" ? <Scrims /> : null}
          {children}
        </Sequence>

        <Sequence from={body} name="Outro">
          <Outro />
        </Sequence>

        {/* progress bar, just under the top safe line */}
        <div
          style={{
            position: "absolute",
            top: SAFE.top - 2,
            left: SAFE.left,
            width: SAFE_W,
            height: 6,
            borderRadius: 3,
            backgroundColor: look.light ? "rgba(10,13,20,0.1)" : "rgba(255,255,255,0.1)",
          }}
        >
          <div
            style={{
              width: `${(frame / durationInFrames) * 100}%`,
              height: "100%",
              borderRadius: 3,
              background: `linear-gradient(90deg, ${accent}, ${accentB})`,
            }}
          />
        </div>
        {safeZone ? <SafeZone /> : null}
      </Canvas>
    </LookContext.Provider>
  );
};

// Background per look: the Ink & Neon backdrop with an orbiting glow for the
// dark looks, cream paper with a dot grid for paper.
export const Canvas: React.FC<{ children?: React.ReactNode }> = ({ children }) => {
  const frame = useCurrentFrame();
  const look = useLook();
  if (look.light) {
    return (
      <AbsoluteFill style={{ backgroundColor: PAPER }}>
        <AbsoluteFill
          style={{
            backgroundImage: "radial-gradient(rgba(60,45,20,0.13) 2px, transparent 2.5px)",
            backgroundSize: "36px 36px",
            backgroundPosition: `${frame * 0.4}px ${frame * 0.25}px`,
          }}
        />
        <div
          style={{
            position: "absolute",
            width: 1000,
            height: 1000,
            left: SAFE_CX - 500 + Math.sin(frame / 45) * 140,
            top: 700 + Math.cos(frame / 55) * 160,
            borderRadius: "50%",
            background: `radial-gradient(circle, ${look.accent}40 0%, transparent 65%)`,
            filter: "blur(50px)",
          }}
        />
        {children}
      </AbsoluteFill>
    );
  }
  return (
    <Backdrop accentA={look.accent} accentB={look.accentB}>
      {/* slow orbiting glow behind the footage keeps the canvas alive */}
      <div
        style={{
          position: "absolute",
          width: 900,
          height: 900,
          left: SAFE_CX - 450 + Math.sin(frame / 40) * 120,
          top: 760 + Math.cos(frame / 52) * 140,
          borderRadius: "50%",
          background: `radial-gradient(circle, ${look.accent}55 0%, transparent 65%)`,
          filter: "blur(40px)",
        }}
      />
      {children}
    </Backdrop>
  );
};

// Bleed look: dark gradients top and bottom so type reads over footage.
const Scrims: React.FC = () => {
  return (
    <>
      <AbsoluteFill
        style={{
          height: 640,
          background: "linear-gradient(180deg, rgba(5,7,12,0.85) 0%, rgba(5,7,12,0.55) 55%, transparent 100%)",
        }}
      />
      <AbsoluteFill
        style={{
          // lower third only: zoom targets sit above it, in the clear
          top: 1000,
          height: 920,
          background: `linear-gradient(180deg, transparent 0%, rgba(5,7,12,0.72) 30%, rgba(5,7,12,0.92) 100%)`,
        }}
      />
    </>
  );
};

const Outro: React.FC = () => {
  const frame = useCurrentFrame();
  const look = useLook();
  return (
    <AbsoluteFill
      style={{
        left: SAFE.left,
        width: SAFE_W,
        top: SAFE.top,
        // AbsoluteFill brings height 100%; pin it to the safe box explicitly
        height: 1920 - SAFE.top - SAFE.bottom,
        alignItems: "center",
        justifyContent: "center",
        flexDirection: "column",
        gap: 22,
        scale: interpolate(frame, [0, 60], [0.96, 1.02], clamp),
      }}
    >
      {look.frame === "bleed" ? (
        <AbsoluteFill style={{ left: -SAFE.left, width: 1080, top: -SAFE.top, height: 1920, backgroundColor: "rgba(5,7,12,0.6)" }} />
      ) : null}
      {/* the launcher icon is a full rounded tile with its own ground: no plate */}
      <Img
        src={staticFile("logo-app.png")}
        style={{
          width: 150,
          height: 150,
          filter: look.light
            ? "drop-shadow(0 16px 30px rgba(60,45,20,0.25))"
            : `drop-shadow(0 18px 40px rgba(0,0,0,0.5)) drop-shadow(0 0 40px ${look.accent}55)`,
          scale: interpolate(frame, [0, 10], [0.4, 1], {
            ...clamp,
            easing: Easing.out(Easing.back(2)),
          }),
          rotate: `${interpolate(frame, [0, 10], [-25, 0], clamp)}deg`,
        }}
      />
      {/* one line in every look: undo the look's headline scale */}
      <Headline text="WM «Keyboard»" size={96 / look.headScale} delay={4} />
      <div
        style={{
          fontFamily: INTER,
          fontWeight: 500,
          fontSize: 44,
          color: look.soft,
          letterSpacing: "0.05em",
          opacity: interpolate(frame, [10, 18], [0, 1], clamp),
        }}
      >
        Private. Offline. Yours.
      </div>
      <StoreBadges />
    </AbsoluteFill>
  );
};

// Official store badges, untouched apart from uniform scaling (Google's brand
// rules forbid recolouring). Google Play leads at full width; F-Droid and
// GitHub share a row under it. Every gap is at least a quarter of the
// neighbouring badge's height, the minimum clear space.
const BADGE_RATIO = 168 / 564;
const PRIMARY_W = 500; // 149 px tall, needs >= 38 px clear
const SECONDARY_W = 300; // 89 px tall, needs >= 23 px clear

export const StoreBadges: React.FC<{ delay?: number }> = ({ delay = 16 }) => {
  const frame = useCurrentFrame();
  const enter = (d: number): React.CSSProperties => ({
    opacity: interpolate(frame, [d, d + 8], [0, 1], clamp),
    translate: `0px ${interpolate(frame, [d, d + 10], [30, 0], { ...clamp, easing: Easing.out(Easing.cubic) })}px`,
  });
  const badge = (file: string, w: number, d: number) => (
    <Img src={staticFile(`badges/${file}`)} style={{ width: w, height: w * BADGE_RATIO, display: "block", ...enter(d) }} />
  );
  return (
    <div
      style={{
        marginTop: 16,
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        gap: Math.ceil(PRIMARY_W * BADGE_RATIO * 0.3),
      }}
    >
      {badge("google-play.png", PRIMARY_W, delay)}
      <div style={{ display: "flex", gap: 56 }}>
        {badge("f-droid.png", SECONDARY_W, delay + 5)}
        {badge("github.png", SECONDARY_W, delay + 8)}
      </div>
    </div>
  );
};
