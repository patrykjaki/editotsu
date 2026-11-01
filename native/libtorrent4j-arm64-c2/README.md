# CP1.5-B arm64 native release candidate

This directory owns the reproducible build and provenance for the first arm64 productionization
candidate. The shipped JAR contains the exact built-in-crypto, WebTorrent-disabled, `-O0`, no-LTO
ELF that passed the C2 10/10 physical-device native startup gate. Broader playback and lifecycle
validation remains required before release approval.

The `-O0` profile is intentional. An optimized ELF is a different candidate and must not replace
this artifact until it has its own Build ID, provenance, packaged-APK proof, and device matrix.

## Verify the checked-in release input

```sh
./gradlew verifyApprovedArm64Native
python3 native/libtorrent4j-arm64-c2/verify_artifact.py \
  --jar app/libs/libtorrent4j-android-arm64-2.1.0-39-cp1.5b-c2.jar
```

## Rebuild from pinned source

Docker must be running and the host Gradle packaging step requires JDK 21. The build uses
`linux/amd64` deliberately because the pinned NDK host toolchain is `linux-x86_64`; Apple Silicon
hosts run it through Docker emulation. Set `CP15B_JAVA_HOME` (or `JAVA_HOME`) to a JDK 21
installation when a newer host JDK is the default. The repo overlay also pins the Gradle 8.14.3
distribution SHA-256 before wrapper packaging.

```sh
native/libtorrent4j-arm64-c2/build.sh
```

The script refuses to reuse an existing source checkout, verifies both source commits, uses a
pinned Ubuntu archive snapshot, validates download hashes in the container build, and accepts the
rebuilt output only when the native ELF hash, Build ID, dependencies, and crypto-symbol boundary
match `provenance.json`.

Release and alpha builds fail if the checked-in approved JAR is missing or changed. CI also fails
unless both the arm64 split and universal APK contain the approved Build ID and native boundary.
There is no fallback to the upstream Maven arm64 native artifact.
