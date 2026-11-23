const APPLE_NS = 'http://music.apple.com/lyric-ttml-internal';
const META_NS = 'http://www.w3.org/ns/ttml#metadata';
const XML_NS = 'http://www.w3.org/XML/1998/namespace';

const children = (node, name) => Array.from(node?.children ?? []).filter(item => item.localName === name);
const first = (node, name) => children(node, name)[0] ?? null;
const attribute = (node, namespace, name, fallback = '') =>
  node?.getAttributeNS(namespace, name) || fallback;
const roleOf = node => attribute(node, META_NS, 'role');

export function parseTime(value = '0') {
  const fields = String(value).trim().split(':');
  if (fields.length > 3 || fields.some(field => !/^\d+(?:\.\d+)?$/.test(field))) {
    throw new Error(`无效的时间：${value}`);
  }
  return Math.round(fields.reduce((seconds, field) => seconds * 60 + Number(field), 0) * 1000);
}

function normalizedLanguage(value) {
  return String(value || '').trim().replaceAll('_', '-').toLowerCase();
}

function languageRank(candidate, preferred) {
  const candidateParts = normalizedLanguage(candidate).split('-').filter(Boolean);
  const preferredParts = normalizedLanguage(preferred).split('-').filter(Boolean);
  if (!preferredParts.length || !candidateParts.length) return 0;
  if (candidateParts.join('-') === preferredParts.join('-')) return 5;
  if (candidateParts[0] !== preferredParts[0]) return 0;
  const candidateScript = candidateParts.find(part => /^[a-z]{4}$/.test(part));
  const preferredScript = preferredParts.find(part => /^[a-z]{4}$/.test(part));
  if (candidateScript && preferredScript) return candidateScript === preferredScript ? 4 : 1;
  const candidateRegion = candidateParts.find(part => /^[a-z]{2}$/.test(part) || /^\d{3}$/.test(part));
  const preferredRegion = preferredParts.find(part => /^[a-z]{2}$/.test(part) || /^\d{3}$/.test(part));
  if (candidateRegion && candidateRegion === preferredRegion) return 3;
  return 2;
}

function chooseLanguage(items, preferred) {
  let chosen = items[0] ?? null;
  let rank = chosen ? languageRank(attribute(chosen, XML_NS, 'lang'), preferred) : -1;
  for (let index = 1; index < items.length; index++) {
    const nextRank = languageRank(attribute(items[index], XML_NS, 'lang'), preferred);
    if (nextRank > rank) {
      chosen = items[index];
      rank = nextRank;
    }
  }
  return chosen;
}

function localizedRecords(items, preferred) {
  const selected = chooseLanguage(items, preferred);
  return {
    node: selected,
    language: attribute(selected, XML_NS, 'lang'),
    type: selected?.getAttribute('type') || '',
    automatic: selected?.getAttribute('automaticallyCreated') === 'true',
    entries: new Map(children(selected, 'text').map(item => [item.getAttribute('for') || '', item])),
  };
}

function inlineRole(node, role, preferred) {
  return chooseLanguage(children(node, 'span').filter(item => roleOf(item) === role), preferred);
}

function textWithoutRoles(node) {
  return Array.from(node?.childNodes ?? [])
    .filter(item => item.nodeType === 3 || (item.nodeType === 1 && !roleOf(item)))
    .map(item => item.textContent || '').join('').trim().replace(/\s+/g, ' ');
}

function wordSpans(node) {
  return Array.from(node?.childNodes ?? []).flatMap(item => {
    if (item.nodeType === 3) {
      const text = item.textContent || '';
      return text && text.trim().length === 0 ? [{ begin: 0, end: 0, text, whitespace: true, spaceAfter: false }] : [];
    }
    if (item.nodeType !== 1 || item.localName !== 'span' || roleOf(item)) return [];
    const text = item.textContent || '';
    return [{
      begin: parseTime(item.getAttribute('begin') || '0'),
      end: parseTime(item.getAttribute('end') || '0'),
      text,
      whitespace: text.trim().length === 0,
      spaceAfter: false,
    }];
  });
}

