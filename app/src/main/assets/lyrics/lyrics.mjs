import { parseTTML, duetLineLayout } from './ttml.mjs';
import { applyQrcAnnotations, pairQrcReadings } from './qrc-annotations.mjs';

const emptyVoice = () => ({ text: '', tokens: [] });
const line = (begin, end, text, tokens = []) => ({ begin, end, text, tokens, background: emptyVoice() });

// NetEase times are absolute milliseconds. LRC may have repeated timestamps and an offset.
function parseTimedText(value, word) {
  const lines = [];
  const text = value || '';
  const offset = Number(text.match(/\[offset:([+-]?\d+)\]/i)?.[1] || 0);
  for (const raw of text.split(/\r?\n/)) {
    if (word) {
      const row = raw.trim().match(/^\[(\d+),(\d+)\](.*)$/);
      if (!row) continue;
      const tokens = [...row[3].matchAll(/\((\d+),(\d+)(?:,\d+)?\)([^]*?)(?=\(\d+,\d+(?:,\d+)?\)|$)/g)]
        .map(m => ({ begin: Number(m[1]), end: Number(m[1]) + Number(m[2]), text: m[3], spaceAfter: false }));
      const sung = tokens.map(t => t.text).join('');
      if (sung.trim()) lines.push(line(Number(row[1]), Math.max(Number(row[1]) + Number(row[2]), ...tokens.map(t => t.end)), sung, tokens));
    } else {
      const sung = raw.replace(/\[[^\]]*\]/g, '').trim();
      if (!sung) continue;
      for (const m of raw.matchAll(/\[(\d+):(\d{2})(?:[.:](\d{1,3}))?\]/g)) {
        lines.push(line(Math.max(0, +m[1] * 60000 + +m[2] * 1000 + +((m[3] || '').padEnd(3, '0')) + offset), 0, sung));
      }
    }
  }
  lines.sort((a, b) => a.begin - b.begin);
  if (!word) lines.forEach((item, i) => { item.end = lines[i + 1]?.begin ?? item.begin + 5000; });
  return { timing: word ? 'Word' : 'Line', duration: lines.at(-1)?.end || 0, lines, agents: {} };
}

function applyAnnotations(song, data) {
  const translations = parseTimedText(data.rawTransText, false).lines;
  const romans = parseTimedText(data.rawRomaText, false).lines;
  const romanWords = parseTimedText(data.rawYromaText, true).lines;
  function nearest(lines, time) {
    let match = null, distance = 501;
    for (const item of lines) {
      const delta = Math.abs(item.begin - time);
      if (delta < distance) { match = item; distance = delta; }
    }
    return match;
  }
  for (const item of song.lines) {
    item.translation = nearest(translations, item.begin)?.text || '';
    const words = nearest(romanWords, item.begin);
    item.pronunciationTokens = words?.tokens || [];
    item.pronunciation = nearest(romans, item.begin)?.text || '';
  }
}

function select(kind, payload, language) {
  if (kind === 'apple') return parseTTML(payload, undefined, language);
  const data = JSON.parse(payload);
  const order = kind === 'ncm' ? ['amll', 'yrc', 'lrc'] : ['amll', 'qrc', 'lrc'];
  for (const source of order) for (const candidate of data.candidates || []) {
    if (candidate.source !== source || typeof candidate.text !== 'string' || !candidate.text.trim()) continue;
    try {
      const rawNcm = kind === 'ncm' && source !== 'amll';
      const song = rawNcm ? parseTimedText(candidate.text, source === 'yrc') :
        parseTTML(candidate.text, undefined, language, kind === 'qq' && source === 'qrc');
      if (!song?.lines?.length) continue;
      if (rawNcm) applyAnnotations(song, data);
      if (kind === 'qq' && source === 'qrc') applyQrcAnnotations(song, data);
      return song;
    } catch { /* Malformed candidate: keep the upstream fallback order. */ }
  }
  return null;
}

const isTimed = token => token.begin !== 0 || token.end !== 0;
const tokenText = token => {
  const text = token?.text || '';
  return token?.spaceAfter && !/\s$/u.test(text) ? `${text} ` : text;
};
const readingText = tokens => tokens.map(tokenText).join('').trim();
const normalizedReading = text => text.replace(/\s+/g, ' ').trim();

