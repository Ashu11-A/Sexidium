# Art toolchain (`scripts/art.py`)

Every Python art/asset script lives in one file, `scripts/art.py`, behind a
subcommand CLI. Run `python3 scripts/art.py <command> --help` for options.

Two groups:

- **Icon sheet pipeline** — cuts the magenta-keyed pixel-art sprite sheet
  (`icons/input.png`, 2048×2048, 230 icons in 6 groups) into individually named,
  transparent, cropped PNGs and bundles them as `icons/sexidium-icons.zip`.
  Needs numpy + scipy + Pillow.
- **Menu / chest art** — imports `./icons/` button sprites and bakes the chest-GUI
  background frames into `assets/`. Needs only Pillow (numpy/scipy are never
  imported by these commands, so a Pillow-only host can still bake art).

## Setup (uv venv, one time)

```bash
uv venv .venv-icons
uv pip install --python .venv-icons numpy scipy pillow
```

## Run the icon pipeline (order matters)

```bash
PY=.venv-icons/bin/python
$PY scripts/art.py extract  --in icons/input.png --out /tmp/icons_raw   # detect + cut + de-key
$PY scripts/art.py name-zip                                            # name, classify, zip
```

## Commands

| command | role |
|---------|------|
| `extract` | detection + magenta removal. Local-median grid split for glued icons, fragment merge, contained-box dedup, 3-stage chroma key (key → boundary peel → neutralise) so no pink rim. Writes `/tmp/icons_raw/idx_NNN.png` + `manifest.json`. |
| `name-zip` | re-derives section + reading-order position, applies the descriptive name tables, writes `/tmp/icons_named/<section>/<name>.png` + `index.json`, then `icons/sexidium-icons.zip`. |
| `montage` | preview sheets on a checkerboard. `--mode idx\|pos\|proof` → `sec_*` / `pos_*` / `proof_*`. Used to eyeball detection and pick names. |
| `debug` | detection diagnostics: green-bbox overlay (`debug_boxes.png`), full-res quadrants (`q_*.png`), size histogram, per-section counts vs targets. |
| `gen-menu-art` | imports `./icons/<section>/*` verbatim → `assets/menu-art/item/<section>/*.png` (the in-game menu button textures). |
| `bake-medieval` | imports the UltimateGUI **Medieval** pack: normalises each `UI/UltimateGUI_medieval_pack/Medieval/generic_<rows*9>.png` into a 256×256 font cell with opaque content at `(0,0)` → `assets/ui/chest/chest_<rows>.png`. The **live** chest-frame generator. |
| `slice-typography` | slices the medieval `typography_title`/`typography_button` font sheets into per-char PNGs → `assets/menu-art/item/font_{title,button}/char_<A-Z>.png` (the `sexidium:title` / `sexidium:button` fonts). Uppercase Latin only. |
| `bake-overhang` | bakes the earlier ornate, overhanging chest-GUI backgrounds from `UI/frame/*` → `assets/ui/chest/chest_<rows>.png`. Superseded by `bake-medieval`; kept for history. |
| `cut-chest` / `extend-chest` / `align-chest` | the earliest flat "Beyond GUI" slot-frame lineage (cut from `UI/slots/*`, extended/warped onto the real slot grid). Kept for history / alternate looks. |

The shared section boundaries + reading order live once in `art.py` (used by
`extract` / `name-zip` / `montage` / `debug`), so detection and naming can never
drift.
