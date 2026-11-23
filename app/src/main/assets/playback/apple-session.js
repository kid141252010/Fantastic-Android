/* Android session additions to the hosted Fantastic player. No audio decoding or key handling. */
(function () {
  const player = window.AmApp?.player;
  const decoder = window.AmDecrypt;
  if (!player || player.fantasticSessionInstalled) return;
  const prepareNext = player.prepareNext;
  if (typeof prepareNext !== 'function' || typeof decoder?.openTrack !== 'function') return;
  player.fantasticSessionInstalled = true;

  // Keep the prepared playlist for a repeat seam.
  let reuse = null;
  const openTrack = decoder.openTrack;
  decoder.openTrack = function (url, signal) {
    if (reuse?.url === url) return Promise.resolve(reuse.playlist);
    return openTrack.call(this, url, signal);
  };

  function repeatCurrent(player) {
    return player.repeat === 'one' || (player.repeat === 'all' && player.queue?.entries.length === 1);
  }

  function countLoop(player, item) {
    if (!Number.isInteger(item?.fantasticIndex)) return;
    player.fantasticLoops = (player.fantasticLoops || 0) + 1;
    player.fantasticLoopOccurrence = item.fantasticIndex;
  }

  player.prepareNext = function () {
    const q = this.queue, item = this.current, mse = this.mse;
    const entry = q?.entries[q.pos];
    if (!repeatCurrent(this) || !entry || !item || entry.track !== item.track || this.pendingKey ||
        !mse?.sb || !mse.playlist || typeof mse.setNext !== 'function' ||
        (item.mode !== 'mse' && item.mode !== 'flac')) return prepareNext.call(this);
    if (this.fantasticRepeatFor === item && this.nextEntry === entry) return;
    this.fantasticRepeatFor = item;
    this.mixer.cancel();
    const serial = ++this.nextSerial;
    clearTimeout(this.nextRetry);
    this.nextEntry = entry;
    mse.expectNext = true;
    const fallback = () => {
      if (this.mse !== mse || serial !== this.nextSerial) return;
      this.nextEntry = null;
      mse.expectNext = false;
      mse.pump(mse.generation);
    };
    // Append this exact prepared rendition at the seam on the existing SourceBuffer.
    // Reuse its prepared playlist without resolving the song again.
    reuse = {url: item.m3u8Url, playlist: mse.playlist};
    try { mse.setNext(item).then(ok => { if (!ok) fallback(); }, fallback); }
    finally { reuse = null; }
  };

  function withOccurrence(item, entry) {
    if (entry?.track !== item.track || !Number.isInteger(entry?.fantasticIndex) ||
        item.fantasticIndex === entry.fantasticIndex) return item;
    return {...item, fantasticIndex: entry.fantasticIndex};
  }

  // Upstream advances the queue pointer before an ordinary resolve. Keep the ID
  // on the actual playing item, including the two paths that bypass start().
  const advance = player.advance;
  if (typeof advance === 'function') {
    player.advance = function (item) {
      // This is the exact item passed to setNext by the repeat adapter.
      if (item === this.current && item === this.fantasticRepeatFor && !this.audio.seeking && !this.audio.paused) {
        countLoop(this, item);
      }
      return advance.call(this, withOccurrence(item, this.nextEntry));
    };
  }
  const adoptVoice = player.adoptVoice;
  if (typeof adoptVoice === 'function') {
    player.adoptVoice = function (entry, item, mode, voice) {
      return adoptVoice.call(this, entry, withOccurrence(item, entry), mode, voice);
    };
  }

  const next = player.next;
  if (typeof next === 'function') {
    player.next = function (auto) {
      // Upstream passes true from ended() for both the audio and PCM engines.
      if (auto === true && repeatCurrent(this)) countLoop(this, this.current);
      return next.call(this, auto);
    };
  }

  // Upstream treats identical IDs as a toggle. Distinct occurrences in a queue must play again.
  const start = player.start;
  player.start = function (item, options) {
    const entry = this.queue?.entries[this.queue.pos];
    item = withOccurrence(item, entry);
    if (this.current?.track === item.track && Number.isInteger(item.fantasticIndex) &&
        this.current.fantasticIndex !== item.fantasticIndex && !options?.force) {
      options = {...options, force: true, resumeAt: 0};
    }
    const at = this.fantasticStart;
    if (Number.isFinite(at)) {
      this.fantasticStart = undefined;
      options = {...options, resumeAt: at};
    }
    return start.call(this, item, options);
  };
})();
