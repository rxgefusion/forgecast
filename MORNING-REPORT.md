# Morning report — overnight run, 2 September 2026

**6 commits, 81 tests passing, working tree clean.** Nothing was force-pushed, no history
rewritten, no test weakened, nothing published, nothing touched outside the project folder.

Three things went wrong. They're in their own section below rather than buried.

---

## 1. Status pass — the plan against reality

I checked every claim in `forge-mod-plan.md` against the code. The board is largely accurate.
Corrections in **bold**.

| Plan item | Reality | Evidence |
|---|---|---|
| "Two things to decide: the name" | **DONE** — `ForgeCast`, id `forgecast` | `fabric.mod.json`, commit `4b7d1d0` |
| "Two things to decide: the licence" | **DONE** — LGPL-3.0-only, file present | `LICENSE`, `c7000ae` |
| Next action 1 — commit DevAuth work | DONE | `3f92795` |
| Next action 2 — switch to 26.1.2 | DONE | `gradle.properties`, `7e9a5e7` |
| Next action 3 — capture tooltips + widget | DONE | 10 tab fixtures + 1 GUI fixture |
| "Does time get finer near zero?" ❓ | **RESOLVED — yes.** `26s / 17s / 10s / 4s` captured | `dump-2026090 2-0218*` |
| "What does a finished slot look like?" ❓ | **RESOLVED** — `Ready!` (tab), `Completed!` (GUI) | `…020918-FINISHED-STATE` |
| "The minutes format" 🟢 | **PARTLY RESOLVED.** Never seen in the *tab list*; the *GUI* shows `8h 46m 2s`. Parser handles d/h/m/s generically either way | `GuiForgeParserTest` |
| "How a locked slot renders" | **STILL OPEN** — untestable on your account | — |
| Slice 1 — see the forge from anywhere | **DONE**, unverified visually | `ForgeHud`, `ForgeMemory` |
| Slice 2 — tell me when it's done | **NOT STARTED.** No sound/notify code exists | grep found nothing |
| Slices 3–5 | NOT STARTED (correct — they follow the merge layer) | — |
| Truncation cache | **PARTIAL** — in-memory only, resets on restart | `ForgeMemory`, `5160d67` |
| "GUI sets truth, widget detects divergence" | **NOT BUILT** — this is the merge layer you reserved | `GuiForgeParser` is referenced by **zero** other main files |
| Warning: 4-case table | **DONE**, with one bug found and fixed (below) | `ForgeAdvice` |
| Warning text `/widget > Forge Widget (on) > Other modes (on)` | **CHANGED** — now recommends *Wrapping*, because your experiment proved Wrapping fixes truncation | `ForgeAdvice.PATH_WRAPPING` |

### One line of the plan is now wrong

The plan's design decision **"Read the tab-list widget only — not the GUI tooltip"** was written
before the GUI was found to be second-accurate. The plan itself later contradicts this in
*"This rewrites the caching design"*. The later section is the correct one; the earlier
"widget only" heading should be struck. **Your call — I did not edit your plan file.**

---

## 2. Safety debt

### (a) Dev tools gated — commit `2600fdb`

Mechanism: **two independent conditions, both required.**

1. `FabricLoader.isDevelopmentEnvironment` — a jar in a normal mods folder always reports false.
   Not a setting anyone can flip.
2. An explicit opt-in, **off by default**, from config or `-Dforgecast.devtools=true`.

`/forgecast dump` and `/forgecast dumpgui` are **not registered at all** outside a development
build — they can't be tab-completed or stumbled into. Inside one they're registered but still
refuse unless opted in, so toggling works without a restart.

**The tick-capture — your prime suspect — now returns immediately unless both hold.** In the
default configuration its cost is a single boolean check per tick. See §4; I think you were right.

### (b) Fixtures anonymised — commit `f051319`

**47 usernames** (not 22 — multiple sessions), bank balance, gems, interest, SkyBlock level,
profile name and 4 server instance ids. All replaced via one consistent mapping across all ten
fixtures, longest-name-first so a short name can't corrupt a longer one.

**No test depended on a real username or balance** — all 64 tests passed unchanged afterwards.
So no test was wrong, which answers your question.

⚠️ **History is not rewritten.** Earlier commits still contain the original data, and the repo is
public. See §5.

---

## 3. The config menu — commits `7bbd7ab`, `4e94025`

`/forgecast` with no arguments opens a hand-rolled settings screen. `/forgecast toggle` is gone.

- **Toggles:** forge panel, incomplete-data warnings, capture tools (dev builds only).
- **Drag-to-position editor** on a second screen, with Bigger/Smaller and Reset.
- **Persists** to `config/forgecast.properties`, saved the moment a change is made.
- **No library.** YACL, MoulConfig and Dandelion all declined.
- **Defaults minimal:** panel off, capture tools off. Warnings on — see §7.

