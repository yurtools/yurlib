#!/usr/bin/env python3
"""Create, verify, and restore Yurlib M2 backups."""

from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
from typing import BinaryIO, Iterable
import xml.etree.ElementTree as ElementTree


MANIFEST_VERSION = 1
ROOT_ID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
SAFE_DB_NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
EXPECTED_TOP_LEVEL = {"database.dump", "lineage.csv", "managed", "manifest.json", "roots.csv"}


class RecoveryError(RuntimeError):
    """An operator-correctable backup or restore failure."""


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def checked_relative_path(value: str) -> PurePosixPath:
    path = PurePosixPath(value)
    if not value or path.is_absolute() or "\\" in value or any(part in ("", ".", "..") for part in path.parts):
        raise RecoveryError(f"Unsafe managed path in manifest: {value!r}")
    return path


def regular_tree_entries(root: Path) -> list[dict[str, object]]:
    if not root.is_dir() or root.is_symlink():
        raise RecoveryError(f"Managed root must be a real directory: {root}")
    entries: list[dict[str, object]] = []
    for current, directories, files in os.walk(root, followlinks=False):
        current_path = Path(current)
        for name in sorted(directories + files):
            source = current_path / name
            metadata = source.lstat()
            relative = source.relative_to(root).as_posix()
            checked_relative_path(relative)
            mode = stat.S_IMODE(metadata.st_mode)
            if stat.S_ISLNK(metadata.st_mode):
                raise RecoveryError(f"Managed roots must not contain symbolic links: {source}")
            if stat.S_ISDIR(metadata.st_mode):
                entries.append({"path": relative, "type": "directory", "mode": mode})
            elif stat.S_ISREG(metadata.st_mode):
                entries.append(
                    {
                        "path": relative,
                        "type": "file",
                        "mode": mode,
                        "size": metadata.st_size,
                        "sha256": sha256(source),
                    }
                )
            else:
                raise RecoveryError(f"Managed roots must contain only regular files and directories: {source}")
    return sorted(entries, key=lambda entry: str(entry["path"]))


def copy_regular_tree(source: Path, destination: Path) -> None:
    destination.mkdir(mode=stat.S_IMODE(source.stat().st_mode))
    destination.chmod(stat.S_IMODE(source.stat().st_mode))
    for entry in regular_tree_entries(source):
        relative = checked_relative_path(str(entry["path"]))
        target = destination.joinpath(*relative.parts)
        source_path = source.joinpath(*relative.parts)
        if entry["type"] == "directory":
            target.mkdir(mode=int(entry["mode"]))
            target.chmod(int(entry["mode"]))
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            with source_path.open("rb") as input_stream, target.open("xb") as output_stream:
                shutil.copyfileobj(input_stream, output_stream, 1024 * 1024)
            target.chmod(int(entry["mode"]))


def parse_root_mappings(values: Iterable[str], *, require_absent: bool) -> dict[str, Path]:
    mappings: dict[str, Path] = {}
    for value in values:
        root_id, separator, raw_path = value.partition("=")
        if not separator or not ROOT_ID.fullmatch(root_id):
            raise RecoveryError("Managed roots must use UUID=/absolute/path syntax")
        path = Path(raw_path)
        if not path.is_absolute():
            raise RecoveryError(f"Managed root path must be absolute: {path}")
        if root_id in mappings:
            raise RecoveryError(f"Duplicate managed root mapping: {root_id}")
        if require_absent and path.exists():
            raise RecoveryError(f"Restore target must not exist: {path}")
        if not require_absent and (not path.is_dir() or path.is_symlink()):
            raise RecoveryError(f"Backup source must be a real directory: {path}")
        mappings[root_id] = path
    if not mappings:
        raise RecoveryError("At least one --managed-root mapping is required")
    return mappings


def compose_command(args: argparse.Namespace, *command: str) -> list[str]:
    result = ["docker", "compose"]
    if args.compose_file:
        result.extend(["--file", str(args.compose_file)])
    result.extend(command)
    return result