function finalParenthesisGroup(text) {
  const close = text.trimEnd().length - 1;
  if (text[close] !== ')' && text[close] !== '）') return null;
  const stack = [];
  let open = -1;
  for (let index = 0; index <= close; index++) {
    const char = text[index];
    if (char === '(' || char === '（') {
      if (!stack.length) open = index;
      stack.push(char === '(' ? ')' : '）');
    } else if (char === ')' || char === '）') {
      if (stack.pop() !== char) return null;
      if (index === close && !stack.length && text.slice(open + 1, close).trim()) return [open, close];
    }
  }
  return null;
}

function backingWrapper(text) {
  const group = finalParenthesisGroup(text);
  return group && !text.slice(0, group[0]).trim() ? group : null;
}

export function unwrapBackingText(text) {
  const group = backingWrapper(text);
  return group ? text.slice(group[0] + 1, group[1]).trim() : text;
}

export function unwrapBackingTokens(tokens) {
  const group = backingWrapper(tokens.map(token => token.text).join(''));
  if (!group) return;
  // Work backwards within each span so deleting the closing character preserves the opening index.
  let offset = 0;
  for (const token of tokens) {
    const end = offset + token.text.length;
    for (let index = group.length - 1; index >= 0; index--) {
      const at = group[index] - offset;
      if (at >= 0 && group[index] < end) token.text = token.text.slice(0, at) + token.text.slice(at + 1);
    }
    offset = end;
  }
}

function optionalTime(value) {
  if (!value) return null;
  try {
    const parsed = parseTime(value);
    return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null;
  } catch {
    return null;
  }
}

function qrcBackgroundClock(node, tokens, fallbackBegin, fallbackEnd) {
  const visible = tokens.filter(token => !token.whitespace &&
    Number.isSafeInteger(token.begin) && token.begin >= 0 &&
    Number.isSafeInteger(token.end) && token.end >= token.begin);
  const visibleBegin = visible.length ? Math.min(...visible.map(token => token.begin)) : fallbackBegin;
  const visibleEnd = visible.length ? Math.max(...visible.map(token => token.end)) : fallbackEnd;
  const begin = optionalTime(node.getAttribute('begin'));
  const end = optionalTime(node.getAttribute('end'));
  if (begin === null || end === null || end < begin) {
    return { qrcBegin: visibleBegin, qrcEnd: visibleEnd };
  }
  return { qrcBegin: begin, qrcEnd: end };
}

function voice(node, translationNode, romanizationNode, preferred) {
  const translation = translationNode || inlineRole(node, 'x-translation', preferred);
  const romanization = romanizationNode || inlineRole(node, 'x-roman', preferred);
  const pronunciationTokens = wordSpans(romanization);
  return {
    text: textWithoutRoles(node),
    tokens: wordSpans(node),
    translation: translation?.textContent?.trim() || '',
    pronunciation: pronunciationTokens.length ? '' : textWithoutRoles(romanization),
    pronunciationTokens,
  };
}

function chineseScript(language) {
  const parts = normalizedLanguage(language).split('-');
  if (parts[0] !== 'zh') return '';
  const explicit = parts.find(part => /^[a-z]{4}$/.test(part));
  if (explicit) return explicit;
  const region = parts.find(part => /^[a-z]{2}$/.test(part));
  return ({ cn: 'hans', sg: 'hans', tw: 'hant', hk: 'hant', mo: 'hant' })[region] || '';
}

