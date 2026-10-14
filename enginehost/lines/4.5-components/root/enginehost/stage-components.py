#!/usr/bin/env python3
"""Stage the bundle's runtime components into the payload.

Reads enginehost/components.json (one entry per component this line builds)
and, for each, writes components/<name>/<version>/ into the payload:

  lib/arm64-v8a/<library>, lib/x86_64/<library>   from the CI artifact
  <license>                                        the component's licence
  <name>.gdextension                               the config the wrapper
                                                   hands the engine

Then checks the table against enginehost/bundle-metadata.json: every
(name, version) a capability declares in runtimeComponents must be staged,
and every staged component must be declared by some capability. A bundle
that declares a component it does not carry would fail at launch; one that
carries a component nothing declares is dead weight. Both fail the build.

Enginehost docs/engine-bundle-format.md, "Adding a runtime component".
"""
import json
import pathlib
import re
import shutil
import sys

ABIS = {"arm64-v8a": "arm64", "x86_64": "x86_64"}  # bundle ABI -> Godot arch feature tag
SEGMENT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")

payload = pathlib.Path(sys.argv[1])
here = pathlib.Path(__file__).resolve().parent
table = json.loads((here / "components.json").read_text())["components"]
metadata = json.loads((here / "bundle-metadata.json").read_text())

staged = set()
for component in table:
    name, version = component["name"], component["version"]
    for segment in (name, version):
        if not SEGMENT.fullmatch(segment) or ".." in segment:
            sys.exit(f"component segment {segment!r} cannot be a path segment")
    source = pathlib.Path(component["artifact"])
    target = payload / "components" / name / version
    libraries = []
    for abi, arch in ABIS.items():
        built = source / "lib" / abi / component["library"]
        if not built.is_file():
            sys.exit(f"{name} {version}: no {abi} library at {built}; every bundle ships arm64-v8a and x86_64")
        (target / "lib" / abi).mkdir(parents=True, exist_ok=True)
        shutil.copyfile(built, target / "lib" / abi / component["library"])
        libraries.append(f'android.{arch} = "lib/{abi}/{component["library"]}"')
    licence = source / component["license"]
    if not licence.is_file():
        sys.exit(f"{name} {version}: its licence {licence} is missing; a component's licence travels with it")
    shutil.copyfile(licence, target / component["license"])
    # Paths relative to the config itself (GDExtensionLibraryLoader resolves
    # them against its directory); "android.<arch>" with no build-type tag so
    # the entry matches whatever template the engine is.
    (target / f"{name}.gdextension").write_text(
        "[configuration]\n\n"
        f'entry_symbol = "{component["entrySymbol"]}"\n'
        f'compatibility_minimum = "{component["compatibilityMinimum"]}"\n\n'
        "[libraries]\n\n" + "\n".join(libraries) + "\n"
    )
    staged.add((name, version))
    print(f"staged {name} {version} -> {target}")

declared = {
    (name, version)
    for capability in metadata["capabilities"]
    for name, version in capability.get("runtimeComponents", {}).items()
}
if declared - staged:
    sys.exit(f"capabilities declare components this build does not carry: {sorted(declared - staged)}")
if staged - declared:
    sys.exit(f"components carried that no capability declares: {sorted(staged - declared)}")
