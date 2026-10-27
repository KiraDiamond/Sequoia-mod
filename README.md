# Sequoia

[![Release](https://img.shields.io/github/v/release/SequoiaWynncraft/Sequoia-mod?display_name=tag&style=flat-square)](https://github.com/SequoiaWynncraft/Sequoia-mod/releases)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-3C8527?style=flat-square)
![Fabric](https://img.shields.io/badge/Loader-Fabric-DBD0B4?style=flat-square)
![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square)
[![License: MIT](https://img.shields.io/badge/License-MIT-2ea44f?style=flat-square)](LICENSE.txt)

**[Sequoia](https://modrinth.com/project/sequoia)** is a client-side Fabric mod for **[Sequoia](https://discord.gg/seq)** guild members on Wynncraft.

It provides the in-game client for Sequoia's guild systems: backend authentication, Discord bridge integration, party finder tools, raid tracking, and a small set of guild-specific utilities. The goal is straightforward: put the guild workflows members actually use into the game client instead of splitting them across chat, Discord, and manual commands.

This is not a general-purpose Wynncraft mod. Most online features require a linked Sequoia Discord/Wynn account, and several of them only make sense if you are already part of the guild's Discord and in-game processes.

If you are a Sequoia or allied guild member, the expected setup is simple: link through Discord, install the mod, connect to the backend, and use the guild integrations from there.

## Features

- Automatic backend authentication
- Automatic connection to Sequoia services
- In-game Discord chat bridge
- Hover previews for image and GIF links in chat, decoded in a sandboxed helper process
- Wynntils item links rendered in Discord with item-encoding-v3 stats, major IDs, crafted details, and mounts
- Clickable world names in chat, so a called-out world is one click away
- Guild members panel: who is online, on which world, which raid builds they own, and who is mid-raid
- Friend list with a one-click "raid ?" whisper, and saved premade parties that show who can come
- Sequoia ranks on member nametags, replacing the Wynncraft rank badge above their head and coloring the name to match (off by default)
- Guild invite and removal audit relay for staff utilities
- Party finder commands and UI
- Seq-only war planner with timed availability, shared five-player territory queues, exclusive 1–5 player parties, a shared Lead + three Eco board, and collaborative territory zones
- Raid tracking and announcements
- Per-player raid gambit counts parsed natively from the raid-start roster
- Sequoia achievements: a badge gallery for raids, wars, professions, roles and raiding events
- A playful Princess-mode guild-raid count, compact leaderboard, and numbered raid celebration
- Interactive world map with gathering nodes analysis and active world events
- World-event reward chest collection for per-event observed drop rates ([details](docs/world-event-loot.md))
- Guild-specific settings and status screens

## Requirements

- Minecraft `1.21.11`
- Fabric Loader `0.18.4` or newer
- Fabric API `0.141.2+1.21.11`
- Java `21`
- Wynntils (optional; version 4.2.9 or newer enables item-encoding-v3 Discord previews and also adds legacy
  completed-war and queue lifecycle reporting, while live war telemetry and active-class detection use bounded
  vanilla fallbacks)

## First-time setup

1. **Have Party Finder access** - link an account that is in Sequoia/an allied guild or can view the Party Finder
   Discord channel (including Veteran, Honoured Veteran, and Community members).
2. **Install the mod** using the steps below.
3. **Connect** - the mod auto-connects on startup if enabled, or by using a button in the Connection section.
4. **Link if prompted** - if the backend reports no linked account, run `/link` in Discord and reconnect.
5. **Check status** - run `/seq status` to make sure you're connected.
6. **Configure** - press `O` and open settings to toggle Discord chat, raid announcements, and related behavior.

Linked players need Sequoia/allied guild membership or access to the Party Finder Discord channel to view, join,
create, or manage Party Finder listings. When a non-guild session connects, the mod shows one notice and disables
Sequoia-only integrations for that session; later membership rejections stay silent.

## Common commands

- `/seq`: open the main menu with Partyfinder, Settings, Connection, Ingredients, and Map
- `/seq p`: open the Sequoia party finder UI
- `/seq achievement`: open your raid, war, profession, role and badge achievements
- `/seq map`: open the Sequoia world map
- `/seq members`: open the guild members panel
- `/seq war`: open the Seq-only war planner after the backend authorizes the current member
- `/seq war available <minutes>`: advertise war availability for 1–1440 minutes
- `/seq war unavailable`: clear your war availability
- `/seq connect`: connect to the backend
- `/seq status`: show connection state
- `/seq logout`: clear the current backend session
- `/seq request aspects`: request aspects from the guild reward queue
- `/seq request tome <reason>`: request a tome from the guild reward queue
- `/allyraids [minutes]`: show recent shared raids with current allies (Sequoia members only)
- `/seq ignore <IGN>`: hide incoming Discord bridge messages from a Minecraft username
- `/seq unignore <IGN>`: show incoming Discord bridge messages from that username again
- `/seqimages preview <url>`: show a supported image link as a previewable chat line
- `/seqimages clear`: drop cached previews and cancel any still loading

<details>
<summary>Full command list</summary>

- `/seq`
- `/seq connect`
- `/seq disconnect`
- `/seq connected`
- `/seq status`
- `/seq logout`
- `/seq request aspects`
- `/seq request tome <reason>`
- `/allyraids [minutes]`
- `/seq allyraids [minutes]`
- `/seq ignore <IGN>`
- `/seq unignore <IGN>`
- `/seq members`
- `/seq member`
- `/seq achievement`
- `/seq map`
- `/seq map params`
- `/seq map eps <blocks>`
- `/seq map minSamples <count>`
- `/seq map reset`
- `/seq map debug`
- `/seq war`
- `/seq war available <minutes>`
- `/seq war unavailable`
- `/seq party`
- `/seq p`
- `/seq party list`
- `/seq party status`
- `/seq party create <activities>`
- `/seq party update <activities>`
- `/seq party join <listingId>`
- `/seq party join <listingId> token <inviteToken>`
- `/seq party join <listingId> <role>`
- `/seq party join <listingId> <role> token <inviteToken>`
- `/seq party deny <listingId>`
- `/seq party leave`
- `/seq party invite <username>`
- `/seq party reserve <count>`
- `/seq party open`
- `/seq party close`
- `/seq party disband`
- `/seq party role <role>`
- `/seq party kick <username>`
- `/seq party promote <username>`
- `/seq party scan`
- `/seq party game create`
- `/seq party game invite <username>`
- `/seq party game invite-all`

</details>

The navigation sidebars also include Achievements and, for authorized members, War Planner. The main menu and sidebars start with Partyfinder and Settings, followed by all remaining entries in alphabetical order.

## Party finder

Party Finder shows known class icons beside members in expanded cards and as an icon row in collapsed cards.
Cached cards pick up newly detected local classes and class switches; remote classes use listing data.
Wynntils class detection continues while Party Finder is open and reads canonical class identifiers,
so combined class/reskin display labels do not prevent icons or class updates.

`/seq party scan` imports the leader's current Wynn party into the active Sequoia listing. Scanned usernames that
match verified linked players with Party Finder access become regular listing members, which enables role, kick, and
leadership actions. Unlinked, ineligible, conflicting, or recently kicked players remain visible as observed
username-only occupancy without receiving Sequoia member privileges.

## War planner

The planner uses backend schema v3: party membership is separate from the
shared Lead/Eco support slots, so support players may also join a party. The
zone map provides palette colors, multi-party assignment, territory routes,
responsive full-map zone previews, and production-based resource coloring. Stored
resources do not affect color. Ordinary 9k emerald income is treated as the
baseline; territories with 18k base emerald production are highlighted as
emerald generators.

The War Planner sidebar entry and `/seq war` command only appear after a compact protected backend access check confirms
that the current account is a Sequoia member. The full planner snapshot is fetched only when the player opens or
explicitly refreshes the screen. Members can advertise timed availability and see their own team immediately;
authorized managers can atomically create or edit parties of one to five people, while one shared Lead and three Eco
slots remain independent of party membership. Managers choose `HQ Team`, `VLow Munch`, or `FFA` from the team editor;
the backend keeps HQ unique and assigns the numeric VLow/FFA suffixes. The Teams view adapts from full-width cards
to a one-column rail and then a balanced two-column grid, while keeping shared support, presence, composition roles,
actions, dragging, and scrolling on the same visible layout. The Zones view assigns named, colored groups of
territories to teams and previews each zone against the complete territory map.
Composition capabilities, eligibility, team exclusivity, versions, and all mutations remain server-authoritative.


## World map

Run `/seq map` to open the world map. Use the dropdown near the top-left of the map view to select Gathering, Events, or Ingredients. The sidebar title follows the selected mode.

The Close Map button at the top-right of the map view returns to the `/seq` menu, including when the map was opened directly with `/seq map`.
Navigation remains pinned in the left sidebar; the map mode dropdown stays over the map view, clear of the right sidebar. Its map, analysis, filter, display, and tracking groups can be folded independently, while selection details and map insights are available from the collapsible right sidebar.

Gathering analysis supports three scopes: all gathering nodes, nodes inside any guild territory, or nodes inside the selected territory. Resource, profession, cluster, and score controls continue to refine the active scope. Choose **Static file** (the default) or **Wynn API** using **Node source** in the Gathering sidebar's **Map & Territory** panel, or **Gathering node source** under **World Map** in Settings. The choice persists across restarts. Static mode loads the bundled nodes without requesting node data from the API. API mode fetches from the [Wynncraft API](https://docs.wynncraft.com/modules/map/list-gathering-nodes) in the background, refreshes hourly, and retries failed requests after one minute while Gathering is open. Each source keeps its own data and status; API failures retain the last successful API data. Loading and failure status appear on an empty map, and the selected source's status is shown in insights.

The Events view shows runs currently visible through the Wynncraft API. Choose All or Tracked to filter the markers, click a marker for event details, and use Track Event or the searchable tracking dropdown to manage persistent tracking. The management list can be limited to Tracked Only for quick removal. Tracked-event detection messages can be enabled in the World Events settings category.

## Guild members

Run `/seq members`, press `O` and choose **Player List**, or pick **Player List** in any
navigation sidebar, to see every guild member Wynncraft reports as online.

The panel uses the same frame as Party Finder: the shared sidebar on the left, the title,
search and actions in the header, then the list below.

### Your raid profile

The first time you open the panel it asks you to fill in a raid profile: which of
the guild's meta builds you own, whether you can bring auras, your region, and a
one-line status. It takes about a minute and replaces "who has an ascendancy for
TNA" in guild chat.

The list of meta builds comes from the backend, not from the mod, so when the
guild's meta changes everyone sees it on their next refresh instead of their next
update. Saving sends your profile to the backend; the setup screen stays open and
shows the reason if that fails, rather than telling you your builds are shared
when they are not.

You tick builds, not raids: the same build serves several raids, so raid coverage
is worked out from what you ticked. **Not now** skips setup and is remembered;
**My profile** in the header reopens it whenever you finish a new set.

### All

The **All** tab lists everyone online, sorted by name until you sort it otherwise, with their world in its own
column, so a name stays where you expect it instead of jumping around every time somebody
switches server. Your own world is highlighted. A member whose world Wynncraft will not
report, which happens when they have turned off their online status, shows a `?` there.

Each row leads with the player's head so you can spot who it is at a glance; whether they
are free is spelled out in the status column rather than hinted at on the head.

The rest of the row is how long they have been online and the builds they said they own.
Guild raid counts and wars are on the member's card rather than in the list, where they
made every row a wall of numbers.

Click a column heading to order the list by it: **MEMBER**, **WORLD** or **ONLINE**. The
heading in force carries an arrow, and clicking it again turns the order round. Each column
starts the way it is usually read, so the longest online comes first and names start at A.
Members Wynncraft says nothing about stay at the bottom either way round, rather than
floating to the top as a row of question marks.

Auras are shown separately from builds, as an `AURAS` tag beside the name, because being
able to bring auras is not a build.

Filter with the raid dropdown, **Auras**, **Free**, and the name
search. With a raid selected, the builds column narrows to the builds that are meta for
that raid, and a member who has not shared a profile is matched on their clear count
instead, which is weaker evidence but needs nobody to opt in.

Click any row for the full card. Everything Wynncraft publishes about them is there, and
none of it costs a request of its own since it rides along in the roster the panel already
fetches: rank, when they joined the guild, world, how long they have been online, playtime,
total level, wars, guild XP contributed and where that places them, and how their raids have
gone (damage, healing, deaths, gambits). Then what they told us: region, auras, declared
builds and guild raids per raid. A **private note** you can keep on that member sits at the
bottom and never leaves your client. The same card has **Add friend**, except on your own.

The row's one action, **Invite**, invites them to your Wynncraft party, creating the party
first when you are not in one yet. It is greyed out on your own row. With a raid
filter active, the confirmation names the build they bring to it.

Inviting also opens a party finder listing when you are not already in one, so the group
shows up for the rest of the guild. With a raid selected in the dropdown the listing is
for that raid, such as TNA; with none, or from Friends and Premades, it covers every raid.
It opens in the region of the world you are on (EU3 gives EU), or your profile's region
when you are not on a regional world, and NA when neither says.
The player you invited joins the listing once they accept in game, through the Wynn party
sync (the `sync_with_wynn_party` setting in the Party Finder category, on by default). If you already
sit in a listing, invites leave it as it is.

The **STATUS** column, between online time and builds, holds a badge: a green **Free**, a
red **Busy** with its countdown, or, for a member sitting in a party finder listing, a chip
such as `PF TNA 2/4`, which takes precedence over busy. That chip is orange while the
listing has room and red like Busy once it is full. The listing you are in yourself
wears the main accent instead, on every member in it, and reads "Your party" on hover.
Badges carry a border the way Party Finder's Open and Full badges do, and buttons do
not, so the two cannot be confused.

An orange chip is also a button when joining could work: the listing is open, it is
not your own row, and you are not already in a listing. Hovering it then reads
`Join 2/4`, and a click joins as DPS, the party finder's default. An invite-only
listing with room stays orange but does nothing when clicked.

### Friends

The **Friends** tab is the people you like raiding with. Add someone from their profile in
the All tab; they stay on the list whether or not they are online.

- **Raid ?** sends them `/msg <name> raid ?`.
- **Invite** pulls them into your party, creating it if you are not in one.
- **Remove** takes them off the list.

Every friend shows when they last logged in, such as `NA6, logged in 2h ago` for someone
online or `offline, logged in 3d ago` for someone who is not. That is Wynncraft's `lastJoin`,
the time they connected, not the time they left, which is why it does not say "seen". A
member who hides their online status in Wynncraft's privacy settings has no login time.

Online friends who are busy carry a red `BUSY` tag beside their name. Offline friends stay
listed with a faded head, and their two action buttons
are disabled.

### Premade parties

The **Premades** tab holds groups you run with often, so a regular team is one click rather
than four invites typed out every time.

**New party** opens the editor with you already in it: give it a name, then add members by
username, or press **Add my party** to pull in whoever is in your Wynncraft party right
now. A party holds four, you included, which is a raid group.

Each row says who can come, such as `3/4 online, 1 busy`, in green when the whole group is
free. The invite button only invites the free members: it reads **Invite all** when that is
everyone and **Invite 2** when it is not, and the confirmation says who was left out. It
creates the party when you are not in one, and spaces the invites out so Wynncraft does not
drop the commands. **Edit** reopens the editor, where **Delete** also lives.

Click a row to see every seat: free and on which world, busy with its countdown, or offline
with their last login.

### Busy status

A member shows a **Busy** chip in the status column, with a countdown, for eight minutes after
finishing a raid. The
signal is the same raid completion report the mod relays to Discord: Wynncraft announces every
guild raid in guild chat, so one completion marks its whole party at once, with no server round
trip. Members running the mod are tagged `MOD`.

### Where the data comes from

| What | From |
| --- | --- |
| Who is online, their world, playtime, guild raid counts, last login | Wynncraft's public API |
| Who is in a party finder listing | The party finder's own listings |
| The meta builds and raids, and everyone's profile | The Sequoia backend, `GET /raid-profiles` |
| Your friends, premade parties and private notes | This client only, `config/sequoia/raid-profile.json` |

The last row never leaves your machine. A private note about somebody is not
something to upload.

The last backend response is cached to `config/sequoia/cache/raid-profiles.json`
so the panel is not blank while the backend is unreachable. A member who has not
shared a profile reads `no profile yet` in the builds column, so an empty cell never
passes for "owns nothing".

Raid profiles can be pointed at a different backend than the rest of the mod, to try
the feature on staging while sign-in, chat and the party finder stay on production:

```
./gradlew build -Praid_profiles_environment=staging
```

It follows `backend_environment` when not set, so an ordinary release build is
unaffected.

Each backend signs its tokens with its own secret, so staging refuses a production
token. When the two differ, the mod signs in to the raid-profiles backend a second
time and keeps that token in memory only; the main session is left alone. That
second sign-in needs your account linked on that backend. If it is not, the mod
posts a link in chat to its website sign-in
(`https://staging.seqwawa.com/auth/web/start?return_to=https://staging.seqwawa.com/`),
which links through Discord and Wynncraft the same way `/link` does. Live
`raid_profile_update` pushes are ignored in this mode, because the WebSocket still
belongs to the main backend.

[`docs/raid-profiles-protocol.md`](docs/raid-profiles-protocol.md) documents the
client's API contract. The service and its catalog are maintained in
[`sequoia-backend`](https://github.com/SequoiaWynncraft/sequoia-backend).

The roster refreshes at most once a minute, matching the two-minute cache
Wynncraft serves the guild endpoint with; **Refresh** in the header forces the
next allowed fetch of both the roster and the profiles.
## Achievements

Open **Achievements** with `/seq achievement` or from the navigation sidebar.

The screen groups achievements into a responsive badge gallery with one to three columns,
keeping the dark theme and tier-coloured badge rings. Raid, war, profession and role cards show
the account's current status and, where applicable, progress toward the backend's next milestone.
Hover a card for its details.

Raiding events are static awards: each event has one card showing its earned tier or not-earned
status, without a progress bar. Wartorn Palace keeps four tiers, Nexus of Light keeps three,
and The Canyon Colossus has its existing untiered badge. Bundled event and insignia artwork is
used when available; other cards retain the Sequoia tree icon.

Counts, tiers, milestone targets and event awards come from the Sequoia backend. Progress follows
the same Minecraft account across machines; linked alternate accounts retain separate totals.
The backend counts recorded raid announcements from 14 August 2026 at 18:12:02 UTC onward.
The mod polls periodically and refreshes shortly after a locally detected completion. If there
is no cached result and the backend is unavailable, the screen reports that state rather than
presenting missing data as zero completions.

Tier colours use the `achievement.bronze` through `achievement.mythril` theme keys. See
[`docs/theme-template.theme.yml`](docs/theme-template.theme.yml) for the complete palette schema.

## Chat image previews

Previews are **off by default**. Enable **Show image and GIF previews** under Discord chat to opt in.
Supported links in chat then become **[View Image]** or **[View GIF]**: hover to preview, click to enlarge,
shift-click to insert the URL. Turning the setting off disables the feature and all of its requests.
**Show media links**, just above the preview setting, controls Discord media visibility. Leave it on
with previews off to see plain URLs, or turn it off to hide media links and media-only messages while
keeping accompanying text and ordinary links. Hidden media makes no preview requests.

Still PNG, JPEG and WebP images keep up to 1024 pixels per side for readable previews at larger GUI
scales. GIFs, MP4s and animated WebP retain the 256-pixel animation limit; all previews remain within
the existing total pixel, memory and sandbox budgets.

- **Sources:** HTTPS GIF, PNG, JPEG and WebP links from Discord, Tenor, Giphy, Imgur and Klipy, plus Klipy's MP4
  renditions of GIFs. FxTwitter GIFs are supported only as direct
  `https://gif.fxtwitter.com/tweet_video/<id>.gif` or `.webp` links without query parameters.
- **Privacy:** previews are fetched only from those hosts, which see your IP address as a browser would.
  Other hosts, redirects off the list, private addresses, bodies over 8 MiB and downloads over 20 seconds
  are refused.
- **Decoding:** media is never decoded in the game. A bundled helper runs a fixed FFmpeg build in WebAssembly
  under an OS sandbox (seccomp on Linux, Seatbelt on macOS, an AppContainer on Windows).

## Settings

The settings screen includes controls for:

- Auto connect to the Sequoia backend
- Discord chat display
- World name links in chat, and whether clicking one switches immediately or types `/switch` into the chat box
- Raid auto-announce
- Tracked world-event notifications
- Global Sequoia UI size
- UI theme selection
- Update checks on startup
- Draggable war queue HUD position through **HUD layout**, text size, maximum rows, and an only-my-queues filter shared with the war planner map
- Toggleable TNA beam countdown with a draggable position in **HUD layout**
- Optional queue-miss blame messages, disabled by default

War tracking, planner display, and queue controls share one **Wars** category. Settings that depend on another
control remain visible underneath it and are indented and disabled while their parent is off.
Major category headings and their fold indicators use the theme's light primary accent (`accent.primary_hover`).
Setting titles and enabled control labels use primary text; subcategory headings use secondary text. Categories and subcategories start folded. Click their
headers to expand or fold them. Search reveals matching controls without changing the saved
expansion choices for the current screen. Folding stops hidden input and color previews.

Sequoia includes Default and High Contrast themes. Open **Theme editor** from the Settings
screen to copy a theme, edit its full RGBA palette, preview changes live, and save a personal
theme without restarting. Personal themes are stored as `*.theme.yml` files under
`config/sequoia/themes`. Files added manually are discovered when the client starts.
The complete supported schema is available in [`docs/theme-template.theme.yml`](docs/theme-template.theme.yml).

Map cluster hulls explicitly override alpha to 35% (89/255), including selected clusters; markers use their normal theme opacity.
Map point markers use squares, while world-space radius indicators remain circular. Gathering, Events, Ingredients,
and the territory picker show your player head using the same cached 20-unit renderer as the war map.
A square fallback remains visible while a head image loads or if it is unavailable. Totem map markers use `shaman.png`,
and Close Map returns to the main menu. Cluster counts are centered by their
text bounds, with a 1-unit border at 35% opacity.
Other themed screens and controls preserve the alpha values from the active theme, including
fully transparent colors. Top-right header text uses the light primary accent (`accent.primary_hover`),
and dividers use the dark primary accent (`accent.primary_dark`). Party Finder places the role selector
next to New/Manage Party on the left and its title on the right; controls wrap when space is limited.
Sidebar headers center the Sequoia icon and title together. The `/seq` menu fits `seqmod_bg.png`
proportionally along its left side at 35% opacity.
Sidebar navigation shares Partyfinder's active, hover, and idle colors.
Search fields share its 140-unit width and shrink to fit when space is limited; drawing, text
clipping, and click targets use the same width. Map search dropdown results may be wider to fit names.
The War Planner's opacity control scales panel backgrounds and preserves their configured alpha at 100%.
Map imagery in the planner and territory picker stays at full opacity independently of that slider. The Theme editor's RGBA controls remain the way to change individual alphas.

When changing the UI, use `ThemeManager.color(token)` and the shared `SequoiaUiStyle` conventions.
Do not replace a configured alpha with a constant or force a theme color opaque. Use the appropriate
theme token for disabled, active, and hover states. Add alpha adjustments only when explicitly requested.


## Installation

1. Install Fabric for Minecraft `1.21.11`.
2. Put the Sequoia mod jar in your Minecraft `mods` folder.
3. Install Fabric API.
4. Optionally install [Wynntils](https://wynntils.com) 4.2.9 or newer for item-encoding-v3 Discord
   previews plus legacy completed-war and queue lifecycle reporting. Sequoia's core UI, live war telemetry, and
   active-class detection do not require it.
5. Start the game and press `O`, or run `/seq`.

## License

MIT: `LICENSE.txt`.

The chat image decoder bundles third-party components (FFmpeg, zlib, wasi-libc, Wasmtime and
other Rust crates, the Rust standard library). Their notices ship in the JAR under `licenses/`,
indexed by `licenses/NOTICES.txt`. Each release also publishes
`sequoia-images-native-source.zip`, the corresponding source of the bundled decoder.

### Player List party synchronization

Inviting through Player List enables and saves **Sync with Wynn party** when the
feature links a new or existing Sequoia listing. The leader requests a Wynn roster
scan if the party has not yet been observed. Accepted invites and later party
changes then use the existing automatic sync path.

Observed players with verified Sequoia account links become full listing members
when eligible. The backend can resolve a renamed guild member through the cached
Wynncraft UUID roster before checking their link. Unlinked or ineligible players
remain observed occupancy; a later sync can upgrade them once a verified link is
available. This does not create or verify account links on anyone's behalf.

## Developer contracts

Shared client/backend wire checks and fixture maintenance are documented in
[`docs/game-websocket-contract.md`](docs/game-websocket-contract.md).

Native image decoder builds and upstream verification are documented in
[`native/README.md`](native/README.md).
