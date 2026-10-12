# Image decoder builds

The repository contains the decoder and sandbox source, not source archives or compiled
helpers. Both Build and Release call `image-decoders.yml`:

1. Download FFmpeg 9.0.2, zlib 1.3.2 and WASI SDK 34 from their upstream release URLs.
2. Check pinned SHA-256 values before extracting anything. Fetch FFmpeg's detached signature
   and public key directly from ffmpeg.org, and require a valid signature from
   `FCF986EA15E6E293A5644F10B4322F04D67658D8`.
3. Compile FFmpeg and zlib into `build/native-wasm/decoder.wasm`.
4. Build each platform's native helper from that WASM on a matching runner, using
   `Cargo.lock` with `--locked`. Run the Rust tests, pixel comparison, malformed-input
   checks and OS sandbox probes before uploading the helper.
5. Download the six tested helper artifacts from the **same workflow run**, check their
   validation hashes, and stage only the eight shipping executables. Gradle refuses to
   build a JAR if any supported platform is missing.

There is no runtime decoder download. The finished mod still bundles its helpers.
Archives, WASM, AOT modules and executables are generated under ignored directories.
Old `provenance.json` checks and committed-helper comparisons are no longer used.

## Local builds

The full six-platform build runs in GitHub Actions. For a local mod build, download the
six `native-PLATFORM` artifacts from a successful Build run **for the commit you checked
out** in the repository you trust. Extract each into `.native-artifacts/native-PLATFORM/`,
then run:

```sh
python native/stage-resources.py .native-artifacts .native-resources
./gradlew clean build remapJar
```

On Windows, use `gradlew.bat`. Staged resources live outside `build/` so Gradle's `clean`
does not delete them. `-Pnative_resources_dir=/path/to/resources` selects another staging
directory. A validation hash detects changed files; it does not authenticate artifacts
from an untrusted repository or workflow. Do not reuse artifacts from a different commit.

To rebuild the WASM yourself on x86-64 Linux, install Python 3.11+, GnuPG, make and tar,
then run `bash native/build-wasm.sh`. This always fetches and verifies upstream inputs.
Copy the resulting `build/native-wasm/decoder.wasm` to the same path on a matching native
build machine. With Rust and the platform toolchain installed, run
`python native/build-native.py PLATFORM`. Windows needs Visual Studio C++ build tools;
Linux needs musl-tools. This produces the helper and its validation report in
`build/native/PLATFORM/`, with test-only probes in a separate directory.

## Updating dependencies

Review URL, version, digest and signing-key changes in `fetch-sources.py` against the
[FFmpeg download page](https://ffmpeg.org/download.html), [zlib release page](https://zlib.net/)
and [WASI SDK release](https://github.com/WebAssembly/wasi-sdk/releases/tag/wasi-sdk-34).
Update the corresponding source directory names in `build-wasm.sh` when changing versions.
A new digest alone does not establish authenticity. FFmpeg must also pass the pinned-key
signature check. Review changes to the downloader, build scripts and workflows like code:
this removes opaque dependency replacements, but cannot make malicious build scripts safe.

Run the focused checks with:

```sh
python -m unittest discover -s native/tests -p test_build_inputs.py
```
