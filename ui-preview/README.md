# UI preview renders — what these images are

**These are not screenshots of Minecraft.** No pixel in this folder came from the
game. The client layer (`dev.chatmacro.studio.client.*`) has never been compiled
against Minecraft in this environment, because `maven.fabricmc.net`,
`libraries.minecraft.net` and `services.gradle.org` are unreachable here — see
`KNOWN_LIMITATIONS.md`.

## What they *are*

A deterministic drawing produced by `render_ui_preview.py` from three real inputs:

| Input | Where it comes from |
| --- | --- |
| Colours | `theme-tokens.json`, dumped from the shipping `ThemeManager` by running `ThemeDump.java` through the offline javac harness — the same table the widgets read at runtime |
| Geometry | `StudioScreen` (header, columns, toolbar algorithm), `MacroCardWidget` (card height, toggle), `ProfileListPanel` (row height). The script **fails** if a constant drifts from the source |
| Text | the real `assets/chatmacro_studio/lang/*.json` strings, including Turkish characters |

The renderer also mirrors `Colors.composite()`, `Colors.enforceContrast()` and
`StudioPalette.ellipsize()`, and it **refuses to draw a translucent colour** — a
fill that receives `#AARRGGBB` aborts the render instead of quietly producing a
see-through panel.

## What it caught

Rendering the screen found four defects that reading the code did not:

1. `accentSoft` / `selection` / `dangerSoft` are alpha tokens, and three call
   sites passed them straight to a fill — the selected card and the selected
   profile row would have punched translucent holes in their panels. Fixed with
   `Colors.composite(...)` plus a regression test (`theme: tint composites stay
   opaque`).
2. The amber "Komut" pill drew light text on amber (~1.6:1 contrast). The pill now
   derives its label colour from its own fill via `Colors.enforceContrast`.
3. The header panel ended at y=40 while its two label rows were drawn at y=32/35,
   so the title and status hung outside their panel.
4. The toolbar was a fixed chain of 9 buttons (`~570 px`) with no breakpoint: on a
   300 px window the buttons ran off the right edge and the labels overlapped. It
   now measures itself, shrinks the primary buttons, swaps to localized short
   labels and moves the secondary actions into one menu button.

## The clip

`studio-walkthrough.gif` is the same renderer drawing five hand-authored states
(selection moving down the list, a hovered toolbar button, the settings popup).
It is an **illustration of state transitions, not a screen recording** — there is
no animation curve, no input handling and no game frame in it. The 120–220 ms
animation timings are unit-tested in `ui`/`animation` suites; the clip does not
test them.

## What it cannot tell you

Nothing about hitboxes, focus order, narration, animation timing, scrollbars,
text input behaviour, or whether the screen even opens. Those need a real
`runClient` with Minecraft and Fabric present. `README_TR.md` /
`README_EN.md` and `TEST_REPORT.md` keep that distinction explicit.

## Re-rendering

```bash
python3 scripts/render-ui-preview.sh              # needs Pillow: pip install pillow
python3 tools/ui-preview/render_ui_preview.py --clip   # + the walkthrough GIF
```
