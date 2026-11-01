#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "${SCRIPT_DIR}/../.." && pwd)
WORK_ROOT=${CP15B_NATIVE_WORK_ROOT:-${REPO_ROOT}/build/cp1-5b-native}
CHECKOUT=${WORK_ROOT}/libtorrent4j
OUTPUT_DIR=${WORK_ROOT}/output
IMAGE_TAG=editotsu/libtorrent4j-arm64-c2:cp1.5b-o0
WRAPPER_COMMIT=da7d2403f06a0939bc2c51a88e67a6c4a85512ef
LIBTORRENT_COMMIT=24a3adf35d3e689bde700371337aad61b160bafa

if [[ -n "${CP15B_JAVA_HOME:-}" ]]; then
    export JAVA_HOME=${CP15B_JAVA_HOME}
fi
JAVA_BIN=java
if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA_BIN=${JAVA_HOME}/bin/java
fi
JAVA_SPEC_VERSION=$("${JAVA_BIN}" -XshowSettings:properties -version 2>&1 \
    | awk -F'= ' '/java.specification.version/ { print $2; exit }')
if [[ "${JAVA_SPEC_VERSION}" != "21" ]]; then
    echo "JDK 21 is required for pinned wrapper packaging; found ${JAVA_SPEC_VERSION:-unknown}." >&2
    echo "Set CP15B_JAVA_HOME (or JAVA_HOME) to a JDK 21 installation." >&2
    exit 1
fi

if [[ -e "${CHECKOUT}" ]]; then
    echo "Refusing to reuse native checkout: ${CHECKOUT}" >&2
    echo "Move it aside and rerun so the build starts from a clean clone." >&2
    exit 1
fi

mkdir -p "${WORK_ROOT}" "${OUTPUT_DIR}"
git clone https://github.com/aldenml/libtorrent4j.git "${CHECKOUT}"
git -C "${CHECKOUT}" checkout --detach "${WRAPPER_COMMIT}"
git -C "${CHECKOUT}" submodule update --init --recursive

[[ "$(git -C "${CHECKOUT}" rev-parse HEAD)" == "${WRAPPER_COMMIT}" ]]
[[ "$(git -C "${CHECKOUT}/swig/deps/libtorrent" rev-parse HEAD)" == "${LIBTORRENT_COMMIT}" ]]

install -m 0644 "${SCRIPT_DIR}/overlays/swig/Jamfile" "${CHECKOUT}/swig/Jamfile"
install -m 0644 "${SCRIPT_DIR}/overlays/swig/config/android-arm64-config.jam" \
    "${CHECKOUT}/swig/config/android-arm64-config.jam"
install -m 0644 "${SCRIPT_DIR}/overlays/gradle/wrapper/gradle-wrapper.properties" \
    "${CHECKOUT}/gradle/wrapper/gradle-wrapper.properties"

docker build --platform linux/amd64 --tag "${IMAGE_TAG}" "${SCRIPT_DIR}/docker"
docker run --rm --platform linux/amd64 \
    --volume "${CHECKOUT}:/libtorrent4j" "${IMAGE_TAG}" /b2-arm64.sh

(
    cd "${CHECKOUT}"
    ./gradlew --no-daemon clean nativeAndroidArm64Jar
)

CANDIDATE_NAME=libtorrent4j-android-arm64-2.1.0-39.jar
CANDIDATE_JAR=${CHECKOUT}/build/libs/${CANDIDATE_NAME}
if [[ ! -f "${CANDIDATE_JAR}" ]]; then
    CANDIDATE_JARS=()
    while IFS= read -r candidate; do
        CANDIDATE_JARS+=("${candidate}")
    done < <(find "${CHECKOUT}" -type f -name "${CANDIDATE_NAME}" -print)

    if [[ ${#CANDIDATE_JARS[@]} -ne 1 ]]; then
        echo "Expected exactly one rebuilt candidate JAR, found ${#CANDIDATE_JARS[@]}" >&2
        printf 'Candidate: %s\n' "${CANDIDATE_JARS[@]}" >&2
        exit 1
    fi
    CANDIDATE_JAR=${CANDIDATE_JARS[0]}
fi
python3 "${SCRIPT_DIR}/verify_artifact.py" \
    --candidate-jar "${CANDIDATE_JAR}" \
    --report "${OUTPUT_DIR}/artifact-verification.json"

cp "${CANDIDATE_JAR}" "${OUTPUT_DIR}/libtorrent4j-android-arm64-2.1.0-39-cp1.5b-c2-rebuilt.jar"
cp "${SCRIPT_DIR}/provenance.json" "${OUTPUT_DIR}/approved-provenance.json"
docker image inspect --format '{{.Id}}' "${IMAGE_TAG}" > "${OUTPUT_DIR}/docker-image-id.txt"
git -C "${CHECKOUT}" rev-parse HEAD > "${OUTPUT_DIR}/libtorrent4j-commit.txt"
git -C "${CHECKOUT}/swig/deps/libtorrent" rev-parse HEAD > "${OUTPUT_DIR}/libtorrent-commit.txt"

echo "Verified native build outputs: ${OUTPUT_DIR}"