def run(
    command: list[str],
    *,
    stdout: BinaryIO | int | None = None,
    stdin: BinaryIO | None = None,
    input_bytes: bytes | None = None,
) -> bytes:
    try:
        completed = subprocess.run(
            command,
            check=True,
            stdin=stdin,
            input=input_bytes,
            stdout=stdout if stdout is not None else subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except FileNotFoundError as error:
        raise RecoveryError(f"Required command is unavailable: {command[0]}") from error
    except subprocess.CalledProcessError as error:
        diagnostic = error.stderr.decode("utf-8", errors="replace").strip()
        raise RecoveryError(f"Command failed: {' '.join(command)}\n{diagnostic}") from error
    return completed.stdout if completed.stdout is not None else b""


def database_identity(args: argparse.Namespace) -> tuple[str, str]:
    username = os.environ.get("YURLIB_DB_USERNAME", "yurlib")
    database = os.environ.get("YURLIB_DB_NAME", "yurlib")
    if not SAFE_DB_NAME.fullmatch(username) or not SAFE_DB_NAME.fullmatch(database):
        raise RecoveryError("YURLIB_DB_USERNAME and YURLIB_DB_NAME must be simple PostgreSQL identifiers")
    return username, database


def postgres(args: argparse.Namespace, *command: str, stdin: BinaryIO | None = None) -> bytes:
    return run(compose_command(args, "exec", "-T", "postgres", *command), stdin=stdin)


def require_quiescent_application(args: argparse.Namespace) -> None:
    running = run(compose_command(args, "ps", "--status", "running", "--services")).decode().splitlines()
    active = sorted({"server", "document-worker"}.intersection(running))
    if active:
        raise RecoveryError(f"Stop application services before recovery work: {', '.join(active)}")
    if "postgres" not in running:
        raise RecoveryError("The postgres Compose service must be running")
    username, database = database_identity(args)
    for _ in range(30):
        try:
            postgres(args, "pg_isready", f"--username={username}", f"--dbname={database}")
            break
        except RecoveryError:
            time.sleep(1)
    else:
        raise RecoveryError("The postgres Compose service did not become ready within 30 seconds")


def psql(args: argparse.Namespace, sql: str) -> bytes:
    username, database = database_identity(args)
    return postgres(
        args,
        "psql",
        "--no-psqlrc",
        "--set=ON_ERROR_STOP=1",
        f"--username={username}",
        f"--dbname={database}",
        "--tuples-only",
        "--no-align",
        "--command",
        sql,
    )


ROOT_INVENTORY_SQL = """
COPY (
    SELECT id, mount_alias, relative_base_path, expected_identity_digest,
           availability, default_for_covers, default_for_conversions
    FROM library_root
    WHERE mode = 'MANAGED_OUTPUT'
    ORDER BY id
) TO STDOUT WITH (FORMAT CSV, HEADER TRUE)
"""

LINEAGE_SQL = """
COPY (
    SELECT 'DERIVATION' AS record_type, derived_asset_id AS subject_id,
           source_asset_id AS related_id, purpose AS detail,
           source_ordinal::text AS provenance
    FROM asset_derivation_source
    UNION ALL
    SELECT 'LOCATION', location.asset_id, location.library_root_id,
           location.normalized_relative_path, root.mode
    FROM asset_location location
    JOIN library_root root ON root.id = location.library_root_id
    ORDER BY 1, 2, 3
) TO STDOUT WITH (FORMAT CSV, HEADER TRUE)
"""


def inventory_rows(payload: bytes) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO(payload.decode("utf-8"))))


def atomic_destination(destination: Path) -> tuple[Path, Path]:
    destination = destination.absolute()
    if destination.exists():
        raise RecoveryError(f"Destination already exists: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{destination.name}-", dir=destination.parent))
    temporary.chmod(0o700)
    return destination, temporary