export function parseTTML(xml, Parser = globalThis.DOMParser, preferredLanguage, qrcBackgroundTiming = false) {
  if (!Parser) throw new Error('需要 DOMParser');
  const document = new Parser().parseFromString(xml, 'application/xml');
  const root = document.documentElement;
  if (!root || root.localName !== 'tt' || document.getElementsByTagName('parsererror').length) {
    throw new Error('无法解析 TTML：XML 格式不正确');
  }
  const body = first(root, 'body');
  if (!body) throw new Error('TTML 缺少 body');

  const head = first(root, 'head');
  const headMetadata = first(head, 'metadata');
  const metadata = first(headMetadata, 'iTunesMetadata');
  const agents = Object.create(null);
  for (const agent of [...children(head, 'agent'), ...children(headMetadata, 'agent')]) {
    if (agent.namespaceURI !== META_NS) continue;
    const id = attribute(agent, XML_NS, 'id');
    if (!id) continue;
    const name = children(agent, 'name').find(item => item.namespaceURI === META_NS)?.textContent?.trim();
    agents[id] = { id, ...(agent.getAttribute('type') ? { type: agent.getAttribute('type') } : {}), ...(name ? { name } : {}) };
  }

  const translationNodes = children(first(metadata, 'translations'), 'translation');
  const replacementNodes = translationNodes.filter(item => item.getAttribute('type') === 'replacement');
  const translationInfo = localizedRecords(translationNodes.filter(item => item.getAttribute('type') !== 'replacement'), preferredLanguage);
  const replacementInfo = localizedRecords(replacementNodes, preferredLanguage);
  const pronunciationInfo = localizedRecords(children(first(metadata, 'transliterations'), 'transliteration'), preferredLanguage);
  const wantedScript = chineseScript(preferredLanguage);
  const replacementScript = chineseScript(replacementInfo.language);
  const allowReplacement = !wantedScript || !replacementScript || wantedScript === replacementScript;

  const audioOffsets = {};
  for (const audio of children(metadata, 'audio')) {
    const name = audio.getAttribute('role');
    const offset = Number(audio.getAttribute('lyricOffset'));
    if (name && Number.isFinite(offset) && offset !== 0) audioOffsets[name] = Math.round(offset * 1000);
  }

  const sections = children(body, 'div').map((section, sectionIndex) => ({
    begin: parseTime(section.getAttribute('begin') || '0'),
    end: parseTime(section.getAttribute('end') || '0'),
    type: (attribute(section, APPLE_NS, 'songPart', 'Verse') || 'Verse').toLowerCase(),
    lines: children(section, 'p').map((paragraph, lineIndex) => {
      const rawBegin = paragraph.getAttribute('begin');
      const rawEnd = paragraph.getAttribute('end');
      const begin = parseTime(rawBegin || '0');
      const end = parseTime(rawEnd || '0');
      const hasParagraphTiming = Boolean(rawBegin && rawEnd && end > begin);
      const key = attribute(paragraph, APPLE_NS, 'key', `${sectionIndex}:${lineIndex}`);
      const translationNode = translationInfo.entries.get(key) ?? inlineRole(paragraph, 'x-translation', preferredLanguage);
      const pronunciationNode = pronunciationInfo.entries.get(key) ?? inlineRole(paragraph, 'x-roman', preferredLanguage);
      const backgroundNode = children(paragraph, 'span').find(item => roleOf(item) === 'x-bg') || null;
      const backgroundTranslations = children(translationNode, 'span').filter(item => roleOf(item) === 'x-bg');
      const background = voice(backgroundNode,
        backgroundTranslations.find(item => textWithoutRoles(item)),
        children(pronunciationNode, 'span').find(item => roleOf(item) === 'x-bg' && textWithoutRoles(item)),
        preferredLanguage);
      const main = voice(paragraph, translationNode, pronunciationNode, preferredLanguage);
      if (qrcBackgroundTiming && backgroundNode) {
        Object.assign(background, qrcBackgroundClock(backgroundNode, background.tokens, begin, end));
      }
      if (backgroundTranslations.length) main.translation = textWithoutRoles(translationNode);

      if (allowReplacement) {
        const replacement = replacementInfo.entries.get(key);
        if (replacement) {
          main.text = textWithoutRoles(replacement);
          main.tokens = wordSpans(replacement);
          const replacementBackground = children(replacement, 'span').find(item => roleOf(item) === 'x-bg');
          if (replacementBackground) {
            background.text = textWithoutRoles(replacementBackground);
            background.tokens = wordSpans(replacementBackground);
          }
        }
      }

      background.text = unwrapBackingText(background.text);
      unwrapBackingTokens(background.tokens);
      background.pronunciation = unwrapBackingText(background.pronunciation);
      unwrapBackingTokens(background.pronunciationTokens);
      background.translation = unwrapBackingText(background.translation);
      if (background.text && !backgroundTranslations.length && !background.translation) {
        const group = finalParenthesisGroup(main.translation);
        if (group) {
          const mainText = main.translation.slice(0, group[0]).trim();
          if (mainText) {
            background.translation = main.translation.slice(group[0] + 1, group[1]).trim();
            main.translation = mainText;
          }
        }
      }
      const agent = attribute(paragraph, META_NS, 'agent');
      return {
        key,
        begin,
        end,
        paragraphBegin: hasParagraphTiming ? begin : null,
        paragraphEnd: hasParagraphTiming ? end : null,
        agent,
        ...main,
        background,
      };
    }),
  }));

  const lines = sections.flatMap(section => section.lines);
  const hasWordTimes = lines.some(item => item.tokens.some(token => !token.whitespace) || item.background.tokens.some(token => !token.whitespace));
  const hasLineTimes = lines.some(item => item.begin || item.end);
  const timing = attribute(root, APPLE_NS, 'timing') || (hasWordTimes ? 'Word' : hasLineTimes ? 'Line' : 'None');
  const duration = parseTime(body.getAttribute('dur') || '0') || Math.max(0, ...lines.map(item => item.end));
  const authors = children(head, 'meta').filter(item => item.getAttribute('key') === 'ttmlAuthorGithubLogin')
    .map(item => item.getAttribute('value') || '');
  const credits = children(first(metadata, 'songwriters'), 'songwriter').map(textWithoutRoles);

  return {
    timing,
    language: attribute(root, XML_NS, 'lang', 'en'),
    duration,
    audioOffsets,
    agents,
    sections,
    lines,
    credits,
    authors,
    translation: { language: translationInfo.language, type: translationInfo.type, automatic: translationInfo.automatic },
    pronunciation: { language: pronunciationInfo.language, automatic: pronunciationInfo.automatic },
  };
}

