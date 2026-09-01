# Godot 4.7.1 plugin

This Enginehost bundle embeds the official Godot Android library
`org.godotengine:godot:4.7.1.stable` and attaches its fragment directly to the
host runtime process. It accepts a live game folder and
runs either an unpacked `project.godot` project or a selected `.pck`/`.zip`
through Godot's documented `--path` / `--main-pack` command line.
Additional engine settings may be passed as a string array in
`options.commandLine`; they are appended after the plugin-selected project or
pack argument.

The capability covers the Godot 4.7 series while retaining 4.7.1 as the exact
runtime metric. Godot 3.x and other 4.x lines belong in co-installable bundles.

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

This branch is cut from 4.7.1 and is subject to that restriction: its bundle
cannot load external packs until such a source build exists. Branches cut from
4.6 and earlier, including `plugin/4.5`, are unaffected and work as shipped.

## Known gap: save location

Godot has no command line option for the user data directory, on any released
line. `user://` therefore resolves to the runtime app's own Android data
directory, not to the save directory Enginehost hands the session. Redirecting
it needs a mechanism other than the command line.

Godot is copyright its contributors and licensed under MIT. The Maven artifact
retains upstream notices; see <https://godotengine.org/license/> and the
[official Android-library documentation](https://docs.godotengine.org/en/stable/tutorials/platform/android/android_library.html).

No game files are copied. Builds run only in CI.
