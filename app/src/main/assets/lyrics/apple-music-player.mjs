import { duetSides } from './ttml.mjs';
import { clamp, curves, motion, lineDuration, lineStagger, syllableLift,
  emphasisEnvelope, interludeScale } from './apple-music-motion.mjs';

const same = (a, b) => a.replace(/[()]/g, '').trim().toLowerCase() === b.replace(/[()]/g, '').trim().toLowerCase();
const splitLift = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}\p{Script=Thai}]/u;
const joinedScript = /[\p{Script=Arabic}\p{Script=Devanagari}]/u;
const rtlText = /^[^\p{L}]*[\p{Script=Arabic}\p{Script=Hebrew}]/u;
const node = (tag, className, text) => {
  const el = document.createElement(tag);
  el.className = className;
  if (text !== undefined) el.textContent = text;
  return el;
};
const nativeSegmenter = (granularity) => {
  try { return new Intl.Segmenter(undefined, { granularity }); } catch { return null; }
};

/** DOM rendering of the supplied Android APK's lyric motion, independent of AMLL. */
export class AppleMusicLyrics {
  constructor(host, seek) {
    this.element = node('div', 'apple-music-lyrics');
    this.element.setAttribute('role', 'region');
    this.element.setAttribute('aria-label', '歌词');
    this.content = node('div', 'lyrics-lines');
    this.element.append(this.content);
    host.append(this.element);
    this.seek = seek;
    this.segmenter = nativeSegmenter('word');
    this.graphemes = nativeSegmenter('grapheme');
    this.rows = [];
    this.size = [0, 0];
    this.focus = -1;
    this.time = 0;
    this.lastTime = NaN;
    this.manualUntil = 0;
    this.animating = false;
    this.dirty = true;
    // Browsing is a host adaptation; native Android's RecyclerView handles the gesture itself.
    this.element.addEventListener('pointerdown', () => this.browse(Infinity));
    const release = () => { if (this.manualUntil) this.manualUntil = performance.now() + 5000; };
    addEventListener('pointerup', release);
    addEventListener('pointercancel', release);
    this.element.addEventListener('wheel', () => this.browse(performance.now() + 5000), { passive: true });
    this.element.addEventListener('scroll', () => { this.dirty = true; }, { passive: true });
    this.reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
  }

  setWordSegmenter(segmenter) {
    this.segmenter = segmenter;
  }

  segments(text) {
    return this.segmenter ? this.segmenter.segment(text) :
      [...text.matchAll(/[\p{L}\p{N}\p{M}]+|[^\p{L}\p{N}\p{M}]/gu)].map(m => ({ segment: m[0], index: m.index }));
  }

  characters(text) {
    return this.graphemes ? [...this.graphemes.segment(text)].map(s => s.segment) :
      text.match(/[^\p{M}]\p{M}*|\p{M}+/gu) || [];
  }

  voice(source, line, background) {
    const element = node('div', `lyric-voice${background ? ' background-voice' : ''}`);
    const clean = text => background ? text.replace(/[()]/g, '') : text;
    const tokens = source.tokens.filter(t => t.begin !== 0 || t.end !== 0);
    const words = (tokens.length ? tokens : [{ text: source.text, begin: line.begin, end: line.end }]).map(t => ({
      text: clean(t.text) + (t.spaceAfter ? ' ' : ''), begin: t.begin, end: Math.max(t.begin, t.end),
      parts: [], width: 0, splitLift: splitLift.test(t.text), joined: joinedScript.test(t.text),
    }));
    let text = '';
    for (const word of words) { word.offset = text.length; text += word.text; }
    element.dir = rtlText.test(text) ? 'rtl' : 'ltr';
    const groups = [];
    let wordIndex = 0;
    for (const segment of this.segments(text)) {
      if (/^\s+$/u.test(segment.segment)) { element.append(document.createTextNode(segment.segment)); continue; }
      const group = { element: node('span', 'lyric-word-group'), parts: [], begin: Infinity, end: 0, text: segment.segment };
      const start = segment.index, end = start + segment.segment.length;
      while (wordIndex < words.length && words[wordIndex].offset + words[wordIndex].text.length <= start) wordIndex++;
      for (let j = wordIndex; j < words.length && words[j].offset < end; j++) {
        const word = words[j];
        const partText = text.slice(Math.max(start, word.offset), Math.min(end, word.offset + word.text.length));
        group.begin = Math.min(group.begin, word.begin);
        group.end = Math.max(group.end, word.end);
        // Keep joined scripts intact; per-letter transforms would break Arabic/Indic shaping.
        for (const character of word.joined ? [partText] : this.characters(partText)) {
          const el = node('span', 'lyric-word-piece', character);
          if (word.joined) el.classList.add('joined-script');
          const part = { element: el, word, group, index: group.parts.length, width: 0, prefix: 0 };
          group.parts.push(part);
          word.parts.push(part);
          group.element.append(el);
        }
      }
      group.duration = group.end - group.begin;
      group.emphasis = this.timing === 'Word' && !background && group.duration >= 1000 &&
        group.parts.length <= 7 && !splitLift.test(group.text) && !joinedScript.test(group.text);
      group.peak = (motion.emphasisScale - 1) * clamp(group.duration / 1000 - 1);
      element.append(group.element);
      groups.push(group);
    }
    const voice = { element, words, groups, background, rtl: element.dir === 'rtl' };
    const translation = source.translation && !same(source.translation, text) ? source.translation : '';
    if (translation) element.append(node('div', 'lyric-translation', clean(translation)));
    return voice;
  }

