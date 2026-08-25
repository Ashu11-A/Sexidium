# Base prompt: turning a YouTube "Minecraft, but…" video into a challenge

Most of Sexidium's challenges come from a YouTuber format ("Minecraft, But Every Block I Place Copies
Everywhere", "Minecraft But Mobs Multiply"…). This file is the workflow for going from *a handful of
video links* to *a shipped, tested challenge*. It sits in front of
[Prompt.challenge.md](add-a-challenge.md) — do the research here first, then implement there.

## Ground rules

- **You are extracting MECHANICS, not content.** The deliverable from the research phase is a short rule
  spec in your own words: what triggers, what happens, what the limits are. Never paste transcript text,
  narration or descriptions into code, comments, docs or commit messages — the *rules of a game mode* are
  what you need, and they are not the video's expression.
- **Multiple videos, one mode.** Creators implement the same idea with different mods, so the videos will
  disagree on details. Treat the **majority behaviour as canonical**, note the variants, and make the
  disagreements **config keys** rather than picking one arbitrarily.
- **Never assume "the whole world" is literal.** Video mods run on a single-player world with a tiny
  loaded area. On a server the same rule has to be bounded (radius + per-tick budget + catch-up), or it
  is a crash, not a feature. Designing that bound *is* the engineering work.

## 1. Harvest — `yt-dlp`

Titles and descriptions alone usually identify the mode; the transcript settles the details.

```bash
cd <scratchpad>
ID=3DRB7bElWxo

# Title / channel / duration — cheapest identification step, do this for every link first.
yt-dlp --skip-download --print "%(title)s ||| %(channel)s ||| %(duration)s" "https://www.youtube.com/watch?v=$ID"

# Description — often names the ORIGINAL mod, which is the best possible source.
yt-dlp --skip-download --print "%(description)s" "https://www.youtube.com/watch?v=$ID"

# Subtitles in the video's own language (auto-generated is fine), plus any translation you read.
yt-dlp --skip-download --write-auto-subs --write-subs --sub-langs "en.*,pt.*" --sub-format vtt \
       -o "$ID.%(ext)s" "https://www.youtube.com/watch?v=$ID"
```

- **Cookies**: add `--cookies-from-browser chrome` when a video needs a signed-in session. It is fine if
  it warns `cannot decrypt v11 cookies` — public metadata and subtitles still download, so only retry
  properly if a fetch actually *fails*.
- A `No supported JavaScript runtime` warning is likewise non-fatal for metadata/subtitles.
- Strip VTT to readable text before searching, and de-duplicate — auto-captions repeat every line:
  ```bash
  sed -e 's/<[^>]*>//g' -e '/-->/d' -e '/^WEBVTT/d' -e '/^Kind:/d' -e '/^Language:/d' -e '/^$/d' \
      "$ID.en.vtt" | awk '!seen[$0]++' > "$ID.txt"
  ```
- Search with a small Python script over the whole corpus rather than shell `grep` — the transcripts are
  one long line after joining, and regex with context windows is what actually finds the rule statements.

## 2. Mine — what to actually look for

Creators state the rule in the first 60 seconds and then discover its consequences for 30 minutes. Both
halves matter: the opening gives you the rule, the rest gives you the **edge cases and the fun**.

Search the corpus for, at minimum:

| Question | Search for |
|---|---|
| The core rule | `every`, `copies`, `everywhere`, `each time`, the title's own words |
| Is the inverse also true? | `break`, `mine`, `destroy`, `remove` — does undoing copy too? |
| What is exempt? | `chest`, `inventory`, `items`, `doesn't copy`, `only the block` |
| Failure/exploit modes | `overwrite`, `ruined`, `broke the`, `impossible`, `can't beat` |
| Server cost | `lag`, `crash`, `fps`, `too many` |
| Scope | `chunk`, `radius`, `loaded`, `render distance`, `whole world` |

