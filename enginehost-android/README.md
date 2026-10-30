# Godot 4.5.1 plugin

This Enginehost bundle embeds the official Godot Android library
`org.godotengine:godot:4.5.1.stable` and attaches its fragment directly to the
host runtime process. It accepts a live game folder and runs it through Godot's
documented `--main-pack` command line.
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
3. a `project.godot` is refused with a sentence: on Android `res://` is the
   APK's own assets, so an unexported project cannot be opened;
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

## The platform a game sees

A game exported for Windows or Linux runs here unmodified, and it behaves as
it does on that desktop only if the engine tells it that is where it is.
Asked `OS.get_name()` and the `pc`/`mobile`/platform feature tags, this engine
answers for the platform the game was exported for, which the wrapper reads
from the game's own executable (PE, ELF or Mach-O) and exports as
`ENGINEHOST_GODOT_PLATFORM`. Feature-tagged project settings follow, so a
game takes its desktop values (`rendering_method`, not
`rendering_method.mobile`); `options.commandLine` can still force a renderer
per game. A pack with no executable beside it sees Android, as does any game
whose folder sets `"options": {"platform": "android"}`, for a game whose own
touch layout is wanted.

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

Only the engine knows that name, so it says it: `get_user_data_dir` exports the
folder it resolved as `ENGINEHOST_USER_DATA_DIR` (lines 4.2 and later), and the
wrapper hands it to Enginehost (`EngineHost.reportSaveDirectory`) after the engine
starts and again when the game is left. Enginehost then names that folder as the
game's saves (its `GAME_ENDED` broadcast, Droidtop/tracker#547) instead of the
whole save folder.

