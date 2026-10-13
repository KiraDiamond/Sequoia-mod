"""Generate or check the third-party notices for the image decoder helpers.

usage: notices.py write | check

Writes src/main/resources/licenses/NOTICES.txt and texts/. A component that may be used under
Apache-2.0 (alone or as one of several choices) is used under it and only listed, since the one
Apache-2.0 text covers it; components without that choice ship their own license files. check
regenerates and fails unless the result equals the committed files. Both need the pinned Rust
toolchain and the crate sources (cargo fetch --locked in native/decoder).
"""
import hashlib, json, pathlib, re, shutil, subprocess, sys, tempfile, textwrap

ROOT = pathlib.Path(__file__).resolve().parents[1]
CRATE = ROOT / "native/decoder"
LICENSES = ROOT / "src/main/resources/licenses"
TARGETS = ["x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-musl",
           "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin"]
LICENSE_FILE = re.compile(r"^(LICEN[CS]E|COPYING|COPYRIGHT|UNLICENSE)([-._].*)?$", re.I)
# Texts no toolchain or crate provides, as fetched from upstream when added.
PINNED = {
    # musl v1.2.5 COPYRIGHT; wasi-libc 2e6fb9d8ee0c libc-bottom-half/cloudlibc/LICENSE.
    "musl-1.2.5-COPYRIGHT": "f9bc4423732350eb0b3f7ed7e91d530298476f8fec0c6c427a1c04ade22655af",
    "wasi-libc-cloudlibc-LICENSE":
        "c8b789cf5a746611e6300a0cc7750dbf92b61912a709d04e639245f7290656d0",
    # The "Legacy LLVM License" sections of compiler-rt/LICENSE.TXT (llvm-project 895aa2c896ad,
    # wasi-sdk 34) and libunwind/LICENSE.TXT (rust-lang/llvm-project 52ed14fcd56a, Rust 1.98.1).
    "llvm-compiler-rt-legacy-LICENSE":
        "b18fc5314cb2a476a1d631a9ed361020a2cbcecb13e7e0aab0a80342d72ff3cc",
    "llvm-libunwind-legacy-LICENSE":
        "dfd486d44f7735ffbe2ed52a1bcbb384235762fff1795419c3e4f57e7f7a03bd",
}
TOOLCHAIN_TEXTS = ["Apache-2.0.txt", "LLVM-exception.txt", "Unicode-3.0.txt"]
# Standard library crates linked into a binary. All are Apache-2.0 OR MIT (memchr: Unlicense OR
# MIT) per the toolchain's COPYRIGHT-library.html and crate manifests. Review on toolchain bumps.
STD_LINKED = ["std", "core", "alloc", "panic_abort", "panic_unwind", "unwind", "std_detect",
              "compiler_builtins", "addr2line", "adler2", "cfg_if", "gimli", "hashbrown", "libc",
              "memchr", "miniz_oxide", "object", "rustc_demangle"]
# Test harness, proc macros, profiling runtime and empty facade crates.
STD_UNLINKED = ["test", "getopts", "proc_macro", "rustc_literal_escaper", "profiler_builtins",
                "sysroot", "rustc_std_workspace_core", "rustc_std_workspace_alloc",
                "rustc_std_workspace_std"]


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def run(*args):
    return subprocess.run(args, cwd=CRATE, capture_output=True, text=True, check=True).stdout


def election(expression):
    """The license a crate is used under when one text covers it, else None."""
    if " AND " in expression or "(" in expression:
        return None
    options = [option.strip() for option in expression.replace("/", " OR ").split(" OR ")]
    return next((o for o in ("Apache-2.0", "Apache-2.0 WITH LLVM-exception", "Unlicense")
                 if o in options), None)


def shipped_crates():
    """Metadata of every crate compiled into a helper for any of the six targets."""
    packages = {(p["name"], p["version"]): p for p in json.loads(
        run("cargo", "metadata", "--locked", "--format-version", "1"))["packages"]}
    shipped = set()
    for target in TARGETS:
        tree = run("cargo", "tree", "--locked", "-e", "normal,no-proc-macro", "--target", target,
                   "--prefix", "none", "-f", "{p}")
        shipped |= {(line.split()[0], line.split()[1][1:]) for line in tree.splitlines() if line}
    return [packages[key] for key in sorted(shipped) if key[0] != "seqimages-decoder"]


def check_std(sysroot):
    host = re.search(r"^host: (\S+)", run("rustc", "-vV"), re.M).group(1)
    found = {re.match(r"lib(.+)-[0-9a-f]+\.rlib$", p.name).group(1)
             for p in (sysroot / "lib/rustlib" / host / "lib").glob("lib*.rlib")}
    changed = found ^ set(STD_LINKED + STD_UNLINKED)
    if changed:
        sys.exit(f"standard library crates changed: {sorted(changed)}; check their licenses and "
                 "update STD_LINKED in native/notices.py")


