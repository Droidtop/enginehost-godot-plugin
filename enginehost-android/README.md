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

Godot is copyright its contributors and licensed under MIT. The Maven artifact
retains upstream notices; see <https://godotengine.org/license/> and the
[official Android-library documentation](https://docs.godotengine.org/en/stable/tutorials/platform/android/android_library.html).

No game files are copied. Godot's user data is redirected to Enginehost's
user-visible save directory. Builds run only in CI.
