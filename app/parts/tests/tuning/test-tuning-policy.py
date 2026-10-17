#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check the tuning lab's cross-file contract and optional offline binary policy.

The compiled-policy checks use libsepol in userspace; they never load or change the
host's SELinux policy. Run after compiling sepolicy with --policy and --libsepol.
"""
import argparse
import ctypes as C
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

parts = Path(__file__).resolve().parents[2]
device = parts.parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--policy", type=Path)
parser.add_argument("--libsepol", type=Path)
args = parser.parse_args()
checks = 0


def require(condition, description):
    global checks
    checks += 1
    if not condition:
        raise SystemExit("FAIL: " + description)


source = parts / "src/org/lineageos/settings/galaxian/tuning"
models = (source / "TuningModels.kt").read_text()
session = (source / "VmUclampManager.kt").read_text()
policy_dir = device / "sepolicy/private"
contexts = (policy_dir / "property_contexts").read_text()
vendor_contexts = (device / "sepolicy/vendor/genfs_contexts").read_text()
private_contexts = (policy_dir / "genfs_contexts").read_text()
policy_text = (policy_dir / "galaxian_parts_tuning.te").read_text()
vendor_policy = (device / "sepolicy/vendor/galaxian_parts_tuning.te").read_text()
rc = (parts / "init.galaxian_parts_tuning.rc").read_text()

vm_values = {
    "stock": ("20", "10", "100"),
    "balanced": ("15", "5", "80"),
    "performance": ("20", "10", "50"),
    "battery": ("10", "5", "150"),
}
for name, values in vm_values.items():
    require(f'{name.upper()}({",".join(values)})' in models.replace(" ", ""),
            f"VM preset {name} matches the curated values")
uclamp_values = {"default": ("0", "1024"), "battery": ("0", "512"), "performance": ("128", "1024")}
for name, (low, high) in uclamp_values.items():
    require(f'{name.upper()}({low},{high})' in models.replace(" ", ""),
            f"uclamp preset {name} matches the curated values")

actions = {}
trigger = None
for raw in rc.splitlines():
    line = raw.split("#", 1)[0].strip()
    if not line:
        continue
    if line.startswith("on "):
        trigger = line[3:]
        require(trigger not in actions, "each init trigger occurs once")
        actions[trigger] = []
    else:
        require(trigger is not None and raw[0].isspace(), "init contains only action commands")
        actions[trigger].append(line)

require(actions.pop("property:sys.boot_completed=1", None) ==
        ["setprop sys.galaxianparts.tuning_ready 1"],
        "boot advertises readiness without changing tuning")
require(set(actions) ==
        {f"property:sys.galaxianparts.vm_profile={n}" for n in vm_values} |
        {f"property:sys.galaxianparts.uclamp_profile={n}" for n in uclamp_values},
        "init actions match the presets exactly")
for name, values in vm_values.items():
    nodes = ("dirty_ratio", "dirty_background_ratio", "vfs_cache_pressure")
    require(actions[f"property:sys.galaxianparts.vm_profile={name}"] ==
            [f"write /proc/sys/vm/{node} {value}" for node, value in zip(nodes, values)],
            f"{name} writes only its three fixed VM values and never swappiness")
for name, (low, high) in uclamp_values.items():
    require(actions[f"property:sys.galaxianparts.uclamp_profile={name}"] == [
            f"write /proc/sys/kernel/sched_uclamp_util_min {low}",
            f"write /proc/sys/kernel/sched_uclamp_util_max {high}"],
            f"{name} writes only its two fixed clamp values")
require("${" not in rc and "persist." not in rc, "no expanded writes or persisted boot selection")
require('SystemProperties.set("sys.galaxianparts.vm_profile"' in session,
        "Android wrapper requests the VM preset property")
require('SystemProperties.set("sys.galaxianparts.uclamp_profile"' in session,
        "Android wrapper requests the uclamp preset property")
require('SystemProperties.get("sys.galaxianparts.tuning_ready") == "1"' in session,
        "Android wrapper checks readiness")
require(not re.search(r"writeText|FileOutputStream|Runtime\.getRuntime|ProcessBuilder", session),
        "preset wrappers neither write proc directly nor invoke a shell")

require("set_prop(galaxian_parts_app, galaxianparts_vm_request_prop)" in policy_text,
        "Parts can set only its typed VM request")
require("set_prop(galaxian_parts_app, galaxianparts_uclamp_request_prop)" in policy_text,
        "Parts can set only its typed uclamp request")
require("get_prop(galaxian_parts_app, galaxianparts_tuning_status_prop)" in policy_text,
        "Parts reads readiness")
require("set_prop(galaxian_parts_app, galaxianparts_tuning_status_prop)" not in policy_text,
        "Parts cannot declare itself ready")
require("allow galaxian_parts_app proc_galaxian_tuning:file r_file_perms;" in policy_text,
        "proc tuning nodes are read-only for the app")
require("allow init proc_galaxian_tuning:file w_file_perms;" in policy_text,
        "init alone writes the VM and clamp presets")
require("allow galaxian_parts_app sysfs_galaxian_tuning:file rw_file_perms;" in vendor_policy,
        "governor and I/O nodes are writable only through the tuning type")
require(not re.search(r"allow\s+init\s+\{[^}]*\bproc\b", policy_text + vendor_policy),
        "init write is not broadened to generically labelled proc nodes")
require(not re.search(r"\b(?:permissive|sys_admin|sys_resource)\b",
                      policy_text + vendor_policy),
        "new policy grants no admin capability")
normalized_policy = re.sub(r"\s+", " ", policy_text)
require("allow galaxian_parts_app { proc_dirty proc_loadavg proc_pressure_cpu "
        "proc_pressure_io proc_pressure_mem }:file r_file_perms;" in normalized_policy,
        "platform-labelled reclaim and stall nodes are read-only for the app")

# The platform labels these itself; a second genfs entry for the same path is a
# duplicate genfs error at build time, so our file must leave them untouched.
for node in ("dirty_ratio", "vfs_cache_pressure"):
    require(f"genfscon proc /sys/vm/{node} u:object_r:proc_galaxian_tuning:s0"
            in private_contexts, f"unlabelled VM node {node} gets our label")
for node in ("dirty_background_ratio", "swappiness"):
    require(f"genfscon proc /sys/vm/{node} " not in private_contexts,
            f"already-labelled VM node {node} is not relabelled")
for node in ("sched_uclamp_util_min", "sched_uclamp_util_max", "sched_bore"):
    require(f"genfscon proc /sys/kernel/{node} u:object_r:proc_galaxian_tuning:s0"
            in private_contexts, f"{node} is labelled")
for node in ("pressure/cpu", "pressure/memory", "pressure/io", "loadavg"):
    require(f"genfscon proc /{node} " not in private_contexts,
            f"platform-labelled {node} is not relabelled")
for node in ("policy0/scaling_governor", "policy4/scaling_governor",
              "policy0/scaling_available_governors", "policy4/scaling_available_governors",
              "schedutil/rate_limit_us"):
    require(f"genfscon sysfs /devices/system/cpu/cpufreq/{node} " in vendor_contexts and
            "sysfs_galaxian_tuning" in vendor_contexts, f"{node} is labelled")
for prefix in ("/block", "/kernel/bore"):
    require(f"genfscon sysfs {prefix} " in vendor_contexts, f"{prefix} is labelled")
require("genfscon sysfs /class/devfreq " not in vendor_contexts,
        "devfreq keeps the MediaTek label instead of being relabelled")
for statement in (
        "allow galaxian_parts_app sysfs_galaxian_tuning:dir r_dir_perms;",
        "allow galaxian_parts_app sysfs_galaxian_tuning:lnk_file r_file_perms;",
        "allow galaxian_parts_app sysfs_devices_block:dir r_dir_perms;",
        "allow galaxian_parts_app sysfs_devices_block:file rw_file_perms;",
        "allow { hal_power_default mtk_hal_power } sysfs_galaxian_tuning:file rw_file_perms;"):
    require(statement in vendor_policy, "vendor tuning policy keeps: " + statement)

build = (parts / "Android.bp").read_text()
module = re.search(r'prebuilt_etc\s*\{[^{}]*name:\s*"init.galaxian_parts_tuning.rc"[^{}]*\}', build)
require(module is not None and "system_ext_specific: true" in module[0]
        and "vendor:" not in module[0], "tuning actions run from system_ext as core init")
require('"init.galaxian_parts_tuning.rc"' in build.split("required:", 1)[1].split("]", 1)[0],
        "app installation requires the tuning init module")
print(f"PASS: {checks} tuning source-contract checks")

if not args.policy:
    print("Compiled policy not checked (pass --policy to verify effective access).")
    raise SystemExit(0)

sys.path.insert(0, str(parts / "thermal/tests"))
from check_property_access import OfflinePolicy
policy = OfflinePolicy(args.policy, args.libsepol)
lib = policy.lib
lib.sepol_genfs_sid.argtypes = [C.c_char_p, C.c_char_p, C.c_uint16, C.POINTER(C.c_uint32)]
lib.sepol_sid_to_context.argtypes = [C.c_uint32, C.POINTER(C.c_char_p), C.POINTER(C.c_size_t)]
libc = C.CDLL(None)
libc.free.argtypes = [C.c_void_p]


def genfs_context(fstype, path):
    cls, sid = C.c_uint16(), C.c_uint32()
    require(lib.sepol_string_to_security_class(b"file", C.byref(cls)) == 0, "resolve file class")
    require(lib.sepol_genfs_sid(fstype.encode(), path.encode(), cls, C.byref(sid)) == 0,
            "resolve compiled label for " + path)
    context, length = C.c_char_p(), C.c_size_t()
    require(lib.sepol_sid_to_context(sid, C.byref(context), C.byref(length)) == 0,
            "read compiled context for " + path)
    try:
        return context.value.decode()
    finally:
        libc.free(context)


def access(domain, target, klass, permission, expected):
    actual, _ = policy.allowed(f"u:r:{domain}:s0", target, klass, permission)
    require(actual == expected, f"{domain} {target} {klass}:{permission} = {expected}")


# Our own labels: app reads, init writes, neither can be widened.
for node in ("dirty_ratio", "vfs_cache_pressure"):
    context = genfs_context("proc", "/sys/vm/" + node)
    require(context == "u:object_r:proc_galaxian_tuning:s0", "exact compiled label for " + node)
    for permission in ("read", "open", "getattr"):
        access("galaxian_parts_app", context, "file", permission, True)
    for permission in ("write", "append", "setattr"):
        access("galaxian_parts_app", context, "file", permission, False)
    access("init", context, "file", "write", True)

for node in ("sched_uclamp_util_min", "sched_uclamp_util_max", "sched_bore"):
    context = genfs_context("proc", "/sys/kernel/" + node)
    require(context == "u:object_r:proc_galaxian_tuning:s0", "exact compiled label for " + node)
    access("galaxian_parts_app", context, "file", "read", True)
    access("galaxian_parts_app", context, "file", "write", False)
    access("init", context, "file", "write", True)

# swappiness belongs to the MediaTek vendor policy (proc_swappiness, vendor
# type) so the app can never read or write it; the VM presets leave it alone.
require(genfs_context("proc", "/sys/vm/swappiness") != "u:object_r:proc_galaxian_tuning:s0",
        "swappiness keeps the vendor label")
access("galaxian_parts_app", genfs_context("proc", "/sys/vm/swappiness"), "file", "read", False)

# Platform labels kept in place: the app reads, never writes.
require(genfs_context("proc", "/sys/vm/dirty_background_ratio") == "u:object_r:proc_dirty:s0",
        "dirty_background_ratio keeps its platform label")
require(genfs_context("proc", "/loadavg") == "u:object_r:proc_loadavg:s0",
        "/proc/loadavg keeps its platform label")
for node in ("cpu", "memory", "io"):
    context = genfs_context("proc", "/pressure/" + node)
    require(context.startswith("u:object_r:proc_pressure_"), "pressure keeps its platform label")
    access("galaxian_parts_app", context, "file", "read", True)
    access("galaxian_parts_app", context, "file", "write", False)
for context in (genfs_context("proc", "/sys/vm/dirty_background_ratio"),
                genfs_context("proc", "/loadavg")):
    access("galaxian_parts_app", context, "file", "read", True)
    access("galaxian_parts_app", context, "file", "write", False)
# Init already owns the reclaim sysctl the VM presets write.
access("init", genfs_context("proc", "/sys/vm/dirty_background_ratio"), "file", "write", True)

for node in ("min_free_kbytes", "overcommit_memory"):
    context = genfs_context("proc", "/sys/vm/" + node)
    require(context != "u:object_r:proc_galaxian_tuning:s0", "other VM sysctls keep labels: " + node)
    access("galaxian_parts_app", context, "file", "write", False)

# Disk queue nodes stay on the MediaTek block label; the app is granted there.
access("galaxian_parts_app", "u:object_r:sysfs_devices_block:s0", "file", "read", True)
access("galaxian_parts_app", "u:object_r:sysfs_devices_block:s0", "file", "write", True)
# The governor nodes we do own are writable by the app and the power HAL only.
access("galaxian_parts_app", "u:object_r:sysfs_galaxian_tuning:s0", "file", "write", True)
access("mtk_hal_power", "u:object_r:sysfs_galaxian_tuning:s0", "file", "write", True)
access("shell", "u:object_r:sysfs_galaxian_tuning:s0", "file", "write", False)

for label in ("galaxianparts_vm_request_prop", "galaxianparts_uclamp_request_prop",
              "galaxianparts_tuning_status_prop"):
    context = f"u:object_r:{label}:s0"
    access("galaxian_parts_app", context, "file", "read", True)
    access("init", context, "property_service", "set", True)
    access("galaxian_parts_app", context, "property_service", "set",
           label != "galaxianparts_tuning_status_prop")
    for domain in ("untrusted_app", "system_app", "shell", "vendor_init"):
        access(domain, context, "property_service", "set", False)
print(f"PASS: {checks} tuning contract and effective-policy checks")