**Tested (17 tests):** round trip, missing file, truncated file, binary rubbish, bad number, bad
boolean, unknown keys, comments, clamping, atomic replace, unusable path.

**Not tested, and cannot be:** every visual. Layout, dragging, scaling, the screen background,
whether buttons overlap on small GUI scales. All UNVERIFIED.

---

## 4. Performance — measured, not guessed

I wrote a throwaway JIT-warmed benchmark against a real 105-row fixture, took the numbers, and
deleted it. **Nothing was changed.**

| Path | Cost | Frequency | Per second |
|---|---|---|---|
| `ForgeParser.parse` | **15.8 µs** | 1/s (HUD) + 1/s (advice) | ~32 µs |
| `ProfileReader.profileOf` | **8.3 µs** | 2/s | ~17 µs |
| `ForgeMemory.update` | **0.49 µs** | 1/s | ~0.5 µs |
| `ForgeAdvice.classify` | **0.03 µs** | 1/s | negligible |
| per-frame `lowercase()+contains` | **0.024 µs** | ~200/s | ~5 µs |

**Total measured steady-state cost: roughly 50 microseconds per second — about 0.005% of one
second.** That cannot produce a visible stutter.

### Ranked suspects

1. **The tick-capture, before tonight.** 🔴 It ran on *every tick* whenever **any** container
   screen was open — including your own inventory — walking ~90 slots and **rebuilding every
   tooltip, twice a second**. This is the only thing in the codebase doing heavy work at high
   frequency, and "stutter around loading screens" fits opening a screen shortly after a load.
   **You called this correctly.** It is now off by default. **This is the first thing your A/B
   test should confirm.**
2. **Component→string conversion, 2× per second.** 🟠 `readTabRows` walks the component tree of
   ~105 tab entries and builds a legacy string for each — done independently by the HUD and by
   the advice check. I could not measure this without a running game. Twice the work needed;
   one shared snapshot per second would halve it.
3. **Per-frame `lowercase()` allocation.** 🟡 Measured at 0.024 µs, ~5 µs/s. Real garbage, but
   far too small to see. Not worth changing on its own.
4. **HUD `font.width()` per segment per frame.** 🟡 ~24 calls/frame while the panel is shown.
   Small, and the panel is off by default.

### Honest conclusion

**With the tick-capture gated, I cannot find anything in ForgeCast capable of a visible stutter.**
The measured total is four orders of magnitude below a perceptible frame drop. If the stutter
persists with the jar removed *and* returns with tonight's build installed, suspect #2, and I'd
want a profiler rather than more reasoning. **Your A/B test is the right next step.**

---

## 5. Audit findings, by severity

### 🔴 High

**1. Anonymisation doesn't reach git history.** The fixtures are clean *now*, but every earlier
commit still contains 47 real usernames and your bank balance, on a public repo. Cleaning it
means rewriting history — which you forbade, correctly, unattended. **Decision needed (§7).**

### 🟠 Medium

**2. The "widget system off" case is unreachable.** `ForgeAdvice` correctly distinguishes it
(fixed tonight, `2520dd4`) — but `checkForgeAdvice` first requires a `Profile:` row to confirm
SkyBlock, and that row *is* a widget row. If widgets are off entirely, we bail before
classifying. So the player is never told. Fixing it needs a different SkyBlock signal
(scoreboard title, server brand). **Not attempted — it's a design decision.**

**3. The tab list is converted twice per second.** The HUD and advice paths each call
`readTabRows` + `ForgeParser.parse` independently, one second apart, on the same data. Correct,
but double the work. A shared once-per-second snapshot would fix it — that overlaps the merge
layer you reserved, so **I left it alone**.

### 🟡 Low

**4. `GuiForgeParser` is dead code in production.** Referenced by zero other main files. Correct
for now — it's waiting for the merge layer — but it ships in the jar doing nothing.

**5. `ForgeSnapshot.unparsedRows` is now almost always empty.** After the wrapping fix, the
section stops at the first non-slot row, so rows rarely land there. It still records *why* the
scan stopped, which is useful, but it no longer means what its name suggests.

**6. `ForgeCast.kt` is 510 lines** and holds commands, advice, the tab reader, GUI capture and
text conversion. Not a problem yet; worth splitting before it grows again.

**7. One test asserts little.** `the default expiry is six hours` just pins a constant against
itself. It documents a decision rather than testing behaviour. Harmless; not deleted, since
deleting tests overnight is exactly what you told me not to do.

### ✅ Checked and clean

- **No `!!` anywhere in main** — no non-null assertion can crash.
- **Every parser is defensive**: unknown shapes become `UNKNOWN` with raw text, never a guess.
- Tooltip generation is wrapped in `runCatching`; a bad item can't lose a whole capture.
- Config decoding never throws; corrupt input yields defaults per field.
- No `TODO`/`FIXME`/`HACK` in tracked files. No local paths leaked in `.vscode/launch.json`.

---

## 6. What went wrong

