# enginehost Godot plugin

## Branches

A core has only these branches (Droidtop/tracker#324, the owner, 2026-11-05):

- `upstream-sync` follows upstream Godot (`godotengine/godot`). The daily `sync-upstream`
  workflow (on `plugin-core`) merges upstream's `master` into it; nothing of ours is developed
  there.
- `plugin-core`, the default branch, is the Enginehost recipe: the wrapper, the build workflows,
  the bundle metadata, the patch series and the line pins. Recipe changes land here first,
  through `ci/` branches.
- `plugin/<line>` is upstream at the commit `enginehost/lines/<line>/line.json` pins, composed
  with `plugin-core` by the plugin-line tool. Nothing on it is written by hand.
