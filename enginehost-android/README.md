# Godot 4.5.1 plugin

This Enginehost bundle embeds the official Godot Android library
`org.godotengine:godot:4.5.1.stable` and attaches its fragment directly to the
host runtime process. It accepts a live game folder and runs it through Godot's
documented `--path` / `--main-pack` command line.
Additional engine settings may be passed as a string array in
`options.commandLine`; they are appended after the plugin-selected project or
pack argument. A normal game needs none of them.

The capability covers the Godot 4.5 series while retaining 4.5.1 as the exact
runtime metric. Godot 3.x and other 4.x lines belong in co-installable bundles.

## What the folder may hold

`GodotPackResolver` is the only thing that decides what Godot is pointed at, in
this order:

1. the folder's `execFile`, when one is set;
2. the folder's single `.pck` or `.zip`;
3. a `project.godot`, which is opened with `--path`;
4. otherwise, the first file in the folder carrying an appended pack.

A self-contained export -- Godot's own default, a single executable with the
`.pck` appended -- is handed to `--main-pack` as it lies. Godot's
`PackedSourcePCK::try_open_pack` already looks for the `GDPC` trailer at the end
of whatever it is given, so nothing is extracted and nothing is copied: pointing
a 2.3 GB Windows `.exe` at the engine costs no disk and no first-launch wait.
The carrier's own architecture is irrelevant, because only the appended data is
read and the machine code is never loaded.

Anything that resolves to no loadable pack fails before the engine starts, with
a message naming what was looked for. It must never fail as an
`IllegalArgumentException`: `GodotFragment.performEngineInitialization` reads
that exception as "the APK expansion pack is missing" and replaces the game with
its OBB downloader UI, so a launch error would vanish behind a progress bar for a
download that cannot exist.

## All Files Access

Godot's Android file layer (`StorageScope`) refuses any path outside the app's
own directories and shared storage unless the host holds All Files Access, no
matter what the filesystem itself allows. Games live on removable storage, so
the plugin checks for the permission up front and says so, rather than letting
the engine start and then fail to open the pack it was handed.

## External packs and path overrides

Enginehost points Godot at a pack outside the APK, so the engine has to accept
a `--main-pack` naming an arbitrary filesystem path. Whether it does depends on
the Godot line, and it is the first thing to check when branching a new version.

**4.6 and earlier** place no restriction on `--main-pack`. A version branch off
one of these lines needs nothing beyond retargeting the Gradle dependency; the
published Maven artifact serves.

**4.7 and later** reject a `--main-pack` whose path does not resolve to
`FileAccess::ACCESS_RESOURCES`, unless the binary was compiled with the
`disable_path_overrides=no` SCons option. The option was added in 4.7 and
defaults to disabling the overrides in export templates, so published artifacts
from 4.7 on refuse an external pack with:

    --main-pack is attempting to load from outside of the executable, but this
    Godot binary was compiled without support for path overrides. Aborting.

No other command line avoids this. On Android `ACCESS_RESOURCES` is bound to
`FileAccessAndroid`, which reads only the APK's own assets (see
`initialize_core` in `platform/android/os_android.cpp`), and both redirects away
from it are themselves behind `OVERRIDE_PATH_ENABLED`. So no `res://` path can
reach an extracted pack, and `--path` is gated by the same define. A 4.7+ branch
therefore cannot use the published Maven artifact for external packs; it needs
`platform=android target=template_release disable_path_overrides=no` built from
the Godot source this repository already carries.

This branch is cut from 4.5.1, which predates the option entirely, so the
published artifact loads an external pack exactly as shipped and no source
build is involved here. The branches cut from 4.7 and later, `plugin-core`
among them, are the ones that carry the restriction.

## Known gap: save location

Godot has no command line option for the user data directory, on any released
line. `user://` therefore resolves to the runtime app's own Android data
directory, not to the save directory Enginehost hands the session. Redirecting
it needs a mechanism other than the command line.

Godot is copyright its contributors and licensed under MIT. The Maven artifact
retains upstream notices; see <https://godotengine.org/license/> and the
[official Android-library documentation](https://docs.godotengine.org/en/stable/tutorials/platform/android/android_library.html).

No game files are copied. Builds run only in CI.
