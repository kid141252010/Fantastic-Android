/*
 * Apple Music Android 7.0.0 (1614), sample SHA-256:
 * 49e83b64fccff79310c7dff174e02228dd0455a3d87df4d9655d1e4f8cd2e351
 * Behavioral reimplementation; no Apple bytecode, native libraries or artwork.
 * Sources in the supplied APK's DEX (jadx names):
 * utils.F.f50854i; PlayerLyricsViewFragment.f/onCreateView; p236lc.y.a;
 * player.C4022z.f0/W/Y; Q2.e (Android SpringForce); X9.a.
 * See README.md here for event mapping and platform differences.
 */
export const clamp = (value, min = 0, max = 1) => Math.min(max, Math.max(min, value));

/** Invert x(t), then evaluate y(t); evaluating y at elapsed time is NOT a cubic easing. */
export function cubicBezier(x1, y1, x2, y2) {
  const ax = 1 - 3 * x2 + 3 * x1, bx = 3 * x2 - 6 * x1, cx = 3 * x1;
  const ay = 1 - 3 * y2 + 3 * y1, by = 3 * y2 - 6 * y1, cy = 3 * y1;
  return (fraction) => {
    if (fraction <= 0) return 0;
    if (fraction >= 1) return 1;
    let lo = 0, hi = 1, t = fraction;
    for (let i = 0; i < 18; i++) {
      const x = ((ax * t + bx) * t + cx) * t;
      const error = x - fraction;
      if (Math.abs(error) < 1e-7) break;
      if (error < 0) lo = t; else hi = t;
      const slope = (3 * ax * t + 2 * bx) * t + cx;
      const next = slope > 1e-7 ? t - error / slope : -1;
      t = next > lo && next < hi ? next : (lo + hi) / 2;
    }
    return ((ay * t + by) * t + cy) * t;
  };
}

export const curves = Object.freeze({
  line: cubicBezier(0.4, 0.1, 0, 1),
  emphasis: cubicBezier(0.25, 0.1, 0.25, 1),
  interludeProgress: cubicBezier(0, 0.25, 1, 0.58),
  interludeExit: cubicBezier(0.25, 0, 1, 0.2),
  credits: cubicBezier(0.75, 0, 0.25, 1),
});

export const motion = Object.freeze({
  lineDefaultMs: 750, wordLeadMs: 100,
  stiffness: 25, damping: 0.93, liftPx: -2,
  normalScale: 0.98, activeScale: 1,
  featherPx: 30, emphasisScale: 1.14, emphasisShadowPx: 5,
  primaryAlpha: 0.94, tertiaryAlpha: 0.18,
  interludeMinMs: 7000, interludePeriodMs: 4000,
  interludeEnterMs: 750, interludeExitMs: 250, dotStaggerMs: 50,
});

export function lineDuration(gapMs) {
  return Math.round(480 + 270 * ((clamp(gapMs, 200, 750) - 200) / 550));
}

export function lineStagger(distance) {
  const n = clamp(distance, 0, 4);
  return Math.round(25 * 0.25 * (5 * n - (n + 1) * n / 2));
}

/** Unit-mass, zero-initial-velocity spring; same analytical solution as Q2.e.c. */
export function syllableLift(elapsedMs) {
  if (elapsedMs <= 0) return 0;
  const t = elapsedMs / 1000;
  const omega = Math.sqrt(motion.stiffness);
  const decay = motion.damping * omega;
  const frequency = omega * Math.sqrt(1 - motion.damping ** 2);
  return motion.liftPx * (1 - Math.exp(-decay * t) *
    (Math.cos(frequency * t) + decay / frequency * Math.sin(frequency * t)));
}

/** C4022z.W: staggered attack, cancel attack at release, decay from the sampled value. */
export function emphasisEnvelope(elapsedMs, durationMs, index, count) {
  const attack = Math.min(durationMs, 3000);
  const stagger = Math.min(0.4 * durationMs / count, 400);
  const t = elapsedMs - index * stagger;
  if (t <= 0 || attack <= 0) return 0;
  const release = 2 * durationMs / count;
  if (t < release) return curves.emphasis(clamp(t / attack));
  return curves.emphasis(clamp(release / attack)) *
    (1 - curves.emphasis(clamp((t - release) / attack)));
}

// Every sample from X9.a.f26614c, in original order. Linear interpolation between samples.
export const interludeSamples = Object.freeze([
  0, 0.0005, 0.002, 0.004, 0.007, 0.01, 0.0138, 0.02, 0.026, 0.032, 0.039,
  0.048, 0.058, 0.071, 0.086, 0.102, 0.119, 0.135, 0.153, 0.172, 0.194,
  0.215, 0.234, 0.257, 0.279, 0.299, 0.32, 0.34, 0.358, 0.373, 0.389,
  0.404, 0.418, 0.43, 0.44, 0.45, 0.458, 0.466, 0.473, 0.479, 0.483,
  0.4875, 0.491, 0.494, 0.496, 0.498, 0.499, 0.4995, 0.5, 0.5005, 0.502,
  0.504, 0.507, 0.51, 0.5138, 0.52, 0.526, 0.532, 0.539, 0.548, 0.558,
  0.571, 0.586, 0.602, 0.619, 0.635, 0.653, 0.672, 0.694, 0.715, 0.734,
  0.757, 0.779, 0.799, 0.82, 0.84, 0.858, 0.873, 0.889, 0.904, 0.918,
  0.93, 0.94, 0.95, 0.958, 0.966, 0.973, 0.979, 0.983, 0.9875, 0.991,
  0.994, 0.996, 0.998, 0.999, 0.9995, 1,
]);

export function interludeInterpolation(fraction) {
  const index = clamp(fraction) * (interludeSamples.length - 1);
  const lower = Math.min(Math.floor(index), interludeSamples.length - 2);
  return interludeSamples[lower] + (interludeSamples[lower + 1] - interludeSamples[lower]) * (index - lower);
}

export function interludeScale(elapsedMs, durationMs) {
  const cycles = Math.floor(durationMs / motion.interludePeriodMs);
  if (!cycles) return 1;
  const phase = (Math.max(0, elapsedMs) % (durationMs / cycles)) / (durationMs / cycles);
  const p = interludeInterpolation(phase);
  return p <= 0.5 ? 1 + 0.4 * p : 1.4 - 0.4 * p;
}
