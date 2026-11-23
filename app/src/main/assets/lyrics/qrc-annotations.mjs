import { unwrapBackingText, unwrapBackingTokens } from './ttml.mjs';
const MAX_ROWS = 768;
const MAX_SEGMENTS = 256;

const valid = value => Number.isSafeInteger(value) && value >= 0;
const clean = value => String(value || '').replace(/\s+/gu, ' ').trim();
const useful = value => { const text = clean(value); return text && text !== '//' ? text : ''; };
const visible = token => Boolean(token && useful(token.text));

function decodeEntities(value) {
  return value.replace(/&(?:amp|lt|gt|quot|apos|#\d+|#x[\da-f]+);/giu, entity => {
    const name = entity.slice(1, -1).toLowerCase();
    const named = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
    if (named[name]) return named[name];
    const hexadecimal = name.startsWith('#x');
    const number = Number.parseInt(name.slice(hexadecimal ? 2 : 1), hexadecimal ? 16 : 10);
    return Number.isSafeInteger(number) && number <= 0x10ffff && !(number >= 0xd800 && number <= 0xdfff)
      ? String.fromCodePoint(number) : entity;
  });
}

function lyricContent(value) {
  const text = String(value || '');
  const match = /LyricContent\s*=\s*(["'])/i.exec(text);
  if (!match) return text;
  const start = match.index + match[0].length;
  const quote = match[1];
  let cursor = start;
  while (true) {
    const end = text.indexOf(quote, cursor);
    if (end < 0 || end + 1 === text.length || /^\s*\/>/u.test(text.slice(end + 1))) {
      return decodeEntities(end < 0 ? text.slice(start) : text.slice(start, end));
    }
    cursor = end + 1;
  }
}

function tuple(value) {
  const parts = String(value).split(',');
  if (parts.length !== 2 || !/^\d+$/u.test(parts[0]) || !/^\d+$/u.test(parts[1])) return null;
  const begin = Number(parts[0]);
  const duration = Number(parts[1]);
  const end = begin + duration;
  return valid(begin) && valid(duration) && valid(end) ? { begin, end } : null;
}

function parseQrc(value) {
  const text = lyricContent(value);
  const rowMatches = [...text.matchAll(/\[(\d+),(\d+)\]/g)];
  const rows = [];
  const seen = new Set();
  for (let rowIndex = 0; rowIndex < rowMatches.length && rows.length < MAX_ROWS; rowIndex++) {
    const rowMatch = rowMatches[rowIndex];
    const rowTuple = tuple(`${rowMatch[1]},${rowMatch[2]}`);
    if (!rowTuple) continue;
    const start = rowMatch.index + rowMatch[0].length;
    const end = rowMatches[rowIndex + 1]?.index ?? text.length;
    const body = text.slice(start, end);
    const markerMatches = [...body.matchAll(/\(([^()]*)\)/g)];
    const parts = [];
    const tokens = [];
    let cursor = 0;
    for (const marker of markerMatches) {
      const segment = body.slice(cursor, marker.index);
      parts.push(segment);
      const timing = tuple(marker[1]);
      if (timing) {
        if (useful(segment)) {
          tokens.push({ ...timing, text: segment, spaceAfter: /\s$/u.test(segment) });
        }
      } else if (marker[1].includes(',')) {
        parts.push('');
      } else {
        parts.push(marker[0]);
      }
      cursor = marker.index + marker[0].length;
    }
    const tail = body.slice(cursor);
    parts.push(tail);
    if (tokens.length && tail) tokens[tokens.length - 1].text += tail;
    const textValue = useful(parts.join(''));
    if (!textValue) continue;
    const keptTokens = tokens.length > MAX_SEGMENTS ? [] : tokens;
    const key = `${rowTuple.begin},${rowTuple.end}|${textValue}|${keptTokens.map(item => `${item.begin},${item.end},${item.text}`).join('|')}`;
    if (seen.has(key)) continue;
    seen.add(key);
    rows.push({
      begin: rowTuple.begin,
      end: rowTuple.end,
      text: textValue,
      tokens: keptTokens,
      hasDuration: true,
      key,
    });
  }
  rows.sort((a, b) => a.begin - b.begin);
  return rows;
}

function parseLrc(value) {
  const text = lyricContent(value);
  let offset = 0;
  const rows = [];
  const seen = new Set();
  for (const raw of text.split(/\r?\n/u)) {
    const metadata = raw.trim().match(/^\[offset:([+-]?\d+)\]$/iu);
    if (metadata) {
      offset = Number(metadata[1]);
      if (!Number.isSafeInteger(offset)) offset = 0;
      continue;
    }
    const prefix = raw.match(/^(?:(?:\[\d+:\d{2}(?:[.:]\d{1,3})?\])\s*)+/u)?.[0];
    if (!prefix) continue;
    const body = useful(raw.slice(prefix.length));
    if (!body) continue;
    for (const match of prefix.matchAll(/\[(\d+):(\d{2})(?:[.:](\d{1,3}))?\]/gu)) {
      const fraction = (match[3] || '').padEnd(3, '0');
      const timestamp = Number(match[1]) * 60000 + Number(match[2]) * 1000 + Number(fraction || 0);
      const begin = Math.max(0, timestamp - offset);
      if (!valid(begin)) continue;
      const key = `${begin}|${body}`;
      if (seen.has(key)) continue;
      seen.add(key);
      rows.push({ begin, end: begin, text: body, tokens: [], hasDuration: false, key });
      if (rows.length >= MAX_ROWS) return rows;
    }
  }
  rows.sort((a, b) => a.begin - b.begin);
  rows.forEach((row, index) => { row.end = rows[index + 1]?.begin ?? row.begin + 5000; });
  return rows;
}

function parseReadings(value) {
  if (typeof value !== 'string' || !value.trim()) return null;
  const qrc = parseQrc(value);
  if (qrc.length) return qrc;
  const lrc = parseLrc(value);
  return lrc.length ? lrc : null;
}

function finalParenthesisGroup(text) {
  const value = String(text || '');
  const close = value.trimEnd().length - 1;
  if (close < 0 || (value[close] !== ')' && value[close] !== '）')) return null;
  const stack = [];
  let open = -1;
  for (let index = 0; index <= close; index++) {
    const char = value[index];
    if (char === '(' || char === '（') {
      if (!stack.length) open = index;
      stack.push(char === '(' ? ')' : '）');
    } else if (char === ')' || char === '）') {
      if (stack.pop() !== char) return null;
      if (index === close && !stack.length && value.slice(open + 1, close).trim()) return [open, close];
    }
  }
  return null;
}


function voiceBounds(line, voice, background) {
  const configuredBegin = Number(voice.qrcBegin);
  const configuredEnd = Number(voice.qrcEnd);
  if (background && valid(configuredBegin) && valid(configuredEnd) && configuredEnd >= configuredBegin) {
    return { begin: configuredBegin, end: configuredEnd };
  }
  if (!background && valid(Number(line.begin)) && valid(Number(line.end)) && Number(line.end) >= Number(line.begin)) {
    return { begin: Number(line.begin), end: Number(line.end) };
  }
  const timed = (voice.tokens || []).filter(token => visible(token) && valid(token.begin) && valid(token.end) && token.end >= token.begin);
  if (timed.length) return { begin: Math.min(...timed.map(token => token.begin)), end: Math.max(...timed.map(token => token.end)) };
  return { begin: Number(line.begin), end: Number(line.end) };
}

function targetGroups(song) {
  const records = [];
  for (const line of song?.lines || []) {
    for (const background of [false, true]) {
      const voice = background ? line.background : line;
      if (!voice || (!(useful(voice.text)) && !(voice.tokens || []).some(visible))) continue;
      const bounds = voiceBounds(line, voice, background);
      if (!valid(bounds.begin) || !valid(bounds.end) || bounds.end < bounds.begin) continue;
      records.push({ voice, background, begin: bounds.begin, end: bounds.end });
    }
  }
  records.sort((a, b) => a.begin - b.begin);
  const groups = [];
  for (const record of records) {
    const previous = groups.at(-1);
    if (!previous || previous.begin !== record.begin) groups.push({ begin: record.begin, members: [record] });
    else previous.members.push(record);
  }
  for (let index = 0; index < groups.length; index++) {
    const previous = groups[index - 1]?.begin;
    const next = groups[index + 1]?.begin;
    const distance = Math.min(previous === undefined ? Infinity : groups[index].begin - previous, next === undefined ? Infinity : next - groups[index].begin);
    groups[index].window = Number.isFinite(distance) ? Math.min(1000, distance * 0.4) : 1000;
  }
  return groups;
}


const median = values => {
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
};
const grid = (value, size) => Math.round(value / size) * size;

function chooseMember(source, group) {
  const duration = source.end - source.begin;
  if (!source.hasDuration) {
    const main = group.members.filter(member => !member.background);
    return main.length === 1 ? main[0] : group.members.length === 1 ? group.members[0] : null;
  }
  const candidates = group.members.filter(member => Math.abs(member.end - member.begin - duration) <= 20);
  if (candidates.length === 1) return candidates[0];
  const main = candidates.filter(member => !member.background);
  return main.length === 1 ? main[0] : null;
}

function projectMatches(sources, groups, scale, offset) {
  const candidates = [];
  for (let sourceIndex = 0; sourceIndex < sources.length; sourceIndex++) {
    const source = sources[sourceIndex];
    const projected = source.begin * scale + offset;
    const possible = [];
    for (let index = 0; index < groups.length; index++) {
      const group = groups[index];
      const residual = Math.abs(group.begin - projected);
      const member = residual <= group.window ? chooseMember(source, group) : null;
      if (member) possible.push({ sourceIndex, groupIndex: index, member, residual });
    }
    possible.sort((a, b) => a.residual - b.residual || a.groupIndex - b.groupIndex);
    if (possible.length) candidates.push(possible[0]);
  }
  const byMember = new Map();
  for (const candidate of candidates) byMember.set(candidate.member, [...(byMember.get(candidate.member) || []), candidate]);
  const matches = [];
  for (const list of byMember.values()) {
    list.sort((a, b) => a.residual - b.residual || a.sourceIndex - b.sourceIndex);
    if (list.length === 1 || list[0].residual + 10 < list[1].residual) matches.push(list[0]);
  }
  return matches.sort((a, b) => a.sourceIndex - b.sourceIndex);
}

function fitProjections(sources, groups, scale, offset) {
  const matches = projectMatches(sources, groups, scale, offset);
  if (matches.length < 6) return [];
  const count = Math.floor(matches.length / 3);
  const first = matches.slice(0, count);
  const last = matches.slice(-count);
  const t0 = median(first.map(item => sources[item.sourceIndex].begin));
  const t1 = median(last.map(item => sources[item.sourceIndex].begin));
  const d0 = median(first.map(item => groups[item.groupIndex].begin - sources[item.sourceIndex].begin));
  const d1 = median(last.map(item => groups[item.groupIndex].begin - sources[item.sourceIndex].begin));
  if (t1 - t0 < 10000 || Math.abs((d1 - d0) / (t1 - t0)) > 0.02) return [];
  const slope = (d1 - d0) / (t1 - t0);
  const fitted = { scale: 1 + slope, offset: d0 - slope * t0 };
  const refined = projectMatches(sources, groups, fitted.scale, fitted.offset).filter(item => item.residual <= 80);
  if (refined.length < 6) return [fitted];
  const count2 = Math.floor(refined.length / 3);
  const rt0 = median(refined.slice(0, count2).map(item => sources[item.sourceIndex].begin));
  const rt1 = median(refined.slice(-count2).map(item => sources[item.sourceIndex].begin));
  const rd0 = median(refined.slice(0, count2).map(item => groups[item.groupIndex].begin - sources[item.sourceIndex].begin));
  const rd1 = median(refined.slice(-count2).map(item => groups[item.groupIndex].begin - sources[item.sourceIndex].begin));
  if (rt1 - rt0 < 10000) return [fitted];
  const slope2 = (rd1 - rd0) / (rt1 - rt0);
  if (Math.abs(slope2) > 0.02) return [fitted];
  return [fitted, { scale: 1 + slope2, offset: rd0 - slope2 * rt0 }];
}

function alignSources(sources, groups) {
  if (!sources.length || !groups.length) return [];
  const projections = [{ scale: 1, offset: 0 }];
  for (const size of [50, 250]) {
    const counts = new Map();
    for (const source of sources) {
      const rowGrids = new Set(groups.map(group => grid(group.begin - source.begin, size)));
      for (const offset of rowGrids) counts.set(offset, (counts.get(offset) || 0) + 1);
    }
    [...counts.entries()].sort((a, b) => b[1] - a[1] || Math.abs(a[0]) - Math.abs(b[0]) || a[0] - b[0]).slice(0, 6).forEach(([offset]) => projections.push({ scale: 1, offset }));
  }
  for (const projection of [...projections]) {
    const matches = projectMatches(sources, groups, projection.scale, projection.offset);
    if (!matches.length) continue;
    projections.push({ scale: projection.scale, offset: median(matches.map(item => groups[item.groupIndex].begin - sources[item.sourceIndex].begin)) });
    projections.push(...fitProjections(sources, groups, projection.scale, projection.offset));
  }
  const unique = [...new Map(projections.map(item => [`${item.scale}|${item.offset}`, item])).values()];
  const required = Math.max(3, Math.ceil(0.6 * Math.min(sources.length, groups.length)));
  const evaluated = [];
  for (const projection of unique) {
    const matches = projectMatches(sources, groups, projection.scale, projection.offset);
    const anchors = matches.filter(item => item.residual <= 80);
    const anchorGroups = new Set(anchors.map(item => item.groupIndex));
    if (anchorGroups.size < required) continue;
    const first = Math.min(...anchors.map(item => item.sourceIndex));
    const last = Math.max(...anchors.map(item => item.sourceIndex));
    evaluated.push({
      projection,
      matches: matches.filter(item => item.sourceIndex >= first && item.sourceIndex <= last),
      count: anchorGroups.size,
      residual: anchors.reduce((sum, item) => sum + item.residual, 0),
      signature: anchors.map(item => `${item.sourceIndex}:${item.groupIndex}:${item.member.background}`).join('|'),
    });
  }
  if (evaluated.length) {
    evaluated.sort((a, b) => b.count - a.count || a.residual - b.residual || Math.abs(a.projection.scale - 1) - Math.abs(b.projection.scale - 1) || Math.abs(a.projection.offset) - Math.abs(b.projection.offset) || a.projection.scale - b.projection.scale || a.projection.offset - b.projection.offset);
    const best = evaluated[0];
    const tied = evaluated.filter(item => item.count === best.count && item.residual === best.residual);
    if (new Set(tied.map(item => item.signature)).size > 1) return [];
    const highest = best.count;
    return evaluated.filter(item => item.count >= highest - 1)[0].matches;
  }
  return projectMatches(sources, groups, 1, 0).filter(item => item.residual <= 20);
}

function timed(token) {
  return visible(token) && valid(token.begin) && valid(token.end) && token.end >= token.begin && (token.begin !== 0 || token.end !== 0);
}

function readingText(token) {
  const text = String(token?.text || '');
  return token?.spaceAfter && !/\s$/u.test(text) ? `${text} ` : text;
}

export function pairQrcReadings(tokens, readings, clock) {
  const main = (tokens || []).map((token, index) => ({ token, index })).filter(item => timed(item.token));
  const source = (readings || []).filter(timed);
  if (!main.length || !source.length) return null;
  const sourceBegin = Number(clock?.sourceBegin);
  const sourceEnd = Number(clock?.sourceEnd);
  const targetBegin = Number(clock?.targetBegin);
  const targetEnd = Number(clock?.targetEnd);
  const projections = [];
  if ([sourceBegin, sourceEnd, targetBegin, targetEnd].every(Number.isFinite)) {
    projections.push(item => [item.begin + targetBegin - sourceBegin, item.end + targetBegin - sourceBegin]);
    const sourceDuration = sourceEnd - sourceBegin;
    const targetDuration = targetEnd - targetBegin;
    const ratio = targetDuration / sourceDuration;
    if (sourceDuration > 0 && targetDuration > 0 && ratio >= 0.5 && ratio <= 2) projections.push(item => [targetBegin + (item.begin - sourceBegin) * ratio, targetBegin + (item.end - sourceBegin) * ratio]);
  }
  const firstMain = main[0].token;
  const firstReading = source[0];
  projections.push(item => [item.begin + firstMain.begin - firstReading.begin, item.end + firstMain.begin - firstReading.begin]);
  for (const project of projections) {
    const mapped = [];
    let previous = -1;
    let possible = true;
    for (const reading of source) {
      const interval = project(reading).map(Math.round);
      const candidates = [];
      for (const item of main) {
        const begin = item.token.begin;
        const end = item.token.end;
        const exact = interval[0] === begin && interval[1] === end;
        const length = Math.max(0, interval[1] - interval[0]);
        const overlap = Math.max(0, Math.min(interval[1], end) - Math.max(interval[0], begin));
        const contained = interval[0] >= begin - 10 && interval[1] <= end + 10 && (length === 0 ? interval[0] >= begin - 10 && interval[0] <= end + 10 : overlap >= length / 2);
        if (exact || contained) candidates.push({ item, exact });
      }
      const exact = candidates.filter(candidate => candidate.exact);
      const selected = exact.length ? exact : candidates;
      if (!selected.length || selected.length !== 1 || selected[0].item.index < previous) { possible = false; break; }
      previous = selected[0].item.index;
      mapped.push({ reading, target: selected[0].item });
    }
    const distinct = new Set(mapped.map(item => item.target.index));
    const spans = new Map();
    for (const item of mapped) {
      const interval = project(item.reading).map(Math.round);
      const span = spans.get(item.target.index) || { target: item.target, begin: Infinity, end: -Infinity };
      span.begin = Math.min(span.begin, interval[0]);
      span.end = Math.max(span.end, interval[1]);
      spans.set(item.target.index, span);
    }
    const accurate = [...spans.values()].filter(span =>
      Math.abs(span.begin - span.target.token.begin) <= 10 && Math.abs(span.end - span.target.token.end) <= 10,
    ).length;
    const exactCoverage = mapped.length === main.length && mapped.every(item => {
      const interval = project(item.reading).map(Math.round);
      return interval[0] === item.target.token.begin && interval[1] === item.target.token.end;
    });
    if (!possible || (distinct.size < 3 && !exactCoverage) || accurate * 2 < distinct.size) continue;
    const joined = new Map();
    for (const item of mapped) joined.set(item.target.index, `${joined.get(item.target.index) || ''}${readingText(item.reading)}`);
    return (tokens || []).map((token, index) => ({ ...token, pronunciation: joined.get(index) || '' }));
  }
  return null;
}

function applyCategory(raw, category, groups) {
  const sources = parseReadings(raw);
  if (!sources) return;
  for (const group of groups) for (const target of group.members) {
    if (category === 'translation') target.voice.translation = '';
    else {
      target.voice.pronunciation = '';
      target.voice.pronunciationTokens = [];
      delete target.voice.qrcReadingClock;
    }
  }
  for (const match of alignSources(sources, groups)) {
    const target = groups[match.groupIndex].members.find(member => member.voice === match.member.voice);
    if (!target) continue;
    let text = sources[match.sourceIndex].text;
    let tokens = sources[match.sourceIndex].tokens;
    if (target.background) {
      text = unwrapBackingText(text);
      tokens = tokens.map(token => ({ ...token }));
      unwrapBackingTokens(tokens);
    }
    if (category === 'translation') target.voice.translation = text;
    else {
      target.voice.pronunciation = text;
      if (tokens.length) target.voice.pronunciationTokens = tokens;
      target.voice.qrcReadingClock = { sourceBegin: sources[match.sourceIndex].begin, sourceEnd: sources[match.sourceIndex].end, targetBegin: target.begin, targetEnd: target.end };
    }
  }
}

export function applyQrcAnnotations(song, { rawRomaText, rawTransText } = {}) {
  const groups = targetGroups(song);
  if (!groups.length || groups.length > MAX_ROWS) return song;
  applyCategory(rawRomaText, 'pronunciation', groups);
  applyCategory(rawTransText, 'translation', groups);
  return song;
}

export { unwrapBackingText, unwrapBackingTokens };
