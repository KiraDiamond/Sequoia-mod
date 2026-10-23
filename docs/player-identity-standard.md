# Player identity contract

Status: implemented for mod identity handling in 1.10.14.
Backend protocol/delivery changes remain a separate track.

## Resolve once, then act

A parser recognizes the event and supplies each person's exact range and role to
`PacketNameResolver`. Match against its normalized text, or use `resolveRaw` with
the original component and rendering offsets. Never rebuild an identity-bearing
component from flattened inherited styles.

A resolution carries state (`RESOLVED`, `UNRESOLVED`, `AMBIGUOUS`), display text,
account, established UUID when available, evidence/source, role, range and session.
Uncertain results carry no actionable account. Relay, avatar, colour, mentions and
persistence use that account; eligibility does not select another identity.

## Evidence policies

- `CHAT`: a matching explicit hover or recognized inline account reveal wins over
  insertion hints, which can contain the nickname. Conflicting explicit reveals
  are ambiguous. Otherwise, name-owned insertion identifies the account (including
  nonitalic nickname packets). A self-insertion on an italic nickname is a display
  hint. A plain nonitalic account identifies itself before any old cache entry.
- `METADATA`: sensitive guild-rank assignments require metadata agreement; syntax,
  inline reveals and the cache cannot establish the actor or target.
- `ALIAS`: no plain-text account inference. `TRUNCATED` additionally permits unique
  prefix matches, only for the known truncated party sidebar surface.

A slash format is usable only when exactly one side is a valid account candidate.
A spaced/punctuated nickname is never sanitized into an account. Every person in a
multi-person event gets their own range, even when their displayed names match.

Metadata retains its owning component subtree. Name-container inheritance is
valid; a broad root insertion or subjectless hover cannot claim a child name.
Subject-bearing hovers must name that displayed person. The recognized `<n>`
prestige prefix may share the name's metadata container. Body mentions, tooltips
for another person, item previews and `Bank (Everyone)` labels are ineligible.
Actual profile/UUID evidence is accepted only in the relevant player context or
recognized raid-menu player slot, never from arbitrary decorative heads.

## Session cache

Only explicit packet-ingestion observations teach aliases. Resolution, rendering,
debugging and cache reads are pure. Entries retain their original resolution and
observation time, expire after ten minutes and are bounded to 2,048 aliases.

This cache contains game-player name evidence, shared across game surfaces;
Discord aliases and party membership/order never teach it. Party changes do not
invalidate account identity. Game join/disconnect, leaving the supported server
scope and account changes clear it and advance a generation. Old indexes/results
cannot read or repopulate a newer generation. Backend reconnects do not reset it.

Exact matching is the default. Truncated reads never create shorter aliases or
renew evidence. Stronger explicit reveals may replace insertion-only evidence;
equal-authority conflicts are quarantined until expiry/reset, without extending
the original expiry. Weaker later hints cannot overwrite a stronger reveal.

## Consumers and failure behaviour

Chat, private-message tags, guild events/rewards/bank, raids, party events/sidebar
and chat filters use the shared resolver. Unresolved messages stay undecorated;
account-dependent relays/events skip attribution instead of guessing. Raw style
helpers are restricted to rendering extent and diagnostic fingerprints.

A supplied Discord ID exclusively selects its rank, palette and linked badge;
unknown IDs remain undecorated. Legacy name aliases must be unique.
Minecraft role colours use verified account links. Discord nickname-derived game
aliases are absent. Established UUIDs cannot fall back to a different linked
account; a verified name fallback supports roster records missing their UUID.
Party health uses exact canonical profile-name-to-UUID matches and rejects
conflicts, without nickname/prefix fallback after resolution.

Regression coverage includes the cinfrascitizen bank poison, GaztheCat/RaidKeeper,
Reyzhia/nunot colouring, inherited metadata, identical actor/target displays,
explicit-name formats, cache conflicts/expiry/read purity, session changes and
account-to-UUID health/rank attachment. A live Wynncraft smoke test remains useful.

## Separate backend track

Observer authentication does not authenticate the reported speaker. Backend
sender validation, wire evidence/versioning, report correlation, delivery
acknowledgements and retries remain independently required. This local refactor
does not claim those delivery changes or a verified rollout.
