const CJK = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{M}\u30fc\uff70\ufe00-\ufe0f]/u;
const KANA = /[\p{Script=Hiragana}\p{Script=Katakana}\u30fc\uff70]/u;
const MAX_WORD_LENGTH = 20;

export async function parseDictionary(text) {
  const entries = new Map();
  let lineNumber = 0;
  for (const line of text.split(/\r?\n/)) {
    lineNumber++;
    if (!line) continue;
    const tab = line.lastIndexOf('\t');
    const word = line.slice(0, tab);
    const cost = Number(line.slice(tab + 1));
    if (tab < 1 || !Number.isInteger(cost) || cost < 0 || cost > 255) {
      throw new Error(`Invalid CJK dictionary row ${lineNumber}`);
    }
    entries.set(word.normalize('NFKC'), cost);
    if (lineNumber % 4096 === 0) await new Promise(resolve => setTimeout(resolve, 0));
  }
  if (!entries.size) throw new Error('Empty CJK dictionary');
  return entries;
}

function* cjkWords(text, dictionary, base) {
  const points = [];
  const offsets = [];
  let offset = 0;
  for (const point of text) {
    points.push(point.normalize('NFKC'));
    offsets.push(offset);
    offset += point.length;
  }
  offsets.push(text.length);

  for (let start = 0; start < points.length;) {
    let phrase = '';
    let end = start + 1;
    let bestEnd = end;
    let matched = false;
    for (let cursor = start; cursor < Math.min(points.length, start + MAX_WORD_LENGTH); cursor++) {
      phrase += points[cursor];
      if (dictionary?.has(phrase)) {
        matched = true;
        bestEnd = cursor + 1;
      }
    }
    if (!matched && KANA.test(points[start])) {
      while (end < points.length && KANA.test(points[end])) end++;
      bestEnd = end;
    } else if (!matched) {
      bestEnd = start + 1;
    }
    const beginOffset = offsets[start];
    const endOffset = offsets[bestEnd];
    yield { segment: text.slice(beginOffset, endOffset), index: base + beginOffset, isWordLike: true };
    start = bestEnd;
  }
}

export function createWordSegmenter(dictionary, native = null) {
  return {
    *segment(text) {
      let offset = 0;
      while (offset < text.length) {
        const isCjk = CJK.test(String.fromCodePoint(text.codePointAt(offset)));
        let end = offset;
        while (end < text.length && CJK.test(String.fromCodePoint(text.codePointAt(end))) === isCjk) {
          end += String.fromCodePoint(text.codePointAt(end)).length;
        }
        const run = text.slice(offset, end);
        if (isCjk) yield* cjkWords(run, dictionary, offset);
        else if (native) {
          for (const part of native.segment(run)) {
            yield { segment: part.segment, index: offset + part.index, isWordLike: part.isWordLike };
          }
        } else {
          for (const match of run.matchAll(/[\p{L}\p{N}\p{M}_]+|[^\p{L}\p{N}\p{M}_]/gu)) {
            yield { segment: match[0], index: offset + match.index, isWordLike: /[\p{L}\p{N}]/u.test(match[0]) };
          }
        }
        offset = end;
      }
    },
  };
}

export function needsDictionary(Segmenter = globalThis.Intl?.Segmenter) {
  try {
    const sample = [...new Segmenter('zh', { granularity: 'word' }).segment('生命')];
    return sample.length !== 1 || sample[0].segment !== '生命' || !sample[0].isWordLike;
  } catch {
    return true;
  }
}

async function inflate(response) {
  return new Response(response.body.pipeThrough(new DecompressionStream('gzip'))).text();
}

export function createDictionaryLoader({ Segmenter = globalThis.Intl?.Segmenter, fetcher = globalThis.fetch, decompress = inflate } = {}) {
  let pending;
  return async (install, onReady) => {
    if (!needsDictionary(Segmenter)) return false;
    try {
      pending ??= (async () => {
        let native = null;
        try { native = new Segmenter(undefined, { granularity: 'word' }); } catch {}
        const response = await fetcher(new URL('./cjdict.tsv.gz', import.meta.url));
        if (!response.ok) throw new Error('CJK dictionary unavailable');
        const dictionary = await parseDictionary(await decompress(response));
        return createWordSegmenter(dictionary, native);
      })();
      install(await pending);
      onReady();
      return true;
    } catch {
      return false;
    }
  };
}

export const ensureWordSegmenter = createDictionaryLoader();