function lyricStartsRtl(text) {
  const letter = text.match(/\p{L}/u)?.[0];
  return Boolean(letter && letter >= '\u0590' && letter <= '\u06ff');
}

export function duetLineLayout(song) {
  if (song.timing !== 'Word') return null;
  let people = 0;
  let others = 0;
  for (const { type } of Object.values(song.agents)) {
    if (type === 'person') people++;
    else if (type === 'other') others++;
  }
  if (people + others < 2) return null;

  let previous = -1;
  let opposite = false;
  let hasOpposite = false;
  const layout = song.lines.map((line, index) => {
    const type = song.agents[line.agent]?.type;
    let lineOpposite = false;
    const canStart = type === 'person' || type === 'other';
    if (line.agent && type !== 'group' && (previous >= 0 || canStart)) {
      if (previous < 0) {
        opposite = type === 'other';
      } else if (line.agent !== song.lines[previous].agent) {
        // s0 walks past neutral rows and compares the two rows at the previous singer boundary.
        const before = lyricStartsRtl(song.lines[previous].text);
        const after = lyricStartsRtl(song.lines[previous + 1].text);
        if (before === after) opposite = !opposite;
      }
      previous = index;
      lineOpposite = opposite;
      hasOpposite ||= opposite;
    }
    return { duetRightAligned: lyricStartsRtl(line.text) !== lineOpposite, narrow: type !== 'person' || people > 1 };
  });
  return hasOpposite ? layout : null;
}
