# Earlier releases

For [1.4.2-beta3 (versionCode 54)](../README.md#whats-new-in-142-beta3), see the current beta notes. Changes on `main` since then come first.

## 2.0.0-beta7 (`v2.0` branch)

- Added public MCP access over official Tailscale Funnel (Headscale rejected upfront): dedicated loopback `PublicServer` (`127.0.0.1:18785`, constant-time `X-AVS-Public`, `/mcp` and OAuth 2.1 endpoints only), RFC 9728/8414/7591/8707 discovery and dynamic registration, PKCE S256 with 8-char pairing code and biometric/PIN strong confirmation (default `look`, `system` never granted, hold-to-confirm disabled), 1 h access tokens and 30 d rotating refresh tokens with reuse full-revocation, 4-point risk warning flow, ongoing Off notification, 24 h idle auto-close, auto-reset on app update, and prompt-injection notice in core `guide`.

## 2.0.0-beta5 (`v2.0` branch)

- Added `act --hold` and `note hold on|off` (Android 14+ post-reply app pause managed by the privileged Gate with automatic unhold before the next command, on timeout/takeover/release, or on app exit), short-range single-act rules `act --on "<cond> => <step>[ x1..x3]"` with shared persistence debounce and claimed BEFORE/AFTER windows, and first-line reply time hints (`X.Xs since last reply`) with a one-time `BEFORE` coaching note.

## 2.0.0-beta4 (`v2.0` branch)

- Added closed-loop gestures (`press … hold until`, `drag … hold until`, `drag … seek until`) using existing `Probe` with region-only crop OCR (`readCrop`) and paused background OCR while fingers are down; added `GestureRecord` (pickup, follow, after-release `stayed`/`snapped_back`/`elsewhere`/`unknown`), `SNAP_BACK` loud reason, and fixed 4-cell drag strips in full replies.

## 2.0.0-beta3 (`v2.0` branch)

- Added reply protocol v3 (`Loud`, short quiet replies when all actions meet expectations with escape hatches `act --full` / `note reply quiet|full` / auto-escalation, `PageBook` page cards, cropped change images at 960/360/640 px, event-based `[Meanwhile]` with toast and popup detection, 1200-token reply budget) and externalized session memory (turn counter, `task recap`, layered core + 8-topic `guide`).

## 2.0.0-beta2 (`v2.0` branch)

- Added Gate in the privileged service process: holds the public loopback port and forwards raw TCP bytes to the app's internal port; wakes a frozen or force-stopped app within 3 s or returns `503 NOT_READY`, and cancels held agent fingers when heartbeats go stale.

## 2.0.0-beta1 (`v2.0` branch)

- Added the evaluation harness (`:torture` 13 deterministic scenes and `tools/eval/` task runner) and reply cost metering (`meter` in JSON replies and step logs for characters, estimated tokens and image tokens), leaving text replies unchanged.

## On `main` since 1.4.2-beta3 (not released yet)

- **Fewer false "the screen did not change" from slow apps**: when the picture after an action's first wait still matches the one before, `act` looks up to 1.5 s longer (also for the last step under `--fast`). A late change is waited out and both moments are counted from the action: "the screen changed late, 2.0s after the action, and settled at 2.6s", or "… was still changing when time was up at 5.0s". A `--stop` condition met meanwhile stops the stretch at that step, without `NO_CHANGE`. "The screen did not change" (`NO_CHANGE`) now means no change was seen within that window, about 1.5 s later than before. If the screen changes only after the observation window closes, the step can still report `NO_CHANGE`; and the check compares a sampled picture, so it does not prove a tap missed.
- Step counts read "1 step", not "1 steps": Records, lessons, automations, `use` replies and "1 setup step left".

## What's new in 1.4.1

- **Schedules and watches per agent**: same names no longer overwrite each other; forgetting an agent clears its schedules and watches.
- **Steadier self-hosted Headscale**: the control server resolves even with a proxy such as FlClash on; public DNS is used only when every phone DNS is unreachable.
- **Shizuku**: diagnostics include Shizuku's version, permission and user state; the hint leads with the usual cause (Shizuku must be started again after a restart).
- Fixed Remote › Advanced not opening; schedules, watches and task ownership are more reliable; app data stays out of system backups.

## What's new in 1.4.0

- **A new app**: four tabs (Now, Connect, Records, Settings), each page with one main thing; explanations moved to second-level pages. Purple glass throughout.
- **Every agent on its own**: each connected agent gets its own token, records and saved steps; lessons are shared. One inbox on the home page collects what agents need from you, and notifications open the right page.
- **Teaching, rebuilt**: one lesson flow from the request to the saved segment, a record button that answers the agent's request, and a review pane beside the live screen. A repeated `teach` returns the saved lesson instead of asking again. Agents tidy the page back to its starting state before they ask.
- **Keyboard**: `key ctrl+a` / `shift+tab` / `alt+f4` combos; `type` at once or `type "…" slow` letter by letter. In full screen the keyboard button opens your own input method.
- **App sound**: Settings › App sound lists every silenced app and gives sound back; the `sound` command does the same for agents. Muting now sets both of Android's sound records, for phones that only check one of them.
- **`fix` repairs more**: silenced apps, a stopped foreground service, the built-in Tailscale; "fixed" is checked afterwards, not assumed.
- **Port taken**: when another app (or a clone-space copy) holds the port, the service takes the next free one and says who holds it; local agents find it by themselves and Tailscale forwards to it.
- **Smaller, faster reading**: PP-OCRv6 tiny detector + small recognizer (11.5 MB); `collect` skips search bars and keeps going to the real end; `repeat … until` judges the last look on what the screen shows now.

## What's new in 1.3.0

**One step language for scripts, saved segments and lessons**

- `*` in words is any characters: `tap *Bluetooth*` taps the item holding it, `wait text:Total*items`; `words@2` is the second place from the top, `@-1` the last. When the same words are in several places, the reply marks each with its `@N`.
- Flow: `try <step>` or `try { … }` (not found or not as expected: skip and go on); `if text:Close { tap Close } else { back }`; `repeat 3 { scroll down }`, `repeat { scroll down } until text:End`. Braces may span lines and nest. A mistake runs nothing and says how to write it.
- `note save <name> "steps"` stores or rewrites a segment without running it, taught ones too.
- **Human pacing**: `act --human`, or `note human on` to keep it for an app. A random pause before each action, taps slightly off-centre inside the element with uneven holds, varied swipe and drag durations and ends, typing 1–3 characters at a time. Waits and conditions are unchanged.

**You teach it, it asks you**

- **Teach it by doing it**: do it once on the background screen and it is saved as a segment agents can replay. Take over the screen, tap the record key in the bottom bar, work as usual and tap Done; the review page says where each step came from, lets you untick slips and name it. A tap on words becomes `tap <words>` (found again when the layout moves); only when no words name the place is it a point. Words that appear, or the app that comes up, after a step become its `until`, so the replay waits for the same result. Typed words become a blank `{1}` that agents fill with `use <name> <values>…`; passwords are never kept. Agents can ask for a lesson with `vscreen teach "what to show"`; "Show it" on the phone starts recording right away.
- **Show me**: `vscreen ask "Which one do you mean?"` puts the question on the phone; you tap the place on a picture of the background screen and the agent gets the point and the element there. Boxes, choices and text answers work too; with `--async` it waits until you answer.
- **Lessons are smoother**: "Show it" takes over and records at once, "Done" also hands the screen back. A tap on a search result after typing is kept as "the N-th item holding the typed value" (`tap *{1}*@2`), so replaying with another value taps by content, not by place. A saved lesson keeps one contact sheet of its key steps (red rings mark taps); agents get it with the teach reply and again with `note show <name>`. The app's Schedules & lessons page searches lessons, deletes several at once and shows blocks indented.
- **The floating window tells you when it has a question**: the folded tab turns into a violet question mark; expand it or go full screen and answer yes/no and choices right there.

**Seeing clearly, tapping right**

- **Checks judge only what came after the action**: `until text:…` counts words that appeared after the action, once the app reacted and the picture settled; when nothing changed, the reply says the app did not react.
- **Numbers belong to one page**: on a new page an old `#N` is `STALE_ELEMENT` and never lands on the same words there.
- **Areas that keep moving** (videos, live streams, carousels) are recognized: they no longer slow every step or list bullet comments as changes.
- **Seeing your own taps**: the [Meanwhile] sheet has a frame before each action with where it touched circled, and spare places show what actions led to. `vscreen frames` looks back at the recording: longer, denser or a given stretch.
- **One element per place**: OCR no longer repeats words the app's own elements carry; `collect` gathers by cards and keeps two columns apart, leaves tab bars out, and confirms the end with a second swipe elsewhere.
- **Word taps are faster**: `tap <words>` looks in the app's own elements first and needs no full-screen OCR when they settle it.
- **Time as it was spent**: every reply says how long the phone took and on what; the command and the liveness check go out together, and pictures are smaller.

**On call, or fully out of the way**

- **Schedules**: `vscreen schedule "weekdays 07:00"` books when an agent will use the phone; before each time the service is started and checked, and you are told in time if something blocks it. One-off bookings too (`today 10:30`). `ready` at the top of `status` says whether it can be used.
- **Always on / hidden / blocked**: the switch on keeps it running; off hides it (no notification, nothing in the background) while a local agent or a booking can still wake it, and it hides again afterwards; "Block agent access" is really off.
- **The background screen is muted**: its apps make no sound on the phone.
- **Main-screen notes only when we disturbed it**: you switching apps on the main screen is no longer reported.
- **Agents learn about app updates**: an agent's next use of vscreen shows what changed for it and asks it to refresh its note.

**Remote access that holds**

- **Built-in Tailscale**: one node at a time that lives and dies with the app's process, so a restart by the system no longer leaves the phone unreachable for minutes; the saved login is kept while the server cannot be reached ("dropped, reconnecting"), so you are not asked to log in again; "Connected" is the real link state, with the relay and how often it dropped.
- **A token is enough over the built-in Tailscale**: no signing, and MCP clients connect remotely as they are.
- **The CLI no longer hangs**: an unresponsive local app is woken and tried again; when the app restarts or stops answering for a minute during a command, the CLI stops and says why; a remote phone that does not answer is explained (hidden, blocked, offline, logged out). `VSCREEN_PROXY` sets a proxy.
- The log and `status` record why the previous app process ended, and when the app went to the background, was frozen or the node stalled.

**The app**

- The home page holds the switch, the screen, anything waiting for you and a set of tiles (Connect, Permissions, Screen & window, Schedules & lessons, Activity log, Setup), each with its one key fact; explanations sit behind "?". The app, the floating window and full screen share one violet glass look.
- **Check for updates**: "Check for updates" on the home list looks at the GitHub releases and shows what's new with a download link; the app also checks quietly at most once a day.
- The Shizuku grant button always answers: already granted, Shizuku not running, refused before or no dialog shown — each is said and leads you to the right place; the grant state is read again when you come back to the app.

Not verified on every phone: whether vendor background managers let exact alarms and the wake broadcast through for a frozen app; whether muting with `PLAY_AUDIO` works for every player on MagicOS.

## What's new in 1.2.0

This version rebuilds the command gate, the reply format, the security model and the guide for agents, and went through two real-phone regression rounds with [docs/TASKS.md](TASKS.md) (Honor Magic 7, MagicOS 11). The upgrade table from 1.1 is in section 9 of [API.md](../API.md).

- **Structured results**: every reply carries `ok`, `code` and `next` (look at the screen / fix the command / ask the user / retry later), plus each step's status, the screen's elements and the notes. A stretch that stops at a step is a failure; no reading text to find out. The new API is `/v2`; `/v1` is unchanged and stays for one more version.
- **One command table**: option checks, help, MCP tool schemas and the docs are generated from it. MCP has one tool per command besides `vscreen`.
- **More precise taps**: text that appears several times is listed with coordinates instead of tapping the first; a `#number` whose element moved away is not tapped at its old place; an action that changed nothing is flagged.
- **Shorter waits**: a page left with only a spinner or blinking cursor counts as settled. Perception is driven by new frames.
- **One input path**: taps, swipes and keys are injected by the display service; dumpsys parsing and shell `input` are gone.
- **A token per agent**: look-only / operate / everything, revoked separately, with separate sessions.
- **Signed requests**: from another device the token never travels, requests cannot be replayed or altered, and replies are verified. The CLI does it by itself.
- **The user confirms on the phone**: payment, irreversible steps, force-stop, taking an app off the main screen, and apps set to "ask every time". A flag added by the agent no longer counts. A trusted agent can be given 30 minutes without confirmations.
- **Activity log**: who ran which command, when, with what result. Typed text is recorded as a length only.
- **Harder to knock over**: connection cap, request size and time limits; a command stops when its caller disconnects; a resent request never runs twice; long work can run `--async` and be collected with `task`; commands queue.
- **Events are not lost**: kept until the next fetch, re-fetchable, stored on disk; watches resume after an app restart; the event broadcast goes only to an app you name.
- **A rewritten guide for agents**: built around one loop (look → act → check), with what to do next for each result code; `vscreen guide` is one page.

- **Hands-off by default**: Permissions in the app has two levels. The default, "hands-off", asks you on the phone only for the step that actually spends money (pay, place order, transfer, top up) and for apps you set to "ask every time"; "careful" also asks for irreversible buttons such as delete, steps toward a purchase, force-stop and taking an app off the main screen. The "clear" button next to an input box is no longer treated as irreversible.
- **A new page layout**: the home page goes Connect → Permissions → Settings → Activity log; permissions live in one place (level, app rules, tokens) and settings are grouped (floating window / background screen / reading and recording).
- **A smoother floating window**: its picture is scaled to the size shown at 20 frames a second (30 while you control the screen) instead of the agent's full-size frames; moving the window updates its position once per display frame, so it stays under the finger; after taking over, your finger reaches the screen as it moves, so lists follow it. A landscape screen opens almost as wide as the phone, and each orientation keeps its own size.
- **Look-only tokens can look**: their `act` (no steps or only waits) looks at the screen in use; `stop --force` and `handoff in` need the "everything" scope.
- **Numbers never point at the wrong element**: on one screen a number names one element; after a scroll the same element keeps its number and new ones get new numbers. A number whose element left the screen gives STALE_ELEMENT instead of tapping something else.
- **Floating window**: a landscape screen opens at about three quarters of the phone's width, and the whole window stays on screen when it appears or turns.
