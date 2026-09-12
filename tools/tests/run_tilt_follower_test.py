#!/usr/bin/env python3
"""Offline regression test using Java and Kotlin jars already in Gradle's cache.

Run: python3 tools/tests/run_tilt_follower_test.py
Build the app once first to populate the Kotlin compiler cache.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile


def latest_jar(directory):
    jars = [p for p in directory.glob("*/*/*.jar")
            if not p.name.endswith(("-all.jar", "-sources.jar", "-javadoc.jar"))]
    if not jars:
        raise SystemExit(f"Missing cached Kotlin dependency: {directory}. Build the app first.")
    return max(jars, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.parents[1].name)))


here = Path(__file__).resolve().parent
root = here.parents[1]
cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"
compiler = latest_jar(cache / "org.jetbrains.kotlin/kotlin-compiler-embeddable")
version = compiler.parents[1].name
jars = [compiler]
for artifact in ("kotlin-stdlib", "kotlin-script-runtime", "kotlin-daemon-embeddable", "kotlin-reflect"):
    matches = [p for p in (cache / "org.jetbrains.kotlin" / artifact / version).glob("*/*.jar")
               if not p.name.endswith("-all.jar")]
    jars.append(matches[0] if matches else latest_jar(cache / "org.jetbrains.kotlin" / artifact))
for artifact in ("org.jetbrains.intellij.deps/trove4j", "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm", "org.jetbrains/annotations"):
    jars.append(latest_jar(cache / artifact))
classpath = os.pathsep.join(map(str, jars))
java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"
with tempfile.TemporaryDirectory(prefix="duo-follower-test-") as temp:
    subprocess.run([java, "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-classpath", classpath, "-d", temp,
                    str(here / "FakeChoreographer.kt"), str(here / "TiltFollowerTest.kt"),
                    str(root / "app/src/main/java/com/duoopen/fold/TiltFollower.kt")], check=True)
    subprocess.run([java, "-cp", temp + os.pathsep + classpath, "TiltFollowerTestKt"], check=True)
