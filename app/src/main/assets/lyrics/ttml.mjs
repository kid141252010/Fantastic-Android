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

function voice(node, translationNode, romanizationNode, preferred) {
  const translation = translationNode || inlineRole(node, 'x-translation', preferred);
  const romanization = romanizationNode || inlineRole(node, 'x-roman', preferred);
  return {
    text: textWithoutRoles(node),
    tokens: wordSpans(node),
    translation: translation?.textContent?.trim() || '',
    pronunciation: children(romanization, 'span').length ? '' : textWithoutRoles(romanization),
    pronunciationTokens: wordSpans(romanization),
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

export function parseTTML(xml, Parser = globalThis.DOMParser, preferredLanguage) {
  if (!Parser) throw new Error('需要 DOMParser');
  const document = new Parser().parseFromString(xml, 'application/xml');
  const root = document.documentElement;
  if (!root || root.localName !== 'tt' || document.getElementsByTagName('parsererror').length) {
    throw new Error('无法解析 TTML：XML 格式不正确');
  }
  const body = first(root, 'body');
  if (!body) throw new Error('TTML 缺少 body');

  const head = first(root, 'head');
  const metadata = first(first(head, 'metadata'), 'iTunesMetadata');
  const agents = {};
  for (const agent of children(head, 'agent')) {
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
      const key = attribute(paragraph, APPLE_NS, 'key', `${sectionIndex}:${lineIndex}`);
      const translationNode = translationInfo.entries.get(key);
      const pronunciationNode = pronunciationInfo.entries.get(key);
      const backgroundNode = children(paragraph, 'span').find(item => roleOf(item) === 'x-bg') || null;
      const background = voice(backgroundNode,
        children(translationNode, 'span').find(item => roleOf(item) === 'x-bg') || null,
        children(pronunciationNode, 'span').find(item => roleOf(item) === 'x-bg') || null,
        preferredLanguage);
      const main = voice(paragraph, translationNode, pronunciationNode, preferredLanguage);

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

      const parenthesized = main.translation.match(/[（(]+([^）)]+)[）)]+/)?.[1]?.trim() || '';
      const backgroundTranslation = background.translation.replace(/[()（）]/g, '').trim() || parenthesized;
      if (parenthesized) main.translation = main.translation.replace(/[（(]+[^）)]+[）)]+/, '').trim();
      background.translation = backgroundTranslation;
      const agent = attribute(paragraph, META_NS, 'agent', 'v1');
      return {
        key,
        begin: parseTime(paragraph.getAttribute('begin') || '0'),
        end: parseTime(paragraph.getAttribute('end') || '0'),
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

export function duetSides(song) {
  let previousAgent = '';
  let side = false;
  return song.lines.map(line => {
    const type = song.agents?.[line.agent]?.type;
    if (type === 'group') return false;
    if (!previousAgent) side = type === 'other';
    else if (line.agent !== previousAgent) side = !side;
    previousAgent = line.agent;
    return side;
  });
}
