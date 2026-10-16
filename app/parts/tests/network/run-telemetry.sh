#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

test_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
tree_root=$(cd -- "$test_dir/../../../../../../.." && pwd)
test_output=$(mktemp -d "${TMPDIR:-/tmp}/galaxian-network-tests.XXXXXX")
trap 'rm -rf -- "$test_output"' EXIT
java_root=${JAVA_HOME:-$tree_root/prebuilts/jdk/jdk21/linux-x86}
framework_jar="$tree_root/out/soong/.intermediates/frameworks/base/framework-minus-apex/android_common/combined/framework.jar"
if [[ ! -f "$framework_jar" ]]; then
    echo 'This host test requires the compiled platform framework.jar; it does not build it.' >&2
    exit 1
fi
JAVA_HOME="$java_root" "$tree_root/external/kotlinc/bin/kotlinc" \
    "$test_dir/../../src/org/lineageos/settings/galaxian/network/NetworkModels.kt" \
    "$test_dir/../../src/org/lineageos/settings/galaxian/network/NetworkRepository.kt" \
    "$test_dir/NetworkTelemetryTest.kt" \
    -classpath "$framework_jar:$tree_root/prebuilts/sdk/current/public/android.jar" \
    -include-runtime -d "$test_output/network-tests.jar"
"$java_root/bin/java" -cp "$test_output/network-tests.jar:$framework_jar:$tree_root/prebuilts/sdk/current/public/android.jar" \
    org.lineageos.settings.galaxian.network.NetworkTelemetryTestKt
