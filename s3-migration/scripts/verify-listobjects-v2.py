#!/usr/bin/env python3
"""
Verify that all current source keys exist in the destination bucket.

This script uses ListObjectsV2, writes percent-encoded key lists to disk,
externally sorts them, and produces:
  - missing-in-destination.txt: source current keys absent from destination
  - extra-in-destination.txt: destination keys absent from source

Use --exclude-dst-prefix to skip operational prefixes (e.g. migration-logs/)
that live in the destination bucket alongside migrated objects.

If the source CSE envelope uses .instruction sidecar objects, pass
--exclude-src-suffix .instruction and --exclude-dst-suffix .instruction so
sidecar files are not treated as business objects.

It verifies current object keys only. It does not verify source version history;
use ListObjectVersions for that if preserving every historical version matters.

Note: source-side deletions are not propagated to the destination, so
extra-in-destination.txt is expected to be non-empty and is not a failure.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Iterable
from urllib.parse import quote

try:
    import boto3
except ImportError:
    print("boto3 is required: python3 -m pip install boto3", file=sys.stderr)
    sys.exit(4)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Compare source and destination bucket current keys with ListObjectsV2."
    )
    parser.add_argument("--src-bucket", required=True)
    parser.add_argument("--dst-bucket", required=True)
    parser.add_argument("--src-prefix", default="")
    parser.add_argument("--dst-prefix", default="")
    parser.add_argument(
        "--exclude-dst-prefix",
        default="migration-logs/",
        help="Skip destination keys that start with this prefix (default: migration-logs/).",
    )
    parser.add_argument(
        "--exclude-src-suffix",
        default="",
        help="Skip source keys that end with this suffix, e.g. .instruction.",
    )
    parser.add_argument(
        "--exclude-dst-suffix",
        default="",
        help="Skip destination keys that end with this suffix, e.g. .instruction.",
    )
    parser.add_argument("--workdir", default="./verify-work")
    parser.add_argument("--profile")
    parser.add_argument("--region")
    parser.add_argument("--page-size", type=int, default=1000)
    parser.add_argument("--sort-mem", default="20G")
    parser.add_argument("--sort-parallel", type=int, default=os.cpu_count() or 1)
    return parser.parse_args()


def session_for(args: argparse.Namespace) -> boto3.session.Session:
    return boto3.Session(profile_name=args.profile, region_name=args.region)


def list_keys(
    session: boto3.session.Session,
    bucket: str,
    prefix: str,
    exclude_prefix: str,
    exclude_suffix: str,
    page_size: int,
    out_path: Path,
) -> int:
    client = session.client("s3")
    paginator = client.get_paginator("list_objects_v2")
    count = 0

    with out_path.open("w", encoding="utf-8", newline="\n") as out:
        for page in paginator.paginate(
            Bucket=bucket,
            Prefix=prefix,
            PaginationConfig={"PageSize": page_size},
        ):
            for obj in page.get("Contents", []):
                if exclude_prefix and obj["Key"].startswith(exclude_prefix):
                    continue
                if exclude_suffix and obj["Key"].endswith(exclude_suffix):
                    continue
                # RFC3986 percent-encode so unusual keys remain one record per line.
                out.write(quote(obj["Key"], safe="/") + "\n")
                count += 1
                if count % 100000 == 0:
                    print(f"{bucket}: listed {count} keys", file=sys.stderr)

    print(f"{bucket}: listed {count} keys total", file=sys.stderr)
    return count


def run(command: Iterable[str], env: dict[str, str] | None = None) -> None:
    print("+ " + " ".join(command), file=sys.stderr)
    result = subprocess.run(list(command), env=env)
    if result.returncode != 0:
        raise subprocess.CalledProcessError(result.returncode, command)


def sort_unique(raw_path: Path, sorted_path: Path, tmp_dir: Path, args: argparse.Namespace) -> None:
    env = os.environ.copy()
    env["LC_ALL"] = "C"
    base_cmd = [
        "sort", "-u",
        "-S", args.sort_mem,
        "-T", str(tmp_dir),
        "-o", str(sorted_path),
        str(raw_path),
    ]
    try:
        run(["sort", "-u", "-S", args.sort_mem,
             "--parallel", str(args.sort_parallel),
             "-T", str(tmp_dir), "-o", str(sorted_path), str(raw_path)], env=env)
    except subprocess.CalledProcessError:
        # BSD sort does not support --parallel; fall back for local dry runs.
        run(base_cmd, env=env)


def comm(left: Path, right: Path, out_path: Path, flags: str) -> int:
    env = os.environ.copy()
    env["LC_ALL"] = "C"
    with out_path.open("w", encoding="utf-8", newline="\n") as out:
        print(f"+ comm {flags} {left} {right} > {out_path}", file=sys.stderr)
        subprocess.run(["comm", flags, str(left), str(right)], check=True, stdout=out, env=env)

    with out_path.open("r", encoding="utf-8") as result:
        return sum(1 for _ in result)


def main() -> int:
    args = parse_args()

    if not shutil.which("sort") or not shutil.which("comm"):
        print("sort and comm must be installed", file=sys.stderr)
        return 4

    workdir = Path(args.workdir)
    tmp_dir = workdir / "tmp"
    workdir.mkdir(parents=True, exist_ok=True)
    tmp_dir.mkdir(parents=True, exist_ok=True)

    src_raw = workdir / "source.keys"
    dst_raw = workdir / "destination.keys"
    src_sorted = workdir / "source.sorted"
    dst_sorted = workdir / "destination.sorted"
    missing = workdir / "missing-in-destination.txt"
    extra = workdir / "extra-in-destination.txt"

    session = session_for(args)
    src_count = list_keys(session, args.src_bucket, args.src_prefix,
                          "", args.exclude_src_suffix, args.page_size, src_raw)
    dst_count = list_keys(session, args.dst_bucket, args.dst_prefix,
                          args.exclude_dst_prefix, args.exclude_dst_suffix,
                          args.page_size, dst_raw)

    sort_unique(src_raw, src_sorted, tmp_dir, args)
    sort_unique(dst_raw, dst_sorted, tmp_dir, args)

    missing_count = comm(src_sorted, dst_sorted, missing, "-23")
    extra_count = comm(src_sorted, dst_sorted, extra, "-13")

    print(f"source_current_keys={src_count}")
    print(f"destination_current_keys={dst_count}")
    print(f"missing_in_destination={missing_count} path={missing}")
    print(f"extra_in_destination={extra_count} path={extra}")

    if missing_count > 0:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
