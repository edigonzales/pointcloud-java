#!/usr/bin/env python3
"""Build Oracle GraalVM native variants and train PGO on two complete LAZ inputs."""

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import struct
import subprocess
import time


ROOT = Path(__file__).resolve().parents[1]
VERSION = "25.6.5.1"
JDK_VERSION = "25.0.5.1"
DEFAULT_GRAAL = ROOT / ".cache/jdks/graalvm-25.6.5.1+4.1/Contents/Home"
REACHABILITY = ROOT / "experiments/native-image"
DATASETS = {"so2019": "train-so2019.laz", "autzen": "train-autzen.copc.laz"}
PAUSE = re.compile(r"\bGC\(\d+\) Pause .+?\s\d+(?:\.\d+)?(?:ns|us|µs|ms|s)\s*$", re.MULTILINE)
PROBE = """\
public class GcProbe {
    private static volatile byte[] retained;
    public static void main(String[] args) {
        for (int i = 0; i < 96; i++) {
            retained = new byte[1024 * 1024];
            retained[0] = (byte) i;
        }
        System.gc();
        System.out.println("gc-probe-ok");
    }
}
"""


def run(command, log, env):
    command = [str(argument) for argument in command]
    stdout = log.with_suffix(".stdout.log")
    stderr = log.with_suffix(".stderr.log")
    print(f"{log.name} …", flush=True)
    started = time.monotonic()
    with stdout.open("w") as out, stderr.open("w") as err:
        result = subprocess.run(command, cwd=log.parent, env=env, stdout=out, stderr=err)
    return {
        "argv": command,
        "elapsed_seconds": round(time.monotonic() - started, 3),
        "returncode": result.returncode,
        "stdout": str(stdout),
        "stderr": str(stderr),
    }


def output(result):
    return "\n".join(Path(result[stream]).read_text(errors="replace") for stream in ("stdout", "stderr"))