**1. My anonymisation script silently did nothing on the first run.** `printf` interpreted `\E`
as an escape byte, so all 47 patterns were `\QName<ESC>` and matched nothing — while the
*financial* substitutions in the same script worked, which made it look partly successful. Caught
only because I verified afterwards instead of trusting the exit code. Redone correctly.

**2. I corrupted `ForgeCast.kt` with a bad multi-line `perl -0pi` substitution**, injecting text
over the `package` line. Caught by the compiler, restored with `git checkout`, redone with
precise edits. **Nothing was committed in that state** — but it's a reminder that regex surgery
on source files is a bad habit, and I used it too often tonight.

**3. My own test caught a destructive bug I wrote.** `ConfigStore.save` reported success when the
config path was a *directory*, because `File.delete()` succeeds on an empty one — so it would
have silently deleted a directory to make room. Now refuses. Fixed before commit.

Also minor: the benchmark took three attempts (Gradle swallows test stdout; a Unix path handed
to Java on Windows; heredoc escaping).

---

## 7. Decisions I need from you

### A. Git history still contains your data 🔴

**Recommendation: rewrite it, but only with you awake and watching.**

The repo is public and 25 commits deep. `git filter-repo` on ten fixture files would do it, but
it changes every commit hash after `2ee52fd`, requires a force-push, and I was told to do
neither. Given the repo has existed for hours and has no other contributors, the risk of
rewriting is low and the benefit is real — those are other people's usernames.

Alternative if you'd rather not: delete and recreate the repo from the current tree, losing
history. Cheaper and safer, but you lose the commit trail you've been building deliberately.

### B. Should warnings default ON? 🟡

I set `adviceEnabled = true`, which is the one thing not off by default. Reasoning: it only
speaks when the mod *cannot see your forge*, and only once per situation — a silent failure
seemed worse than one quiet line. **This arguably contradicts "features off or quiet".** One
constant in `ForgeCastConfig` if you disagree.

### C. The unreachable "widget system off" warning 🟠

Detecting SkyBlock needs a signal that isn't itself a widget row. Options: scoreboard title,
server brand, or accept the gap. **I recommend accepting it for now** — the case only matters
for players who've disabled widgets entirely, and a wrong SkyBlock check would spam lobbies.

### D. Your plan's "widget only" line contradicts its own later section

See §1. **Recommend striking the earlier line** — the GUI is second-accurate and the later
section already says so.

---

## 8. Needs your eyes in game — all UNVERIFIED

Nothing below was run in a game. It compiles; that is all I can honestly claim.

1. **`/forgecast` opens the settings screen** — and the title/buttons don't overlap at your GUI scale.
2. **Toggling the panel** works and persists across a restart.
3. **The drag editor** — the panel is grabbable, drags without jumping, clamps at the edges.
4. **Bigger/Smaller** actually scales the text. This uses a matrix transform I could not test.
5. **The panel draws at the configured position** after the origin change (it now draws relative
   to an origin, then translates — a real change to how it positions itself).
6. **`/forgecast dump` and `dumpgui` still work** in the dev environment after enabling capture
   tools in the menu. If they refuse, the gate is inverted.
7. **`config/forgecast.properties` appears** and survives a restart.
8. **The screens render a background** — I could not confirm whether `extractRenderState` draws
   one automatically in 26.x, and did not add one speculatively.

---

## 9. What I deliberately did not do

- **The merge/cache layer** — you reserved it. `GuiForgeParser` remains unwired.
- **Slice 2 (completion notification)** — not in tonight's list.
- **Any performance "fix"** — item 4 was report-only. The tick-capture gating was item 2a's
  safety requirement, not a speculative optimisation, though it likely helps most.
- **Rewriting git history** — forbidden, and correctly so.
- **Deleting the low-value test** in §5.7 — you told me not to touch tests to make things pass,
  and I'd rather over-apply that rule than under-apply it.
- **Editing `forge-mod-plan.md`** — it's your document. Corrections are in §1.
- **Splitting `ForgeCast.kt`** — a 510-line refactor unattended, with no visual tests to catch a
  mistake, is exactly the kind of change that should happen with you watching.

---

## Commits

| Hash | |
|---|---|
| `2520dd4` | Report an absent widget system as off, not as pushed out |
| `2600fdb` | Gate the capture tools off by default and out of release builds |
| `f051319` | Anonymise the committed tab-list fixtures |
| `7bbd7ab` | Add the config model, codec and file store |
| `4e94025` | Add the settings screen and fold the HUD toggle into it |
| `cb483b1` | Share the Hypixel address check instead of repeating it |

**81 tests** — ForgeParser 28, ForgeCastConfig 17, ForgeMemory 13, ForgeAdvice 12, GuiForgeParser 11.

`cb483b1` is committed locally but **not yet pushed** — I stopped pushing once the audit began so
the last commit could be reviewed before it leaves the machine. `git push` when you're happy.