  setSong(song) {
    this.song = song;
    this.timing = song?.timing || 'None';
    this.rows = [];
    this.content.replaceChildren();
    this.focus = -1;
    this.manualUntil = 0;
    this.animating = false;
    this.element.scrollTop = 0;
    const add = (row) => {
      row.offset = 0;
      row.scale = motion.normalScale;
      row.scaleFrom = row.scale;
      row.scaleAt = -Infinity;
      row.active = false;
      this.rows.push(row);
      this.content.append(row.element);
    };
    const gap = (begin, end) => {
      if (this.timing === 'None' || end - begin < motion.interludeMinMs) return;
      const element = node('div', 'lyric-line interlude');
      const dots = node('div', 'interlude-dots');
      dots.setAttribute('aria-label', '间奏');
      for (let i = 0; i < 3; i++) dots.append(node('span', 'interlude-dot'));
      element.append(dots);
      add({ kind: 'interlude', begin, end, focusAt: begin, duration: motion.lineDefaultMs, element, dots });
    };
    const sides = song ? duetSides(song) : [];
    let previousEnd = 0;
    for (let i = 0; i < (song?.lines.length || 0); i++) {
      const line = song.lines[i];
      const timed = [...line.tokens, ...line.background.tokens].filter(t => t.begin !== 0 || t.end !== 0);
      const begin = line.begin || (timed.length ? Math.min(...timed.map(t => t.begin)) : 0);
      const end = Math.max(begin, line.end, ...timed.map(t => t.end));
      gap(previousEnd, begin);
      // The native callback derives THIS move's duration from the FOLLOWING gap;
      // the incoming event lead was established by the preceding line's callback.
      const next = song.lines[i + 1];
      const nextTokens = next ? [...next.tokens, ...next.background.tokens].filter(t => t.begin !== 0 || t.end !== 0) : [];
      const nextBegin = next ? next.begin || (nextTokens.length ? Math.min(...nextTokens.map(t => t.begin)) : 0) : end;
      const duration = this.timing === 'Word' ? lineDuration(nextBegin - end) : motion.lineDefaultMs;
      const lead = i && this.timing === 'Word' ? lineDuration(begin - previousEnd) : 500;
      const element = node('div', `lyric-line${sides[i] ? ' duet' : ''}`);
      element.setAttribute('role', 'button');
      element.setAttribute('aria-label', [line.text, line.translation, line.background.text, line.background.translation].filter(Boolean).join('，'));
      element.tabIndex = 0;
      element.dataset.begin = String(begin);
      const row = { kind: 'lyric', element, begin, end, duration, focusAt: begin - lead, voices: [] };
      const main = this.voice(line, { begin, end }, false);
      row.voices.push(main);
      element.append(main.element);
      if (line.background.text || line.background.tokens.length) {
        const background = this.voice(line.background, { begin, end }, true);
        row.voices.push(background);
        element.append(background.element);
      }
      const seek = () => this.seek(line.tokens.find(t => t.begin || t.end)?.begin ?? begin);
      element.addEventListener('click', seek);
      element.addEventListener('keydown', event => {
        if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); seek(); }
      });
      add(row);
      previousEnd = Math.max(previousEnd, end);
    }
    if (song) {
      gap(previousEnd, song.duration);
      if (song.credits.length) {
        const element = node('div', 'lyric-line lyric-credits', `词曲作者：${song.credits.join('、')}`);
        add({ kind: 'credits', element, begin: previousEnd, end: Infinity,
          focusAt: Math.max(previousEnd, song.duration) + 1000, duration: 1000 });
      }
    }
    this.measure();
    this.lastTime = NaN;
    this.dirty = true;
  }

  measure() {
    for (const row of this.rows) {
      row.top = row.element.offsetTop;
      row.height = row.element.offsetHeight;
      for (const voice of row.voices || []) {
        for (const word of voice.words) {
          word.width = 0;
          for (let i = 0; i < word.parts.length; i++) {
            const part = word.parts[i];
            part.prefix = word.width;
            part.width = part.element.offsetWidth;
            part.liftDelay = word.splitLift ? i * (word.end - word.begin) / word.parts.length : 0;
            word.width += part.width;
          }
        }
      }
    }
  }

  resize() {
    const width = this.element.clientWidth, height = this.element.clientHeight;
    if (!width || !height || (width === this.size[0] && height === this.size[1])) return false;
    this.size = [width, height];
    this.content.style.paddingTop = `${Math.round(height * 0.08)}px`;
    this.content.style.paddingBottom = `${height}px`;
    this.measure();
    this.render(this.time, performance.now(), true);
    return true;
  }

  browse(until) {
    if (this.animating && this.focus >= 0) {
      const row = this.rows[this.focus];
      this.element.scrollTop -= this.remaining(row, performance.now());
      for (const item of this.rows) { item.offset = 0; item.element.style.transform = ''; }
      this.animating = false;
    }
    this.manualUntil = until;
    this.dirty = true;
  }

  remaining(row, wallTime) {
    if (!row.offset) return 0;
    return row.offset * (1 - curves.line(clamp((wallTime - row.moveAt) / row.moveDuration)));
  }

  position(index, wallTime, snap) {
    if (index < 0 || !this.rows.length) return;
    const previous = this.element.scrollTop;
    const target = Math.max(0, this.rows[index].top - Math.round(this.size[1] * 0.08));
    this.element.scrollTop = target;
    const delta = this.element.scrollTop - previous;
    let moving = false;
    for (let i = 0; i < this.rows.length; i++) {
      const row = this.rows[i];
      row.offset = snap ? 0 : this.remaining(row, wallTime) + delta;
      row.moveAt = wallTime + lineStagger(i - index);
      row.moveDuration = this.rows[index].duration;
      moving ||= row.offset !== 0;
    }
    this.animating = moving;
  }

  paintVoice(voice, active, time) {
    const staticLyrics = this.timing === 'None';
    for (const group of voice.groups) {
      let extraWidth = 0;
      for (const part of group.parts) {
        part.envelope = active && group.emphasis && !this.reducedMotion.matches ?
          emphasisEnvelope(time - group.begin + motion.wordLeadMs, group.duration, part.index, group.parts.length) : 0;
        part.expansion = group.peak * part.envelope * part.width;
        extraWidth += part.expansion;
      }
      let preceding = 0;
      for (const part of group.parts) {
        const word = part.word;
        const progress = staticLyrics || (active && this.timing !== 'Word') ? 1 : !active ? 0 :
          word.end <= word.begin ? Number(time >= word.begin) :
            clamp((time - word.begin + motion.wordLeadMs) / (word.end - word.begin));
        const frontier = progress * (word.width + motion.featherPx) - part.prefix;
        const bright = staticLyrics ? 0.8 : voice.background ? 0.35 : motion.primaryAlpha;
        const image = `linear-gradient(to ${voice.rtl ? 'left' : 'right'},rgba(255,255,255,${bright}) ${frontier - motion.featherPx}px,rgba(255,255,255,${motion.tertiaryAlpha}) ${frontier}px)`;
        if (image !== part.image) { part.element.style.backgroundImage = image; part.image = image; }
        const lift = active && this.timing === 'Word' && !word.joined && !this.reducedMotion.matches ?
          syllableLift(time - word.begin + motion.wordLeadMs - part.liftDelay) : 0;
        const spread = (preceding + part.expansion / 2 - extraWidth / 2) * (voice.rtl ? -1 : 1);
        preceding += part.expansion;
        const transform = `translate(${spread.toFixed(3)}px,${lift.toFixed(3)}px) scale(${(1 + group.peak * part.envelope).toFixed(5)})`;
        if (transform !== part.transform) { part.element.style.transform = transform; part.transform = transform; }
        const shadow = part.envelope > 0 ? `0 0 ${motion.emphasisShadowPx}px rgba(255,255,255,${0.5 * part.envelope})` : 'none';
        if (shadow !== part.shadow) { part.element.style.textShadow = shadow; part.shadow = shadow; }
      }
    }
  }

  paintInterlude(row, time) {
    const elapsed = time - row.begin, duration = row.end - row.begin;
    let scale = this.reducedMotion.matches ? 1 : interludeScale(elapsed, duration);
    let alpha = 1;
    // C4022z.M: 750 ms expansion followed by 250 ms shrink/fade on removal.
    const exitAt = duration - motion.interludeEnterMs - motion.interludeExitMs;
    if (elapsed >= exitAt && elapsed < duration - motion.interludeExitMs) {
      const p = curves.emphasis(clamp((elapsed - exitAt) / motion.interludeEnterMs));
      scale += (1.2 - scale) * p;
    } else if (elapsed >= duration - motion.interludeExitMs) {
      const p = curves.interludeExit(clamp((elapsed - duration + motion.interludeExitMs) / motion.interludeExitMs));
      scale = 1.2 + (0.5 - 1.2) * p;
      alpha = 1 - p;
    }
    row.dots.style.transform = `scale(${scale})`;
    row.dots.style.opacity = String(alpha);
    const interval = Math.round((duration + 750) / 3);
    for (let i = 0; i < 3; i++) {
      const dot = row.dots.children[i];
      const span = i === 2 ? duration - 2 * interval : interval;
      const p = curves.interludeProgress(clamp((elapsed - i * interval) / span));
      dot.style.backgroundColor = `rgba(255,255,255,${motion.tertiaryAlpha + (motion.primaryAlpha - motion.tertiaryAlpha) * p})`;
      dot.style.opacity = String(this.reducedMotion.matches ? 1 : clamp((elapsed - i * motion.dotStaggerMs) / 750) ** 2);
    }
  }

  render(time, wallTime, snap = false) {
    this.time = time;
    if (snap) this.manualUntil = 0;
    if (!this.size[1]) return;
    const browsing = wallTime < this.manualUntil || this.timing === 'None';
    const returning = this.manualUntil && !browsing;
    if (returning) this.manualUntil = 0;
    if (time === this.lastTime && !this.animating && !this.dirty && !snap && !returning) return;
    let focus = this.rows.length ? 0 : -1;
    for (let i = 0; i < this.rows.length; i++) {
      if (this.rows[i].focusAt <= time) focus = i;
    }
    if (!browsing && (focus !== this.focus || snap || returning)) {
      this.position(focus, wallTime, snap || this.reducedMotion.matches);
    }
    this.focus = focus;
    let moving = false;
    for (let i = 0; i < this.rows.length; i++) {
      const row = this.rows[i];
      const active = this.timing === 'None' || i === focus ||
        (row.kind === 'lyric' && time >= row.begin && time < row.end);
      if (row.active !== active) {
        row.scaleFrom = row.scale;
        row.scaleAt = wallTime;
        row.active = active;
        row.element.dataset.active = String(active);
      }
      if (snap || this.reducedMotion.matches) {
        row.scaleFrom = active ? motion.activeScale : motion.normalScale;
        row.scaleAt = -Infinity;
      }
      const p = snap || this.reducedMotion.matches ? 1 : curves.line(clamp((wallTime - row.scaleAt) / row.duration));
      row.scale = row.scaleFrom + ((active ? motion.activeScale : motion.normalScale) - row.scaleFrom) * p;
      const offset = this.remaining(row, wallTime);
      if (Math.abs(offset) < 0.05) row.offset = 0;
      moving ||= row.offset !== 0 || p < 1;
      const y = row.top - this.element.scrollTop + offset;
      const visible = y + row.height > -20 && y < this.size[1] + 20;
      const visibility = visible ? 'visible' : 'hidden';
      if (row.element.style.visibility !== visibility) row.element.style.visibility = visibility;
      if (!visible) continue;
      row.element.style.transform = `translateY(${offset.toFixed(3)}px) scale(${row.scale.toFixed(5)})`;
      const topFade = clamp((y + row.height) / (this.size[1] * 0.07));
      const bottomFade = clamp((this.size[1] - y) / (this.size[1] * 0.2775));
      row.element.style.opacity = String(Math.min(topFade, bottomFade));
      if (row.kind === 'lyric') {
        for (const voice of row.voices) this.paintVoice(voice, active, time);
      } else if (row.kind === 'interlude') this.paintInterlude(row, time);
      else {
        const elapsed = time - row.begin - 1000;
        row.element.style.opacity = String(Math.min(topFade, bottomFade) * curves.credits(clamp(elapsed / 1000)));
      }
    }
    this.animating = moving;
    this.lastTime = time;
    this.dirty = false;
  }

  debug() {
    return { renderer: 'apple-music-android', size: this.size, focus: this.focus,
      rows: this.rows.length, timing: this.timing, browsing: this.manualUntil !== 0 };
  }
}
