/* Native playback anchors → TTML model → APK-derived lyric motion. Times are milliseconds. */
import { AppleMusicLyrics } from './apple-music-player.mjs';
import { parseTTML } from './ttml.mjs';
import { ensureWordSegmenter } from './word-segmenter.mjs';

const bridge = globalThis.FantasticBridge;
const PRIORITY = { amll: ['amll', 'qrc', 'lrc'], qrc: ['qrc', 'amll', 'lrc'] };

function selectSong(candidates, prefer) {
  for (const source of PRIORITY[prefer] || PRIORITY.amll) {
    for (const candidate of candidates || []) {
      if (candidate.source !== source || typeof candidate.text !== 'string' || !candidate.text.trim()) continue;
      try {
        const song = parseTTML(candidate.text, undefined, 'zh-Hans');
        if (song?.lines?.length) return song;
      } catch { /* Try the next TTML candidate, preserving the server's source priority. */ }
    }
  }
  return null;
}

const view = new AppleMusicLyrics(document.getElementById('lyrics'), ms => bridge?.seek(ms));
let song = null;
let anchor = { pos: 0, at: performance.now(), playing: false };
let frames = 0;
const now = () => anchor.pos + (anchor.playing ? performance.now() - anchor.at : 0);

function fitSize() {
  // Older WebViews can resolve 100% height to zero when first loaded off-window.
  if (innerHeight > 0 && view.element.style.height !== `${innerHeight}px`) view.element.style.height = `${innerHeight}px`;
  return view.resize();
}

function frame(ts) {
  if (++frames % 10 === 0) fitSize();
  view.render(Math.round(now()), ts);
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);

globalThis.fantastic = {
  /** Raw /qq/song/:mid/lyrics response; null clears the previous track immediately. */
  setLyrics(json, prefer = 'amll') {
    try {
      song = json ? selectSong(JSON.parse(json).candidates, prefer) : null;
      view.setSong(song);
      fitSize();
      view.render(Math.round(now()), performance.now(), true);
      if (json) bridge?.status(song ? 'ready' : 'missing');
    } catch (error) {
      song = null;
      view.setSong(null);
      bridge?.status(`error:${error?.message || error}`);
    }
  },
  sync(pos, playing, seek) {
    const drift = pos - now();
    if (seek || playing !== anchor.playing || Math.abs(drift) > 30) {
      anchor = { pos, at: performance.now(), playing };
    }
    // Word motion is sampled from the audio clock, so pause and both seek directions
    // reconstruct the same state; no queued CSS/WAAPI animation can outlive a seek.
    view.render(Math.round(now()), performance.now(), !!seek);
  },
  relayout() {
    fitSize();
    view.measure();
    view.render(Math.round(now()), performance.now(), true);
  },
  debug() {
    const els = [...document.querySelectorAll('#lyrics *')].filter(e => !e.childElementCount && e.textContent.trim());
    const r = els[0]?.getBoundingClientRect();
    return JSON.stringify({
      ...view.debug(), iw: innerWidth, ih: innerHeight, frames,
      lines: song?.lines.length || 0, textEls: els.length,
      el: [view.element.clientWidth, view.element.clientHeight],
      first: r ? [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)] : null,
      hidden: document.hidden,
    });
  },
};

addEventListener('resize', () => globalThis.fantastic.relayout());
void ensureWordSegmenter(segmenter => view.setWordSegmenter(segmenter), () => {
  view.setSong(song);
  globalThis.fantastic.relayout();
});
bridge?.ready();
