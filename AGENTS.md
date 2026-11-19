# 剪一年 · Snip & Grow: project guidance

A tabletop bonsai in a PICO OS 6 Shared Space volume (Spatial SDK BOM 6.1.9), package `io.embodit.bonsai`, v0.1.0.
Design contract: spatial-app-factory `apps/bonsai/design-package.json` (v2.1, gate B 2026-11-18 "都按默认": Shared Space
volume + control window, 1 m wide pot, props pot B / stand B / tray B / stone A / can A, name 剪一年 · Snip & Grow).
Skeleton from the factory's tabletop template (two containers, theme, SoundPool, debug receiver, procedural meshes, GLB
calibration, window control, board-input template with DragTotal); the tree, growth, score, scene and pages are this
app's own.

## Surfaces
- **Pot volume** (default container `SnipGrowPot`, Volumetric 1.0 x 0.9 x 0.78 m, fixed scale, gravity aligned, base
  panel hidden). Content root x 1.25 (design units, `Layout.K`); a uniform user resize scales the floor node too.
  Tripo props (tools/calibrate_models.py -> `domain/ModelCalib.kt`), procedural tree (`domain/Pieces.kt`: 16-side trunk
  tube, 12-side tapered twig segments, lumpy cloud pads), target outline shells, two 3D ◀ ▶ wooden turn buttons, the
  season sign (AttachmentPanel, actual metres), one directional light.
- **Control window** (`SnipGrowControl`, Planar 520 x 600 dp, `Placement.right(pot)`): native TabBar 盆景 / 架子 / 设置.
  Pick page (换一盆) hides the TabBar. [退出应用] closes both containers, removes the tasks, ends the process.

## Code
| Path | What |
|---|---|
| `domain/Tree.kt` | node graph, generation, one watering = one year, cut sites bud, rest year, merge, Score (32³ Dice), `Tuning` per form, test players |
| `domain/Garden.kt` | the single state source: taps, cut / undo / water / turn / guide / done / shelf / pick, sounds |
| `domain/Save.kt` | text codec for files/current.txt + shelf.txt (exact doubles; restore keeps the RNG state) |
| `domain/Layout.kt` | fixed geometry (design units + actual metres), eyes, part boxes |
| `ui/scene/*` | `BonsaiScene` (entities, per-frame tweens on the wall clock), `VolumeScreen` (input -> Garden, sign) |
| `ui/control/*` | the window pages, `Thumb` 2D side view, `VolumeDrag` (B20 slider) |
| `tools/` | `calibrate_models.py`, `import_sounds.py` (CC0 cuts), `build_icon.py`, `loudness.py` |

## Build / test (kj-mbp-m5)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew assembleDebug :app:testDebugUnitTest
```
Offline release: add `-x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease` if lint is not cached.

## Debug broadcast (QA screenshots)
`adb shell am broadcast -a io.embodit.bonsai.DEBUG -p io.embodit.bonsai --es cmd <cmd> --es a "<arg>"`, log tag `SnipGrow`.
Every input command goes through the same Garden calls as a real pinch / button.

| State | Commands (in order) |
|---|---|
| S00 | `s00hold on`, force-stop + launch, shoot within 10 s, `s00hold off` |
| S03g first run | `clear`, force-stop + launch (guide step 1; `guide 2` / `guide 3` for the next steps) |
| S03 | `reset` · `pot shakan 2026` (or chokkan / kengai) · `look summer|autumn|winter|spring` · `outline on|off` · `turnbtn left|right` · `drag 30` |
| S04 | `reset` → `tappad` (highest pad) or `tappad long` → `stepin` (x2 for a bigger cut) |
| S05 | S04 → `freeze 300` → `snip` (`freeze off` after) |
| S06 | `freeze 300|800` → `water` |
| S07 | `cut 0.5` (rest next year) → `water` (no growth) → `water` (grows) |
| S09 | `autoprune 4` (auto player cuts + waters) or `done` |
| S10 | `page shelf` (empty) · `done` → `shelve` |
| S11 | shelf has an item → `view 0` · `confirm delete` |
| S12 / S12h | `page settings` · `howto on` · `confirm exit` · `lang zh|en|system` |
| S13 change | `page shelf` → `confirm change` → `pick` (S02) |
| S14 | `closepanel` (sign becomes [打开面板]) · `openpanel` |
| other | `taptrunk` · `undo` · `randomcut 3` · `water 3` · `dump` |
