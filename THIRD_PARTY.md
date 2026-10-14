# Third-party components

This repository is a fork of Godot Engine. Godot's own MIT licence is at
`LICENSE.txt` (kept unmodified, verified byte-identical to
https://github.com/godotengine/godot's `LICENSE.txt`) and its logo licence is
`LOGO_LICENSE.txt`. The `thirdparty/` directory (bundled unmodified from
upstream) has its own authoritative per-library index at
`thirdparty/README.md`; this file summarises rather than duplicates it.

| Component | Version / commit | Licence | Source | Where in tree |
|---|---|---|---|---|
| Godot Engine | `plugin/4.5` line | MIT | https://github.com/godotengine/godot | entire tree (fork) |
| Bundled third-party libraries (accesskit, freetype, harfbuzz, icu4c, and the rest of `thirdparty/`) | as pinned by the Godot 4.5 release | mixed (mostly MIT/BSD/Apache-2.0/zlib/public domain; see each entry) | see `thirdparty/README.md` | `thirdparty/` |
| Enginehost's own Android integration (`enginehost/`) | this repository | MIT | https://github.com/Droidtop/enginehost-godot-plugin | `enginehost/` |

## Obligations

Godot Engine and everything currently vendored under `thirdparty/` are
permissively licensed (MIT/BSD/Apache-2.0/zlib/public domain per
`thirdparty/README.md`), so Enginehost's own additions are MIT, the most
permissive licence compatible with all of it. `LICENSE.txt` is left exactly
as upstream ships it and is not edited by this file.