function normalizeContainerTokens(tokens, rightAligned) {
  const result = [];
  let pending = '';
  for (const token of tokens || []) {
    const text = tokenText(token);
    const first = text.search(/\S/u);
    if (first < 0) {
      if (text) pending = ' ';
      continue;
    }
    const end = text.trimEnd().length;
    const leading = text.slice(0, first);
    const gap = pending || leading ? ' ' : '';
    if (result.length && !rightAligned && gap) result[result.length - 1].text += gap;
    const prefix = result.length && rightAligned ? gap : '';
    result.push({ ...token, text: `${prefix}${text.slice(first, end)}`, whitespace: false, spaceAfter: false });
    pending = text.length > end ? ' ' : '';
  }
  return result;
}

function pairReadings(tokens, readings) {
  if (!tokens.some(isTimed) || !readings.length) return null;
  const starts = new Map();
  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i];
    const text = tokenText(token);
    if (!isTimed(token) || !text.trim()) continue;
    if (!starts.has(token.begin)) starts.set(token.begin, []);
    starts.get(token.begin).push(i);
  }
  const paired = new Map();
  // Untimed but explicitly segmented annotations can be positional. Timed ones must never be zipped by count.
  const positional = readings.length === tokens.length && !readings.some(isTimed);
  let lastIndex = -1;
  for (let i = 0; i < readings.length; i++) {
    const reading = readings[i];
    const readingValue = tokenText(reading);
    if (!readingValue.trim()) continue;
    let index = positional ? i : undefined;
    if (!positional && isTimed(reading)) {
      const sameStart = starts.get(reading.begin);
      if (sameStart?.length === 1) index = sameStart[0];
      else if (sameStart?.length) {
        const sameEnd = sameStart.filter(i => tokens[i].end === reading.end);
        if (sameEnd.length === 1) index = sameEnd[0];
      } else {
        // A more finely segmented reading can still belong to one original span.
        const containing = tokens.flatMap((token, i) => {
          const text = tokenText(token);
          return text.trim() && token.begin < reading.begin && reading.begin < token.end && reading.end <= token.end ? [i] : [];
        });
        if (containing.length === 1) index = containing[0];
      }
    }
    if (index === undefined || index < lastIndex) return null;
    lastIndex = index;
    paired.set(index, (paired.get(index) || '') + readingValue);
  }
  return paired.size ? tokens.map((token, i) => paired.has(i) ? { ...token, pronunciation: paired.get(i) } : token) : null;
}

function voice(value, wordTimed, rightAligned) {
  const tokens = normalizeContainerTokens(value.tokens || [], rightAligned);
  const readings = normalizeContainerTokens(value.pronunciationTokens || [], rightAligned);
  const original = normalizedReading(value.text) || normalizedReading(readingText(tokens));
  const timedText = readingText(readings);
  const timedReading = original && normalizedReading(timedText) === original ? '' : timedText;
  const plainText = value.pronunciation || '';
  const plainReading = original && normalizedReading(plainText) === original ? '' : plainText;
  const paired = wordTimed && timedReading ?
    (value.qrcReadingClock ? pairQrcReadings(tokens, readings, value.qrcReadingClock) : pairReadings(tokens, readings)) : null;
  return { text: value.text, tokens: paired || tokens, translation: value.translation || '',
    pronunciation: paired ? '' : plainReading || timedReading };
}

globalThis.parseFantasticLyrics = (kind, payload, language) => {
  try {
    const song = select(kind, payload, language);
    if (!song?.lines?.length) return null;
    const duetLayout = duetLineLayout(song);
    return {
      timing: song.timing, duration: song.duration, credits: song.credits || [], audioOffsets: song.audioOffsets || {},
      translationAutomatic: song.translation?.automatic || false,
      pronunciationAutomatic: song.pronunciation?.automatic || false,
      lines: song.lines.map((item, i) => {
        const background = item.background || emptyVoice();
        const rawTokens = [...item.tokens, ...background.tokens].filter(t => t.begin || t.end);
        const begin = item.begin || (rawTokens.length ? Math.min(...rawTokens.map(t => t.begin)) : 0);
        const rightAligned = duetLayout?.[i]?.duetRightAligned === true;
        return { begin, end: Math.max(begin, item.end, ...rawTokens.map(t => t.end)),
          paragraphBegin: item.paragraphBegin ?? null, paragraphEnd: item.paragraphEnd ?? null,
          main: voice(item, song.timing === 'Word', rightAligned),
          background: voice(background, song.timing === 'Word', rightAligned),
          ...duetLayout?.[i] };
      }),
    };
  } catch (error) { return { error: String(error?.message || error) }; }
};
globalThis.FantasticBridge?.ready();

