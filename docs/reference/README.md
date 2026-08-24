# Reference

Two logs. Neither describes how the system works — the rest of these docs do that. These record
**decisions already made** and **defects already found**, so that neither gets rediscovered from
scratch.

| Document | What it records |
|----------|-----------------|
| [tech-decisions.md](tech-decisions.md) | Every UI/GUI technique and Modrinth library/mechanic evaluated against the dual-adapter, no-fat-jar, cross-play rules — each marked implemented / future / rejected, with the reason. Section A is GUI techniques, Section B is libraries and mechanics. |
| [known-issues.md](known-issues.md) | Severity-tagged findings — open bugs, parity gaps, design notes — with line refs, plus what was fixed since the last review and what still needs re-verifying outside core. |

## Why they live together

Both are **re-verified logs, not narrative pages**: their value is that they were checked against the
code at a known point in time, and they are worth nothing once nobody re-checks them. They share the
same maintenance obligation, so they share a folder.

They differ in how they age:

- **`tech-decisions.md` only grows.** A rejected technique stays rejected in writing even after
  everyone has forgotten it was considered — that is the whole point. Deleting an entry invites
  someone to propose it again next quarter. Entries get *restated* (rejected → adopted) but not
  removed.
- **`known-issues.md` is re-verified and pruned.** Each review pass re-checks every open finding
  against current code: fixed findings are dropped (summarised in the *Recently fixed* section, then
  eventually gone), surviving ones get fresh line refs. Never append to it blindly — an issue log that
  is only ever added to stops being trusted, and an untrusted issue log is the same as no issue log.

## Using them

Before proposing a UI approach or a new dependency, read `tech-decisions.md` — it has probably already
been ruled on. Before debugging anything odd, read `known-issues.md` — it may already be logged with
line refs.
