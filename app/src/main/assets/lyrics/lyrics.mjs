/*
 * 安卓 WebView 里的歌词页：原生把 Fantastic 的 /qq/song/:mid/lyrics 结果交给 fantastic.setLyrics()，
 * 并用 fantastic.sync() 推送播放进度锚点；这里每帧外推时间交给 AMLL 的 DomLyricPlayer。
 * 点歌词行经 FantasticBridge.seek() 让原生播放器跳转。
 * amllLine / toAmllLines 照搬 Fantastic web/lyrics/panel.mjs。
 */
import { DomLyricPlayer, setWordSegmenter } from './amll-core.mjs';
import { parseTTML, duetSides } from './ttml.mjs';
import { ensureWordSegmenter } from './word-segmenter.mjs';

const bridge = globalThis.FantasticBridge || { ready() {}, seek() {}, status() {} };

const validTokens = (voice) => voice.tokens.filter((token) => token.begin !== 0 || token.end !== 0);
const plainText = (voice) => validTokens(voice).map((t) => t.text + (t.spaceAfter ? ' ' : '')).join('').trim() || voice.text;
const same = (a, b) => a.replace(/[()]/g, '').trim().toLowerCase() === b.replace(/[()]/g, '').trim().toLowerCase();

function amllLine(voice, line, { isBG, isDuet, translation, pronunciation }) {
  const tokens = validTokens(voice);
  const clean = (text) => (isBG ? text.replace(/[()]/g, '') : text);
  const text = plainText(voice);
  const romanTokens = pronunciation && !tokens.every((t) => t.text === voice.pronunciationTokens[voice.tokens.indexOf(t)]?.text);
  const words = tokens.length
    ? tokens.map((token) => {
      const roman = romanTokens ? voice.pronunciationTokens[voice.tokens.indexOf(token)]?.text : '';
      return {
        startTime: token.begin, endTime: Math.max(token.begin, token.end),
        word: clean(token.text) + (token.spaceAfter ? ' ' : ''),
        ...(roman ? { romanWord: clean(roman) } : {}),
      };
    })
    : [{ startTime: line.begin, endTime: Math.max(line.begin, line.end), word: clean(voice.text) }];
  const begin = Math.min(...words.map((w) => w.startTime));
  const end = Math.max(...words.map((w) => w.endTime));
  return {
    words, startTime: begin, endTime: end, isBG, isDuet,
    translatedLyric: translation && voice.translation && !same(voice.translation, text) ? voice.translation : '',
    romanLyric: pronunciation && voice.pronunciation && !same(voice.pronunciation, text) ? voice.pronunciation : '',
  };
}

function toAmllLines(song, options) {
  const sides = duetSides(song);
  return song.lines.flatMap((line, i) => {
    const isDuet = sides[i];
    const main = amllLine(line, line, { ...options, isBG: false, isDuet });
    if (!line.background.tokens.length && !line.background.text) return [main];
    return [main, amllLine(line.background, line, { ...options, isBG: true, isDuet })];
  });
}

/** 同 Fantastic 的 QQ 歌词优先级：AMLL 歌词库 → QRC → LRC（选 QRC 优先时 QRC → AMLL → LRC） */
const PRIORITY = { amll: ['amll', 'qrc', 'lrc'], qrc: ['qrc', 'amll', 'lrc'] };
function selectSong(candidates, prefer) {
  for (const source of PRIORITY[prefer] || PRIORITY.amll) {
    for (const candidate of candidates || []) {
      if (candidate.source !== source || typeof candidate.text !== 'string' || !candidate.text.trim()) continue;
      try {
        const song = parseTTML(candidate.text, undefined, 'zh-Hans');
        if (song?.lines?.length) return song;
      } catch { /* 换下一个来源 */ }
    }
  }
  return null;
}

const view = new DomLyricPlayer();
document.getElementById('lyrics').append(view.getElement());
view.setEnableSpring(true);
view.setEnableScale(true);

let lines = [];
const options = { translation: true, pronunciation: false };

// 播放进度锚点：原生给出的位置（毫秒）与收到时的 performance.now()
let anchor = { pos: 0, at: performance.now(), playing: false };
const now = () => (anchor.playing ? anchor.pos + (performance.now() - anchor.at) : anchor.pos);

view.addEventListener('line-click', (event) => {
  // AMLL 会把行的开始时间提前用于入场动画；跳到第一个词的原始时间
  const line = event.line.getLine();
  bridge.seek(line.words[0]?.startTime ?? line.startTime);
});

let lastFrame = 0;
function frame(ts) {
  view.setCurrentTime(Math.round(now()));
  view.update(lastFrame ? ts - lastFrame : 0);
  lastFrame = ts;
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);

function relayout() {
  if (lines.length) {
    view.setLyricLines(lines, Math.round(now()));
    view.setCurrentTime(Math.round(now()), true);
  }
}

globalThis.fantastic = {
  /** json：/qq/song/:mid/lyrics 的响应文本；null 清空 */
  setLyrics(json, prefer = 'amll') {
    let song = null;
    try { song = json ? selectSong(JSON.parse(json).candidates, prefer) : null; } catch { song = null; }
    lines = song ? toAmllLines(song, options) : [];
    view.setLyricLines(lines, Math.round(now()));
    view.setCurrentTime(Math.round(now()), true);
    if (json) bridge.status(lines.length ? 'ready' : 'missing');
  },
  /** pos：播放位置（毫秒）；seek：刚跳转过，歌词直接跳到位置而不是滚过去 */
  sync(pos, playing, seek) {
    const drift = pos - now();
    // 小于 30ms 的偏差当作测量抖动，不重设锚点，免得歌词来回抖
    if (seek || playing !== anchor.playing || Math.abs(drift) > 30) {
      anchor = { pos, at: performance.now(), playing };
    }
    if (playing) view.resume(); else view.pause();
    if (seek) view.setCurrentTime(Math.round(pos), true);
  },
};

void ensureWordSegmenter(setWordSegmenter, relayout);
bridge.ready();
