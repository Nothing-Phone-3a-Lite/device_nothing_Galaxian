#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

test_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
tree_root=$(cd -- "$test_dir/../../../../../../.." && pwd)
test_output=$(mktemp -d "${TMPDIR:-/tmp}/galaxian-tuning-tests.XXXXXX")
trap 'rm -rf -- "$test_output"' EXIT
java_root=${JAVA_HOME:-$tree_root/prebuilts/jdk/jdk21/linux-x86}
framework_jar="$tree_root/out/soong/.intermediates/frameworks/base/framework-minus-apex/android_common/combined/framework.jar"
if [[ ! -f "$framework_jar" ]]; then
    echo 'This host test needs the existing compiled platform framework.jar; it does not build it.' >&2
    exit 1
fi
JAVA_HOME="$java_root" "$tree_root/external/kotlinc/bin/kotlinc" \
    "$test_dir/../src/org/lineageos/settings/galaxian/tuning/TuningModels.kt" \
    "$test_dir/../src/org/lineageos/settings/galaxian/tuning/CpuGovManager.kt" \
    "$test_dir/../src/org/lineageos/settings/galaxian/tuning/GpuGovManager.kt" \
    "$test_dir/../src/org/lineageos/settings/galaxian/tuning/IoManager.kt" \
    "$test_dir/../src/org/lineageos/settings/galaxian/tuning/VmUclampManager.kt" \
    "$test_dir/../src/org/lineageos/settings/galaxian/tuning/BoreLmkDiagManager.kt" \
    "$test_dir/TuningLabTest.kt" \
    -classpath "$framework_jar:$tree_root/prebuilts/sdk/current/public/android.jar" \
    -include-runtime -d "$test_output/tuning-tests.jar"
"$java_root/bin/java" -cp "$test_output/tuning-tests.jar:$framework_jar:$tree_root/prebuilts/sdk/current/public/android.jar" \
    org.lineageos.settings.galaxian.tuning.TuningLabTestKt