def generate():
    """{path under licenses/: bytes} for the notices of the current lock file and toolchain."""
    sources = json.loads((ROOT / "native/sources.json").read_text())
    ffmpeg, zlib, wasi = sources["ffmpeg"], sources["zlib"], sources["wasi_sdk"]
    sysroot = pathlib.Path(run("rustc", "--print", "sysroot").strip())
    rust = run("rustc", "--version").split()[1]
    check_std(sysroot)

    pinned = dict(PINNED)
    for key, source in (("ffmpeg", ffmpeg), ("zlib", zlib)):  # guest.py checks them in the archives
        pinned.update({f"{key}-{source['version']}-{name}": digest
                       for name, digest in source["license_files"].items()})
    files = {}
    for name, digest in pinned.items():
        data = (LICENSES / "texts" / name).read_bytes()
        if sha256(data) != digest:
            sys.exit(f"licenses/texts/{name} differs from its pinned upstream text")
        files[f"texts/{name}"] = data
    for name in TOOLCHAIN_TEXTS:
        files[f"texts/{name}"] = (sysroot / "share/doc/rust/licenses" / name).read_bytes()
    files["sequoia-images/LICENSE"] = (LICENSES / "sequoia-images/LICENSE").read_bytes()

    groups, own = {}, []
    for package in shipped_crates():
        title = f"{package['name']} {package['version']}"
        choice = election(package["license"])
        if choice:
            groups.setdefault(choice, []).append(title)
            continue
        folder = pathlib.Path(package["manifest_path"]).parent
        texts = sorted(p.name for p in folder.iterdir() if LICENSE_FILE.match(p.name))
        if not texts:
            sys.exit(f"{title} ({package['license']}) has no license file to ship")
        own.append(f"    {title}: {package['license']}")
        for text in texts:
            name = f"texts/{package['name']}-{package['version']}-{text}"
            files[name] = (folder / text).read_bytes()
            own.append(f"      {name}")
    # Keep "name version" items whole when wrapping.
    wrap = lambda items, indent: textwrap.fill(
        ", ".join(item.replace(" ", "\0") for item in items), 96, initial_indent=indent,
        subsequent_indent=indent, break_on_hyphens=False).replace("\0", " ")
    crates = "\n".join(f"    {choice}:\n{wrap(names, '      ')}"
                        for choice, names in sorted(groups.items()))
    std = textwrap.fill(
        f"Rust {rust} standard library: {', '.join(STD_LINKED[:3])} and the crates they link "
        f"({', '.join(STD_LINKED[3:])}): Apache-2.0, except memchr: Unlicense, and core's Unicode "
        "tables: Unicode-3.0", 96, initial_indent="  ", subsequent_indent="  ")
    lgpl, = (f"texts/ffmpeg-{ffmpeg['version']}-{name}" for name in ffmpeg["license_files"])
    zlib_text, = (f"texts/zlib-{zlib['version']}-{name}" for name in zlib["license_files"])
    files["NOTICES.txt"] = f"""Third-party notices for the chat image previews

sandbox/ in the mod JAR holds an image decoder helper for each platform; each helper embeds a
WebAssembly decoder guest. These are the third-party components in them. A component offered
under Apache-2.0 among other licenses is used under Apache-2.0, whose text is
texts/Apache-2.0.txt; none of them has a NOTICE file. Generated by native/notices.py. The image
preview code itself is under sequoia-images/LICENSE.

Decoder guest
  FFmpeg {ffmpeg['version']} (libavcodec, libavformat, libavutil, libswscale): {ffmpeg['license']},
  built without GPL or nonfree parts
    {lgpl}
    This software is based in part on the work of the Independent JPEG Group
    (libavcodec/jrevdct.c, unmodified).
    The corresponding source, sequoia-images-native-source.zip, is published next to the JAR
    with each release on GitHub (https://github.com/SequoiaWynncraft/Sequoia-mod/releases) and
    Modrinth. It holds the exact FFmpeg and zlib archives, the build scripts and instructions
    for building and using a modified decoder.
  zlib {zlib['version']}: Zlib
    {zlib_text}
  wasi-libc (wasi-sdk {wasi['version']}): Apache-2.0; parts from cloudlibc: BSD-2-Clause; parts from
  musl: MIT
    texts/wasi-libc-cloudlibc-LICENSE
    texts/musl-1.2.5-COPYRIGHT
  LLVM compiler-rt builtins (wasi-sdk {wasi['version']}): Apache-2.0 WITH LLVM-exception; older code
  under the legacy LLVM license
    texts/LLVM-exception.txt
    texts/llvm-compiler-rt-legacy-LICENSE

Helpers
{std}
    texts/Unicode-3.0.txt
  musl 1.2.5 C library (Linux): MIT
    texts/musl-1.2.5-COPYRIGHT
  LLVM libunwind (Linux): Apache-2.0 WITH LLVM-exception; older code under the legacy LLVM
  license
    texts/llvm-libunwind-legacy-LICENSE
  Microsoft C and C++ runtime libraries (Windows), statically linked under the Visual Studio
  and Windows SDK license terms. No notice file is bundled for them.
  Rust crates:
{crates}
{chr(10).join(own)}

Java libraries (nested JARs under META-INF/jars/)
  Apache HttpComponents httpclient5 5.6.4, httpcore5 5.4.3 and httpcore5-h2 5.4.3: Apache-2.0.
  Each JAR carries its own META-INF/LICENSE and META-INF/NOTICE.
""".encode()
    return files


def main():
    mode = sys.argv[1] if len(sys.argv) == 2 else ""
    if mode not in ("write", "check"):
        sys.exit(__doc__)
    produced = generate()
    if mode == "write":
        with tempfile.TemporaryDirectory() as temp:
            for name, data in produced.items():
                (pathlib.Path(temp) / name).parent.mkdir(parents=True, exist_ok=True)
                (pathlib.Path(temp) / name).write_bytes(data)
            shutil.rmtree(LICENSES)
            shutil.copytree(temp, LICENSES)
        print(f"wrote {len(produced)} notice files")
        return
    committed = {p.relative_to(LICENSES).as_posix(): p.read_bytes()
                 for p in LICENSES.rglob("*") if p.is_file()}
    changed = sorted(name for name in committed.keys() | produced.keys()
                     if committed.get(name) != produced.get(name))
    if changed:
        sys.exit("notices differ from native/notices.py output: " + ", ".join(changed))
    print(f"{len(produced)} notice files match")


if __name__ == "__main__":
    main()
