#!/usr/bin/env python3
"""Repeatable local MCP latency probe. All index/project state lives in a temporary directory."""

import argparse
import json
import math
import os
from pathlib import Path
import platform
import queue
import statistics
import subprocess
import tempfile
import threading
import time


class Mcp:
    def __init__(self, command, project, env, log):
        self.process = subprocess.Popen(
            command + ["mcp"], cwd=project, env=env, stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=log, text=True, bufsize=1,
        )
        self.messages = queue.Queue()
        self.next_id = 0

        def read():
            for line in self.process.stdout:
                self.messages.put(json.loads(line))
            self.messages.put(None)

        self.reader = threading.Thread(target=read, daemon=True)
        self.reader.start()
        self.request("initialize", {
            "protocolVersion": "2024-11-05", "capabilities": {},
            "clientInfo": {"name": "research4jar-benchmark", "version": "1"},
        })

    def request(self, method, params):
        self.next_id += 1
        request = {"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": params}
        started = time.perf_counter_ns()
        self.process.stdin.write(json.dumps(request) + "\n")
        self.process.stdin.flush()
        result = self.messages.get(timeout=60)
        elapsed = (time.perf_counter_ns() - started) / 1_000_000
        if result is None or result.get("error") or result.get("result", {}).get("isError"):
            raise RuntimeError(result)
        if result.get("id") != self.next_id:
            raise RuntimeError(f"unexpected response: {result}")
        return elapsed, result["result"]

    def close(self):
        self.process.stdin.close()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
        self.reader.join(timeout=2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cli", type=Path, help="fat CLI jar to measure")
    parser.add_argument("--jars", required=True, help="absolute jar directory, glob, or comma-separated list")
    parser.add_argument("--class-name", required=True, help="fully qualified class present in those jars")
    parser.add_argument("--source-files", type=int, default=2105)
    parser.add_argument("--samples", type=int, default=30)
    parser.add_argument("--warmup", type=int, default=5)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.samples < 2 or args.warmup < 0 or args.source_files < 0:
        parser.error("samples must be >= 2; warmup and source-files must be non-negative")
    command = ["java", "-Xmx512m", "-jar", str(args.cli.resolve())]
    report = {
        "platform": platform.platform(), "architecture": platform.machine(),
        "java": subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr.splitlines()[0],
        "class_name": args.class_name, "source_files": args.source_files,
        "samples": args.samples, "warmup": args.warmup,
    }
    with tempfile.TemporaryDirectory(prefix="research4jar-benchmark-") as directory:
        root = Path(directory)
        project = root / "project"
        source = project / "src/main/java/probe"
        source.mkdir(parents=True)
        env = dict(os.environ, RESEARCH4JAR_HOME=str(root / "data"), RESEARCH4JAR_NO_DAEMON="1")
        started = time.perf_counter_ns()
        indexed = subprocess.run(
            command + ["index", "--jars", args.jars, "--project-dir", str(project)],
            cwd=project, env=env, capture_output=True, text=True, timeout=300,
        )
        if indexed.returncode:
            raise RuntimeError(indexed.stdout + indexed.stderr)
        report["cold_index_wall_ms"] = round((time.perf_counter_ns() - started) / 1_000_000, 3)
        report["index"] = json.loads(indexed.stdout)
        for number in range(args.source_files):
            (source / f"Probe{number:04d}.java").write_text(
                f"package probe;\npublic class Probe{number} {{\n" +
                "// ordinary unrelated source text\n" * 200 + "}\n",
            )
        with (root / "mcp.log").open("w") as log:
            client = Mcp(command, project, env, log)
            try:
                cases = {
                    "find_class": ("find_class", {"text": args.class_name}),
                    "missing_symbol": ("search_symbols", {"text": "ZXQMissingResearch4JarProbe"}),
                    "dependency_without_source": ("dependency_precise", {"text": args.class_name, "no_source_grep": True}),
                    "dependency_with_source": ("dependency_precise", {"text": args.class_name}),
                }
                report["queries"] = {}
                for label, (name, arguments) in cases.items():
                    values = []
                    for number in range(args.warmup + args.samples):
                        elapsed, result = client.request("tools/call", {"name": name, "arguments": arguments})
                        if number >= args.warmup:
                            values.append(elapsed)
                    payload = json.loads(result["content"][0]["text"])
                    if label.startswith("dependency_") and not payload.get("origins"):
                        raise RuntimeError("class-name did not resolve to a jar; choose a class in --jars")
                    report["queries"][label] = {
                        "p50_ms": round(statistics.median(values), 3),
                        "p95_ms": round(sorted(values)[math.ceil(len(values) * .95) - 1], 3),
                        "samples_ms": [round(value, 3) for value in values],
                        "source_usages_truncated_reason": payload.get("source_usages_truncated_reason", ""),
                    }
                # Verify freshness through the public tool, after warming its negative-result cache.
                changed = source / "Freshness.java"
                changed.write_text(f"import {args.class_name};\n")
                _, result = client.request("tools/call", {"name": "dependency_precise", "arguments": {"text": args.class_name}})
                payload = json.loads(result["content"][0]["text"])
                report["new_source_visible"] = any(hit["path"].endswith("Freshness.java") for hit in payload.get("source_usages", []))
                if not report["new_source_visible"]:
                    raise RuntimeError("new source file was invisible after cache warmup")
            finally:
                client.close()
    encoded = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.write_text(encoded)
    print(encoded, end="")


if __name__ == "__main__":
    main()