Write the findings down as a rule spec before you touch code. For Omni Chunk that was:

> Placing a block puts that block at the same position inside every chunk. Breaking removes it from every
> chunk too (one video's variant keeps breaks local — make it a config key). Containers copy as blocks but
> their contents do not. Overwriting something important (spawner, portal frame) is a real hazard and part
> of the tension. Destroying a lot in one place destroys it everywhere — the main lag risk.

## 3. Design the server-safe bound

A single-player mod may brute-force the world; a server may not. Every "affects everything" mode needs:

1. **A spatial bound** — prefer the player's own **render distance** (`PlayerAdapter.viewDistanceChunks()`)
   over a fixed number: everything they can see stays consistent and nothing is spent on chunks nobody is
   looking at. Walk it **outwards from the action** so the effect
   visibly ripples instead of appearing in a random order (`ChunkStamp.offsets`).
2. **A per-tick budget** — `blocks-per-tick`, drained from a queue by a `runTimer(…, 1L, 1L)`
   (`BreakOneBreakAllChallenge` and `OmniChunkChallenge` are the two models).
3. **Order, when the mechanic depends on it.** If the mode re-creates *player actions* rather than just
   block states, store a **commit log**, not a snapshot: a multi-block structure only works if its blocks
   arrive in the original sequence (`ChunkLedger` — see
   [experiences.md](../gameplay/experiences.md#omni-chunks-commit-engine)). Apply commits with physics on
   (`WorldAdapter.setBlockNatural`) so vanilla, not your code, decides what a completed structure does —
   that is how golems, fluids and redstone come out right without hardcoding a single pattern.
4. **A catch-up story** for anything outside the bound. Omni Chunk keeps a **bounded, persisted history**
   of edits and replays it into a chunk the first time a player walks in, which is what preserves the
   illusion without touching the whole world. Prefer this over forcing chunk loads —
   `WorldAdapter.isChunkLoaded` exists precisely so bulk edits skip non-resident chunks.
5. **Structural protection** — a small `protected-blocks` list (bedrock, barrier, end portal). Everything
   else stays destructible: per the fun-first policy, *burying something you needed is the mode working*.

## 4. Implement

Follow [Prompt.challenge.md](add-a-challenge.md) exactly. Two extra rules that come from this format:

- **Put the rule in a pure class.** The geometry (`ChunkStamp`), the history and its persistence format
  (`ChunkLedger`) go in host-free classes unit-tested without a world; the `*Challenge` keeps only the
  lifecycle, the world calls and the HUD. This is what makes the mechanic reviewable against the spec.
- **Compose, do not bulldoze.** Route replicated edits through `blocks().allowsPlace/allowsBreak` so the
  other challenges' vetoes still apply, and honour cancelled events.

## 5. Record the provenance

In the challenge's class Javadoc, name the mode as players know it (e.g. "the mode YouTubers call
OmniChunk") and state the rule. In [experiences.md](../gameplay/experiences.md), the catalog row says what it does and
what the bounds are. **Do not** paste video links, titles or transcript text into source files — the code
should read as a description of the mechanic, not of a video.

## Checklist

- [ ] Every link's title + description fetched; the original mod identified if the description names it
- [ ] Transcripts stripped, de-duplicated and searched for rule / inverse / exemptions / hazards / lag
- [ ] Rule spec written in your own words, with variants noted as config keys
- [ ] Spatial bound + per-tick budget + catch-up designed before coding
- [ ] Pure rule class + unit test; challenge class holds only lifecycle
- [ ] [Prompt.challenge.md](add-a-challenge.md) checklist completed (catalog, icons, config, docs, tests)
- [ ] No transcript text or video links in source or docs

---
*Keeping this current: tracks the `yt-dlp` research workflow and the bounding patterns in
`OmniChunkChallenge`/`ChunkStamp` and `BreakOneBreakAllChallenge`. Update it when the harvest commands or
the server-safe bounding patterns change.*