Godot is copyright its contributors and licensed under MIT. The Maven artifact
retains upstream notices; see <https://godotengine.org/license/> and the
[official Android-library documentation](https://docs.godotengine.org/en/stable/tutorials/platform/android/android_library.html).

No game files are copied. Builds run only in CI.

## Engine patches

`enginehost/patches/series` lists the changes to Godot itself that every line in a version
range carries. They are applied when a line is composed, before the line's own patch.

- **The window draws without Godot's first frame** (every line). Android gives input focus
  only to a window that has drawn. `GLSurfaceView` reports the window drawn only after the
  GL thread finishes a frame, and Godot's first frame boots the whole game. A large pack on
  slow storage kept that first frame going for minutes. Back then found no focused window,
  and Android answered with "isn't responding" (Goodbye Eternity, Droidtop/tracker#604). If
  no frame has come within a second, the window now draws (black) without it, once. The
  game keeps loading, and Back reaches Enginehost.
- **Absolute paths are read natively** (every line, in its own patch beside the API 30 rule
  above `initialize_core`'s file-access defaults). Upstream reads any absolute path, the
  game's pack above all, through Java's FileAccessHandler: one JNI call and one read per
  `get_8`. Goodbye Eternity's start made 8 to 13 thousand one-byte reads a second on BlueStacks,
  all of them crossing JNI, and stayed black for minutes (Droidtop/tracker#604, ANR trace in
  `FileAccessHandler.fileRead`). Such paths now use
  Godot's own `FileAccessUnix`, buffered by stdio, as `user://` and `res://` already do.
  Enginehost holds all-files access and hands the engine real paths, so the Java handler's
  `content://` support is never needed there. This replaces
  4.5's Java-side read-ahead, which could not help: each read still crossed JNI.
- **A link the game opens is offered by Enginehost** (every line, in its own patch).
  `OS_Android::shell_open` no longer calls Godot's Java `open_uri`, which opened the link at
  once. It calls the plugin's `GodotLinks.openLink` (looked up by name in
  `java_godot_wrapper.cpp`, since Godot's Java side is the stock AAR), which hands it to
  `EngineHost.openLink`. Enginehost checks the link, says what it is (a Discord invite, a web
  page...) and offers to open it, in the app it belongs to or a browser, or to copy it; it never
  opens one by itself (Enginehost docs/SPEC.md, "Links a game opens"; Droidtop/tracker#635). On an
  Enginehost older than `openLink`, `shell_open` answers `ERR_UNAVAILABLE` and nothing opens.
- **A missing class is asked for** (`4.5-components` for now, in its own patch). When a game
  names a class neither the engine nor its own scripts define (a scene's `SpineSprite`, a
  script's `extends SpineSprite`), `ClassDB` asks a handler before answering no, outside its
  class lock, once per name, one ask at a time. Android's handler calls the plugin's
  `GodotComponents.requestGDExtensionFor` (a static method looked up by name, since Godot's
  Java side is the stock AAR), which asks Enginehost for the component that provides it
  (`EngineHost.requestComponent`). Enginehost answers with the installed, approved bundle, or
  installs it, or has the person accept its licence, while the engine's thread waits. The
  answer is a `.gdextension` config, which `GDExtensionManager` loads then, up to the level the
  engine has reached. The game goes on with the class, or without it as before. Godot's
  render thread can wait like this: its `GLSurfaceView` and Vulkan thread do not wait on it
  when the request screen pauses or hides the game.


## Runtime components

A game can need more than the stock engine: Spine animations, or another GDExtension its
desktop build carried. Enginehost matches the game's `runtimeRequirements` and tells the plugin
which components the launch loads (`EnginePluginSession.runtimeComponents()`).
`GodotComponents` finds each one's GDExtension config, and the fragment registers them as a
host `GodotPlugin`. Godot reads its `getPluginGDExtensionLibrariesPaths()` in
`OS_Android::load_platform_gdextensions` during core registration, before any game script
parses. The game's own files are never edited. A component is found in one of three places:

- a separately installed component bundle (`runtimeComponentDirectory(name)`), at
  `godot-<series>/<name>.gdextension` for the engine's own series. A capability that loads
  these says `loadsComponents`;
- this bundle's payload, at `components/<name>/<version>/<name>.gdextension`;
- the engine library, compiled in (lines 4.2 to 4.6 have spine-godot built in until the
  component replaces it): nothing to load.

`4.5-components` (`dev.enginehost.godot.v45.v2`) is the stock 4.5.1 engine with no module
compiled in, whose capability loads spine-godot as a separate component. It is the line the
compiled-in Spine is compared against before it is retired (Droidtop/tracker#291).

A game asks for a component while it runs, too ("games asks, and it receives", 2026-10-23;
Enginehost docs/engine-bundle-format.md, "A game asks, and receives"), on a line whose engine
carries the missing-class patch above. `GodotComponents.PROVIDERS` says which component provides
a class, by the prefix its classes share (`Spine` is spine-godot's), and each component is asked
for once per run. On a line without the patch, or on an Enginehost older than
`requestComponent`, nothing asks, and components come only from the launch.

## The spine-godot component line

`enginehost/lines/component-spine-godot-4.2` is not a Godot line. It builds the spine-godot
GDExtension as a separately installed component bundle (`kind: component`,
`dev.enginehost.godot.component.spine-godot.v42`, under the Spine Runtimes License; Enginehost
docs/engine-bundle-format.md, "Component bundles"). It carries one build per Godot series from
4.2 to 4.6, each against godot-cpp at that series' release tag (4.6 uses the 4.5 API, as
godot-cpp has no 4.6 tag), for arm64-v8a and x86_64: `godot-<series>/spine-godot.gdextension`
and `lib/<abi>/godot-<series>/libspine_godot.so`, with spine-runtimes' own `LICENSE` as
`LICENSE-spine-runtimes.txt`.

Its `line.json` declares no upstream (`"upstream": null`, `"sharedPatches": false`): the line
is plugin-core alone, and its build fetches spine-runtimes and godot-cpp at the commits the
line's `bundle-metadata.json` pins under `source`, the one place those pins are written.
spine-runtimes' history is far too large to carry in this repository as a line's upstream.

## 32-bit engines only on request, never published (2026-10-30)

Official Godot cores ship arm64-v8a and x86_64 only (Droidtop/tracker#646). For someone who needs a
32-bit engine, the owner kept a way to build one: dispatch `enginehost-android.yml` on a line with
`include_32_bit` on. CI then also compiles the runtime for `arm32` and `x86_32`, and gradle adds
armeabi-v7a and x86 (`-Penginehost32Bit=true`). The build ends as the unsigned bundle inputs artifact
and is never signed or published. Enginehost's signing gate refuses 32-bit libraries in any case.