def require_success(result):
    if result["returncode"]:
        raise RuntimeError(f"{result['argv'][0]} exited {result['returncode']}. See {result['stderr']}")


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def file_details(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return {"path": str(path), "bytes": path.stat().st_size, "sha256": digest.hexdigest()}


def compiler(graal, gc, extra=()):
    return [graal / "bin/native-image", "-J-Xmx8g", "-O3", "-march=native", f"--gc={gc}", *extra]


def collector_name(gc):
    return "G1 GC" if gc == "G1" else "Serial GC"


def check_collector(result, gc):
    if f"Garbage collector: {collector_name(gc)}" not in output(result):
        raise RuntimeError(f"Builder omitted {collector_name(gc)} from {result['stdout']}")


def preflight(graal, run_dir, env, manifest):
    directory = run_dir / "gc-preflight"
    directory.mkdir(exist_ok=True)
    source = directory / "GcProbe.java"
    source.write_text(PROBE, encoding="utf-8")
    javac = run([graal / "bin/javac", "-d", directory, source], directory / "javac", env)
    manifest["probe_javac"] = javac
    require_success(javac)
    for gc in ("G1", "serial"):
        probe = directory / f"gc-{gc.lower()}"
        entry = manifest["collectors"][gc] = {}
        result = entry["probe_build"] = run(
            [*compiler(graal, gc), "-cp", directory, "-o", probe, "GcProbe"],
            directory / f"build-{gc.lower()}", env,
        )
        if result["returncode"] and gc == "G1":
            reason = next((
                line.strip() for line in output(result).splitlines()
                if re.search(r"G1.*(?:unsupported|not.*supported)|(?:unsupported|not.*supported).*G1", line, re.I)
            ), None)
            if reason:
                entry.update(status="unsupported", reason=reason)
                print(f"G1 unsupported: {reason}", flush=True)
                continue
        require_success(result)
        check_collector(result, gc)
        executed = entry["probe_run"] = run(
            [probe, "-Xmx32m", "-XX:+PrintGC"], directory / f"run-{gc.lower()}", env,
        )
        require_success(executed)
        pauses = {
            stream: len(PAUSE.findall(Path(executed[stream]).read_text(errors="replace")))
            for stream in ("stdout", "stderr")
        }
        if "gc-probe-ok" not in output(executed) or not any(pauses.values()):
            raise RuntimeError(f"{collector_name(gc)} probe did not expose a GC pause. See {directory}")
        entry.update(status="available", pause_lines=pauses)
        print(f"{collector_name(gc)} verified at build time and runtime.", flush=True)


def verify_toolchain(graal, run_dir, env, manifest):
    release = graal / "release"
    properties = dict(re.findall(r'^([A-Z_]+)="([^"\n]+)"$', release.read_text(), re.MULTILINE))
    expected = {
        "IMPLEMENTOR": "Oracle Corporation", "JAVA_VERSION": JDK_VERSION,
        "GRAALVM_VERSION": VERSION, "OS_ARCH": "aarch64", "OS_NAME": "Darwin",
    }
    if any(properties.get(key) != value for key, value in expected.items()):
        raise RuntimeError(f"Expected Oracle GraalVM {VERSION} for macOS AArch64. See {release}")
    manifest["graal_release"] = expected
    for binary, flag in (("java", "-version"), ("native-image", "--version")):
        result = manifest[binary] = run([graal / "bin" / binary, flag], run_dir / f"{binary}-version", env)
        require_success(result)


def las_point_count(source):
    with source.open("rb") as file:
        header = file.read(375)
    if len(header) < 227 or header[:4] != b"LASF" or header[24] != 1:
        raise RuntimeError(f"Invalid LAS header in {source}")
    if header[25] >= 4:
        if len(header) < 375:
            raise RuntimeError(f"Truncated LAS 1.4+ header in {source}")
        return struct.unpack_from("<Q", header, 247)[0]
    return struct.unpack_from("<I", header, 107)[0]


def build(graal, run_dir, env, classpath, gc, suffix, entry, extra=(), previous=None):
    binary = run_dir / f"native-{gc.lower()}{suffix}"
    command = [*compiler(graal, gc, extra), f"-H:ConfigurationFileDirectories={REACHABILITY}",
               "-cp", classpath, "-o", binary, "pointcloud.Main"]
    saved = previous.get("build") if previous else None
    if saved and saved["returncode"] == 0 and saved["argv"] == list(map(str, command)) and binary.is_file():
        if "artifact" in previous and file_details(binary) != previous["artifact"]:
            raise RuntimeError(f"Previously built {binary} has changed.")
        print(f"Reusing {binary.name}.", flush=True)
        result = saved
    else:
        result = run(command, run_dir / f"build-{binary.name}", env)
    entry["build"] = result
    require_success(result)
    check_collector(result, gc)
    proof = output(result)
    expected_pgo = (
        "user-provided" if suffix == "-pgo" else r"(?:[\w-]+\+)*instrument" if suffix == "-instrumented" else None
    )
    if expected_pgo and not re.search(rf"PGO:\s*{expected_pgo}", proof, re.IGNORECASE):
        raise RuntimeError(f"Builder did not confirm the requested PGO mode for {binary}. See {result['stdout']}")
    if suffix != "-instrumented" and "optimization level: 3," not in proof:
        raise RuntimeError(f"Builder did not confirm optimization level 3 for {binary}. See {result['stdout']}")
    entry["artifact"] = file_details(binary)
    for utility, args in (("file", []), ("otool", ["-L"])):
        check = entry[utility] = run(
            [f"/usr/bin/{utility}", *args, binary], run_dir / f"{utility}-{binary.name}", env,
        )
        require_success(check)
        if utility == "file" and not re.search(r"Mach-O.*\barm64\b", output(check)):
            raise RuntimeError(f"Expected an arm64 Mach-O executable. See {check['stdout']}")
    return binary


def train(binary, run_dir, env, gc, name, source, workers, entry, reference):
    label = f"{gc.lower()}-{name}-w{workers}"
    prefix = run_dir / f"train-{label}"
    profile = prefix.with_suffix(".iprof")
    metadata = prefix.with_suffix(".metadata.json")
    las = prefix.with_suffix(".las")
    pipeline = prefix.with_suffix(".pipeline.json")
    modern = name == "autzen"
    step = 100 if modern else 500
    dimensions = ["X", "Y", "Z", "Intensity", "Classification"]
    if modern:
        dimensions.extend(("Red", "Green", "Blue"))
    decimate = {"type": "filters.decimation", "step": step, "offset": 0}
    statistics = {"type": "filters.stats", "dimensions": ",".join(dimensions)}
    filters = [decimate, statistics] if modern else [statistics, decimate]
    write_json(pipeline, [
        str(source),
        *filters,
        {"type": "writers.las", "filename": str(las), "forward": "all",
         "compression": False, "extra_dims": "all"},
    ])
    profile.unlink(missing_ok=True)
    result = entry["run"] = run(
        [binary, "-Xmx512m", f"-XX:ProfilesDumpFile={profile}",
         "pipeline", pipeline, "--metadata", metadata, "--laz-workers", workers, "--overwrite"],
        prefix, env,
    )
    require_success(result)
    expected = las_point_count(source)
    selected = (expected + step - 1) // step
    stats = json.loads(metadata.read_text())
    points = (stats["pointsRead"], stats["pointsSelected"], stats["pointsWritten"])
    observed = {item["name"]: item["count"] for item in stats["statistics"][0]["dimensions"]}
    expected_counts = dict.fromkeys(dimensions, selected if modern else expected)
    if points != (expected, selected, selected) or observed != expected_counts or las_point_count(las) != selected:
        raise RuntimeError(f"Training run did not process the complete input. See {metadata}")
    with profile.open("rb") as file:
        compressed = file.read(2) == b"\x1f\x8b"
    with (gzip.open if compressed else open)(profile, "rt", encoding="utf-8") as file:
        if not isinstance(json.load(file), dict):
            raise RuntimeError(f"Expected a JSON object in {profile}")
    artifacts = entry["artifacts"] = {
        "profile": file_details(profile), "metadata": file_details(metadata), "las": file_details(las),
    }
    signatures = (artifacts["metadata"]["sha256"], artifacts["las"]["sha256"])
    if reference.setdefault(name, signatures) != signatures:
        raise RuntimeError(f"Training output changed across collectors or worker counts. See {metadata}")
    entry["points"] = {"read": expected, "selected": selected, "written": selected}
    return profile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_dir", type=Path, help="working directory containing both training LAZ files")
    parser.add_argument("--graal-home", type=Path, default=DEFAULT_GRAAL)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--preflight-only", action="store_true", help="compile and run collector probes only")
    mode.add_argument("--resume", action="store_true", help="reuse completed probes and untrained builds")
    args = parser.parse_args()
    run_dir = args.run_dir.resolve()
    run_dir.mkdir(parents=True, exist_ok=True)
    graal = args.graal_home.resolve()
    env = {**os.environ, "JAVA_HOME": str(graal)}
    manifest_file = run_dir / ("gc-preflight.json" if args.preflight_only else "native-build.json")
    previous = json.loads(manifest_file.read_text()) if args.resume else {}
    manifest = {"host": platform.platform(), "graal_home": str(graal), "collectors": {}, "status": "running"}
    try:
        verify_toolchain(graal, run_dir, env, manifest)
        if not args.preflight_only:
            jars = sorted((ROOT / "build/install/pointcloud/lib").glob("*.jar"))
            if not jars:
                raise RuntimeError("Install the product distribution with ./gradlew --offline installDist")
            classpath = os.pathsep.join(map(str, jars))
            manifest["classpath"] = [file_details(jar) for jar in jars]
            manifest["reachability"] = file_details(REACHABILITY / "reachability-metadata.json")
            inputs = {name: run_dir / filename for name, filename in DATASETS.items()}
            manifest["inputs"] = {name: file_details(source) for name, source in inputs.items()}
        if previous and any(previous.get(key) != manifest[key] for key in (
            "host", "graal_home", "graal_release", "classpath", "reachability", "inputs",
        )):
            raise RuntimeError("Cannot resume after changes to the host, toolchain, classpath or inputs.")
        saved_collectors = previous.get("collectors", {})
        if all(saved_collectors.get(gc, {}).get("status") in {"available", "unsupported"}
               for gc in ("G1", "serial")):
            manifest["probe_javac"] = previous["probe_javac"]
            manifest["collectors"] = {
                gc: {key: value for key, value in entry.items() if key not in {"builds", "training"}}
                for gc, entry in saved_collectors.items()
            }
            print("Reusing completed collector probes.", flush=True)
        else:
            preflight(graal, run_dir, env, manifest)
        if not args.preflight_only:
            reference = {}
            for gc, collector in manifest["collectors"].items():
                if collector["status"] != "available":
                    continue
                saved_builds = saved_collectors.get(gc, {}).get("builds", {})
                builds = collector["builds"] = {"plain": {}, "instrumented": {}, "pgo": {}}
                build(graal, run_dir, env, classpath, gc, "", builds["plain"],
                      previous=saved_builds.get("plain"))
                instrumented = build(graal, run_dir, env, classpath, gc, "-instrumented",
                                     builds["instrumented"], ("--pgo-instrument",),
                                     previous=saved_builds.get("instrumented"))
                training = collector["training"] = {}
                profiles = []
                for name, source in inputs.items():
                    for workers in (1, 4):
                        entry = training[f"{name}-w{workers}"] = {}
                        profiles.append(train(instrumented, run_dir, env, gc, name, source, workers,
                                              entry, reference))
                build(graal, run_dir, env, classpath, gc, "-pgo", builds["pgo"],
                      ("--pgo=" + ",".join(map(str, profiles)),))
                write_json(manifest_file, manifest)
        manifest["status"] = "complete"
    except (KeyError, OSError, RuntimeError, ValueError) as error:
        manifest.update(status="failed", error=str(error))
        raise SystemExit(str(error)) from error
    except KeyboardInterrupt:
        manifest["status"] = "interrupted"
        raise
    finally:
        write_json(manifest_file, manifest)


if __name__ == "__main__":
    main()
