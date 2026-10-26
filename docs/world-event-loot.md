# World event reward collection

The **World Events → Collect world event rewards** setting is enabled by default.
It shares personal end-chest observations through the existing authenticated Sequoia API.
Collection is limited to the main Wynncraft server. Backend access currently follows the
ordinary guild-member REST policy.

## What is captured

- An explicit active event name, a completion UUID and completion time, and the world when available.
- Only the first 27 chest slots, never the appended player inventory.
- Item slot, raw and normalized display name, quantity, normalized lore and category, including unknown items.
- Capture completeness and chest occupancy. Loot bonus and loot quality remain `null` (unknown).

Event-specific categories are `unidentified_item`, `event_item`, `rune`, `ingredient`, and `liquid_emerald`.
General categories are `emerald`, `emerald_block`, `unidentified_box`, and `potion`.
Unrecognized rewards are retained as `other`. Generic unidentified equipment boxes are
potion-backed items with a level-range lore line; they are not named unidentified equipment.
Named unidentified equipment also uses Wynncraft's unidentified name glyph; its raw name is
retained so this classification evidence survives normalization.
Ingredients use the legacy `Crafting Ingredient` line or the modern pair of a `Crafting Level`
line and `Can be used in recipes for` line. The modern graphical ingredient badge and level
digits may disappear during normalization; neither name color nor description keywords alone
are used to infer an ingredient.
Runes are recognized by the exact names Nii Rune, Uth Rune, Tol Rune, Az Rune and Ek Rune,
ignoring case; graphical resource badges are not required. Identified event rewards include
Third Clarion and Corrupted Cache. Additional named rewards are recognized by complete paired
tooltip lines: a rarity plus `Utility Trinket` with `Right-Click to use`, or a rarity plus `Item`
with `Right-Click to open`. A type label or descriptive mention alone does not qualify.
Known currency, ingredients, generic unidentified boxes and potions keep their classification.

## Capture rule, version 1

The tracker requires the native event-radius message and an explicit event identity:
Wynntils' active-event name, or a nearby visible entity name exactly matching the event catalog.
The map's location/schedule alone never supplies attribution. The tracker scans once per second,
including before entering a radius, and reads individual lines from multiline text displays.
A unique exact catalog name within 64 horizontal blocks can be staged for up to 15 seconds;
it must still be within 64 blocks when entering. Once explicitly identified, the name is kept
through that radius session even if the pre-start label disappears. A one-hour hard deadline
guards against missed exits; repeated radius-entry packets do not extend it. Conflicting names
invalidate the session instead of switching it. Exit, failure, completion, world/account changes
and disabled collection clear attribution. Unknown or ambiguous event identities are skipped.
Catalog requests share the existing two-minute map cache.

`WorldEventLoot` log lines record identity transitions, completion rejection reasons, catalog
and optional-adapter status, chest acceptance, queued samples, and backend upload outcomes.
These logs let a live attempt distinguish missing identity from chest detection or upload issues.

After `Event Completed`, only an exact `Event Rewards` screen with three rows is eligible,
within five minutes. This title and layout were checked against the Wynntils 4.2.9
container model. The snapshot uses the latest full-content and slot packets received in the
first **250 ms after opening**. Packets at or after the cutoff cannot alter it, even if the
next client tick finalizes it later. There is no extra quiet period or click-triggered capture.
The snapshot is queued automatically once; later updates and reopening cannot replace it.
The completion UUID is reused for every upload attempt.

Closing the chest or opening a replacement screen early stops updates to that observation,
while retaining its cached contents until the original deadline. Loot taken during the 250 ms
window may already be absent from the snapshot. `complete=true` means a full-content baseline
arrived within the observation window, including an empty baseline. Missing baselines remain
incomplete and are excluded by the backend. Removals/replacements and recent updates no longer
invalidate the capture. The game has no generation-finished marker, so rewards generated after
the cutoff can be missed. Live gameplay verification of packet timing and the native entity-label
fallback remains necessary; synthetic tests do not establish that.

Full chests are flagged as overflow-risk observations, not proven deletion. Observed rates
cannot recover rewards already deleted by the game.

## Delivery

Deploy backend support for `rune` and `event_item` before distributing this mod version.
An older backend rejects unknown categories and the mod discards permanent 4xx responses.
The updated backend can also recognize matching `other` observations from older mods.

The bounded in-memory queue retries the same payload every 30 seconds on transport/server,
authentication and rate-limit errors. Permanent 4xx errors discard the sample. The queue
is isolated to the active Minecraft account, and a token for a different account cannot upload
observations. Account changes, disabling collection and exiting Minecraft discard queued
observations. World changes clear event attribution, preserving already-frozen queued samples.
The queue holds at most 60 samples and discards observations older than 23 hours.

See the backend's matching `docs/world-event-loot.md` for the rate API and statistical definitions.