def create_backup(args: argparse.Namespace) -> None:
    require_quiescent_application(args)
    mappings = parse_root_mappings(args.managed_root, require_absent=False)
    destination, temporary = atomic_destination(args.destination)
    try:
        inventory = psql(args, ROOT_INVENTORY_SQL)
        rows = inventory_rows(inventory)
        database_root_ids = {row["id"] for row in rows}
        if database_root_ids != set(mappings):
            raise RecoveryError(
                "Managed root mappings do not match PostgreSQL inventory: "
                f"database={sorted(database_root_ids)}, supplied={sorted(mappings)}"
            )
        (temporary / "roots.csv").write_bytes(inventory)
        (temporary / "lineage.csv").write_bytes(psql(args, LINEAGE_SQL))
        username, database = database_identity(args)
        with (temporary / "database.dump").open("xb") as dump:
            run(
                compose_command(
                    args,
                    "exec",
                    "-T",
                    "postgres",
                    "pg_dump",
                    f"--username={username}",
                    f"--dbname={database}",
                    "--format=custom",
                    "--no-owner",
                    "--no-privileges",
                ),
                stdout=dump,
            )
        managed = temporary / "managed"
        managed.mkdir()
        roots: list[dict[str, object]] = []
        row_by_id = {row["id"]: row for row in rows}
        for root_id, source in sorted(mappings.items()):
            copied = managed / root_id
            copy_regular_tree(source, copied)
            roots.append({**row_by_id[root_id], "entries": regular_tree_entries(copied)})
        schema_version = psql(
            args, "SELECT metadata_value FROM yurlib_metadata WHERE metadata_key = 'schema_version'"
        ).decode().strip()
        commit = run(["git", "rev-parse", "HEAD"]).decode().strip()
        manifest = {
            "format": "yurlib-recovery",
            "version": MANIFEST_VERSION,
            "createdAt": datetime.now(timezone.utc).isoformat(),
            "schemaVersion": schema_version,
            "applicationVersion": application_version(),
            "applicationCommit": commit,
            "database": file_record(temporary / "database.dump"),
            "rootInventory": file_record(temporary / "roots.csv"),
            "lineage": file_record(temporary / "lineage.csv"),
            "managedRoots": roots,
        }
        (temporary / "manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        verify_backup(temporary)
        temporary.rename(destination)
    except BaseException:
        shutil.rmtree(temporary, ignore_errors=True)
        raise
    print(f"Backup created and verified: {destination}")


def file_record(path: Path) -> dict[str, object]:
    return {"file": path.name, "size": path.stat().st_size, "sha256": sha256(path)}


def application_version() -> str:
    configured = os.environ.get("YURLIB_APPLICATION_VERSION", "").strip()
    if configured:
        return configured
    pom = Path(__file__).resolve().parents[1] / "pom.xml"
    try:
        root = ElementTree.parse(pom).getroot()
    except (OSError, ElementTree.ParseError) as error:
        raise RecoveryError("Set YURLIB_APPLICATION_VERSION when pom.xml is unavailable") from error
    namespace = root.tag.removesuffix("project")
    version = root.findtext(f"{namespace}version", "").strip()
    if not version:
        raise RecoveryError("The Yurlib application version is unavailable")
    return version


def load_manifest(backup: Path) -> dict[str, object]:
    try:
        manifest = json.loads((backup / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RecoveryError("Backup manifest is missing or invalid") from error
    if manifest.get("format") != "yurlib-recovery" or manifest.get("version") != MANIFEST_VERSION:
        raise RecoveryError("Unsupported backup manifest format or version")
    try:
        created_at = datetime.fromisoformat(str(manifest["createdAt"]))
    except (KeyError, ValueError) as error:
        raise RecoveryError("Backup manifest has an invalid creation time") from error
    if created_at.utcoffset() is None:
        raise RecoveryError("Backup manifest creation time must include a UTC offset")
    if not str(manifest.get("schemaVersion", "")).isdigit():
        raise RecoveryError("Backup manifest has an invalid schema version")
    if not str(manifest.get("applicationVersion", "")).strip():
        raise RecoveryError("Backup manifest has no application version")
    if not re.fullmatch(r"[0-9a-f]{40,64}", str(manifest.get("applicationCommit", ""))):
        raise RecoveryError("Backup manifest has an invalid application commit")
    return manifest


def verify_file_record(backup: Path, record: object, expected_name: str) -> None:
    if not isinstance(record, dict) or record.get("file") != expected_name:
        raise RecoveryError(f"Invalid {expected_name} manifest record")
    path = backup / expected_name
    if not path.is_file() or path.is_symlink():
        raise RecoveryError(f"Backup file is missing or unsafe: {expected_name}")
    if path.stat().st_size != record.get("size") or sha256(path) != record.get("sha256"):
        raise RecoveryError(f"Backup file failed integrity verification: {expected_name}")


def verify_backup(backup: Path) -> dict[str, object]:
    if not backup.is_dir() or backup.is_symlink():
        raise RecoveryError(f"Backup must be a real directory: {backup}")
    actual_top_level = {entry.name for entry in backup.iterdir()}
    if actual_top_level != EXPECTED_TOP_LEVEL:
        raise RecoveryError(f"Unexpected backup entries: {sorted(actual_top_level ^ EXPECTED_TOP_LEVEL)}")
    if stat.S_IMODE(backup.stat().st_mode) & 0o077:
        raise RecoveryError("Backup directory must not grant group or world permissions")
    manifest_path = backup / "manifest.json"
    if not manifest_path.is_file() or manifest_path.is_symlink():
        raise RecoveryError("Backup manifest is missing or unsafe")
    manifest = load_manifest(backup)
    verify_file_record(backup, manifest.get("database"), "database.dump")
    verify_file_record(backup, manifest.get("rootInventory"), "roots.csv")
    verify_file_record(backup, manifest.get("lineage"), "lineage.csv")
    roots = manifest.get("managedRoots")
    if not isinstance(roots, list) or not roots:
        raise RecoveryError("Manifest must contain managed roots")
    expected_root_ids: set[str] = set()
    managed = backup / "managed"
    if not managed.is_dir() or managed.is_symlink():
        raise RecoveryError("Managed backup directory is missing or unsafe")
    for root in roots:
        if not isinstance(root, dict) or not ROOT_ID.fullmatch(str(root.get("id", ""))):
            raise RecoveryError("Manifest contains an invalid managed root")
        root_id = str(root["id"])
        if root_id in expected_root_ids:
            raise RecoveryError(f"Manifest contains duplicate managed root: {root_id}")
        expected_root_ids.add(root_id)
        root_path = managed / root_id
        manifest_entries = root.get("entries")
        if not isinstance(manifest_entries, list):
            raise RecoveryError(f"Managed root has no entry manifest: {root_id}")
        for entry in manifest_entries:
            if not isinstance(entry, dict) or entry.get("type") not in {"file", "directory"}:
                raise RecoveryError(f"Managed root has an invalid entry: {root_id}")
            checked_relative_path(str(entry.get("path", "")))
        actual_entries = regular_tree_entries(root_path)
        if actual_entries != manifest_entries:
            raise RecoveryError(f"Managed root failed exact integrity verification: {root_id}")
    actual_root_ids = {entry.name for entry in managed.iterdir()}
    if actual_root_ids != expected_root_ids:
        raise RecoveryError(f"Unexpected managed roots: {sorted(actual_root_ids ^ expected_root_ids)}")
    return manifest


RESTORE_RECONCILIATION_SQL = """
BEGIN;
UPDATE library_root SET availability = 'UNAVAILABLE', updated_at = CURRENT_TIMESTAMP
WHERE mode = 'READ_ONLY_SOURCE';
UPDATE asset_location SET availability = 'UNAVAILABLE', updated_at = CURRENT_TIMESTAMP
WHERE library_root_id IN (SELECT id FROM library_root WHERE mode = 'READ_ONLY_SOURCE');
UPDATE scan_job SET state = 'CANCELLED', completed_at = CURRENT_TIMESTAMP,
    error_summary = 'Interrupted by backup restore; revalidate the source root before rescanning'
WHERE state IN ('QUEUED', 'RUNNING');
UPDATE ingestion_task SET state = 'CANCELLED', lease_token = NULL, lease_expires_at = NULL,
    heartbeat_at = NULL, completed_at = CURRENT_TIMESTAMP, error_code = 'RESTORE_INTERRUPTED'
WHERE state IN ('QUEUED', 'RUNNING');
UPDATE pdf_metadata_job SET state = 'FAILED_SAFE', lease_token = NULL, lease_expires_at = NULL,
    completed_at = CURRENT_TIMESTAMP, error_code = 'RESTORE_INTERRUPTED',
    safe_diagnostic = 'Staging is not backed up; rescan after source revalidation'
WHERE state IN ('QUEUED', 'RUNNING');
UPDATE cover_job SET state = 'FAILED_SAFE', lease_token = NULL, lease_expires_at = NULL,
    completed_at = CURRENT_TIMESTAMP, error_code = 'RESTORE_INTERRUPTED',
    safe_diagnostic = 'Staging is not backed up; request the cover again'
WHERE state IN ('QUEUED', 'RUNNING');
UPDATE conversion_job SET state = 'FAILED_SAFE', lease_token = NULL, lease_expires_at = NULL,
    heartbeat_at = NULL, staged_output_name = NULL, uploaded_output_sha256 = NULL,
    uploaded_output_byte_size = NULL, completed_at = CURRENT_TIMESTAMP,
    error_code = 'RESTORE_INTERRUPTED', safe_diagnostic = 'Staging is not backed up; request conversion again',
    version = version + 1
WHERE state IN ('QUEUED', 'RUNNING');
COMMIT;
"""


def restore_backup(args: argparse.Namespace) -> None:
    require_quiescent_application(args)
    backup = args.backup.absolute()
    manifest = verify_backup(backup)
    mappings = parse_root_mappings(args.managed_root, require_absent=True)
    manifest_ids = {str(root["id"]) for root in manifest["managedRoots"]}
    if set(mappings) != manifest_ids:
        raise RecoveryError(
            f"Managed root mappings do not match manifest: backup={sorted(manifest_ids)}, supplied={sorted(mappings)}"
        )
    table_count = int(psql(
        args,
        "SELECT count(*) FROM pg_catalog.pg_tables "
        "WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
    ).decode().strip())
    if table_count:
        raise RecoveryError("Restore requires an empty PostgreSQL database")
    staged_targets: dict[Path, Path] = {}
    try:
        for root_id, target in mappings.items():
            target.parent.mkdir(parents=True, exist_ok=True)
            staged = Path(tempfile.mkdtemp(prefix=f".{target.name}-restore-", dir=target.parent))
            staged.rmdir()
            copy_regular_tree(backup / "managed" / root_id, staged)
            staged_targets[target] = staged
        username, database = database_identity(args)
        with (backup / "database.dump").open("rb") as dump:
            postgres(
                args,
                "pg_restore",
                f"--username={username}",
                f"--dbname={database}",
                "--exit-on-error",
                "--single-transaction",
                "--no-owner",
                "--no-privileges",
                stdin=dump,
            )
        psql(args, RESTORE_RECONCILIATION_SQL)
        for target, staged in staged_targets.items():
            staged.rename(target)
        staged_targets.clear()
    finally:
        for staged in staged_targets.values():
            shutil.rmtree(staged, ignore_errors=True)
    print("Restore completed; revalidate every read-only source root before scanning")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--compose-file", type=Path, help="Compose file; defaults to compose.yaml discovery")
    commands = result.add_subparsers(dest="command", required=True)
    backup = commands.add_parser("backup", help="create a quiescent backup")
    backup.add_argument("destination", type=Path)
    backup.add_argument("--managed-root", action="append", default=[], metavar="UUID=/ABSOLUTE/PATH")
    verify = commands.add_parser("verify", help="verify a backup without changing state")
    verify.add_argument("backup", type=Path)
    restore = commands.add_parser("restore", help="restore into an empty deployment")
    restore.add_argument("backup", type=Path)
    restore.add_argument("--managed-root", action="append", default=[], metavar="UUID=/ABSOLUTE/PATH")
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "backup":
            create_backup(args)
        elif args.command == "verify":
            verify_backup(args.backup.absolute())
            print(f"Backup verified: {args.backup.absolute()}")
        else:
            restore_backup(args)
    except RecoveryError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
