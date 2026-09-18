# Godot 4.7.1 plugin

This Enginehost bundle takes Godot's Java classes from the official Android
library `org.godotengine:godot:4.7.1.stable`, pairs them with a native engine
built from this tree, and attaches the fragment directly to the host runtime
process. It accepts a live game folder and runs it through Godot's
documented `--path` / `--main-pack` command line.
Additional engine settings may be passed as a string array in
`options.commandLine`; they are appended after the plugin-selected project or
pack argument. A normal game needs none of them.

The capability covers the Godot 4.7 series while retaining 4.7.1 as the exact
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

## Encrypted packs

A project exported with an encryption key ships a pack whose file directory,
and usually whose files, are AES-256 encrypted. `try_open_pack` decrypts the
directory with the 32 bytes of `script_encryption_key`
(`core/io/file_access_pack.cpp`), and that array is not read from anywhere at
run time: `core/core_builders.py` compiles it into the export template from the
build's `SCRIPT_AES256_ENCRYPTION_KEY`. So the key lives only inside the
binaries the game shipped with, and this bundle's own engine, built with the
default constant, fails such a game in `ProjectSettings::_setup` with
`Can't open encrypted pack directory`.

`GodotPackKey` reads the key back out of the game's own program. Every 32-byte
window of every initialized, non-executable section of the `.exe` or `.x86_64`
is a candidate, and a candidate is accepted only when decrypting the game's
real pack directory with it reproduces the MD5 the pack recorded -- the same
check `FileAccessEncrypted::open_and_parse` makes. A wrong key therefore cannot
be accepted. Writable sections are searched first, because the array is a
mutable global and `.data` is small; a one-AES-block filter on the first
plaintext block rejects nearly every candidate before any full decrypt. On the
two builds of Anomalous Coffee Machine 2 the key comes back in a fraction of a
second, and the Windows and Linux builds yield the same key.

The plugin then exports it as `ENGINEHOST_GODOT_PACK_KEY`, 64 hexadecimal
characters, which `platform/android/java_godot_lib_jni.cpp` applies to
`script_encryption_key` before `Main::setup`, removing it from the environment
as it does. With nothing exported the compiled constant stands and the engine
behaves exactly as upstream. The key is never logged, shown or written to disk.

A folder with an encrypted pack and no program to read the key from fails
before the engine starts, naming the game, saying the pack is encrypted, and
listing the files that were read without finding it.

`tools/GodotPackKeyCheck.java` runs the shipped routine on a workstation,
against fixtures it builds itself and, given a game's executable and pack, a
real game:

    javac -d /tmp/godot-keycheck         app/src/main/java/dev/enginehost/plugin/godot/GodotPackKey.java         app/src/main/java/dev/enginehost/plugin/godot/GodotPackResolver.java         tools/GodotPackKeyCheck.java
    java -cp /tmp/godot-keycheck dev.enginehost.plugin.godot.GodotPackKeyCheck         [<game executable> <pack>]

It reports a recovered key as a digest of itself, never as the key.

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

**4.5 and earlier** place no restriction on `--main-pack`.

**4.6 and later** reject a `--main-pack` whose path does not resolve to
`FileAccess::ACCESS_RESOURCES`, unless the binary was compiled with the
`disable_path_overrides=no` SCons option. The option was added in 4.6
(present in `SConstruct` at 4.6.3-stable, absent at 4.5.1-stable) and
defaults to disabling the overrides in export templates, so published artifacts
from 4.6 on refuse an external pack with:

    --main-pack is attempting to load from outside of the executable, but this
    Godot binary was compiled without support for path overrides. Aborting.

No other command line avoids this. On Android `ACCESS_RESOURCES` is bound to
`FileAccessAndroid`, which reads only the APK's own assets (see
`initialize_core` in `platform/android/os_android.cpp`), and both redirects away
from it are themselves behind `OVERRIDE_PATH_ENABLED`. So no `res://` path can
reach an extracted pack, and `--path` is gated by the same define. The option
arrives with 4.6 (`SConstruct`, `disable_path_overrides`, default on for export
templates), so from this line on the published Maven native library cannot
load an external pack: `Main::setup` aborts with "--main-pack is attempting to
load from outside of the executable". This line's native engine is built from
this tree with `disable_path_overrides=no` (the workflow's scons flags), and
only the Java classes come from Maven.

## Save location

Godot has no command line option for the user data directory, on any released
line, and upstream `OS_Android::get_user_data_dir` ignores the project's own
user directory name and returns the app's files directory: one app, one game.
Under Enginehost one app runs every Godot game, so every game's `savegame.save`
landed in the same private folder. The plugin exports the save folder the person
chose as `ENGINEHOST_SAVE_PATH`, and this fork's `OS_Android::get_user_data_dir`
puts the project's own directory inside it under the desktop's own rule
(`OS_Unix::get_user_data_dir`): `godot/app_userdata/<project name>`, or the
project's custom user directory. Outside Enginehost nothing changes.

Godot is copyright its contributors and licensed under MIT. The Maven artifact
retains upstream notices; see <https://godotengine.org/license/> and the
[official Android-library documentation](https://docs.godotengine.org/en/stable/tutorials/platform/android/android_library.html).

No game files are copied. Builds run only in CI.
