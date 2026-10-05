import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import yurlib_recovery as recovery


ROOT_ID = "d1fb3706-262d-42d4-a8c6-b9e49663464d"


class RecoveryManifestTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.backup = Path(self.temporary.name) / "backup"
        managed = self.backup / "managed" / ROOT_ID / "covers" / "ab"
        managed.mkdir(parents=True)
        self.backup.chmod(0o700)
        (managed / "cover.jpg").write_bytes(b"verified-cover")
        for name, content in {
            "database.dump": b"database",
            "roots.csv": b"id\n" + ROOT_ID.encode() + b"\n",
            "lineage.csv": b"record_type\nDERIVATION\n",
        }.items():
            (self.backup / name).write_bytes(content)
        manifest = {
            "format": "yurlib-recovery",
            "version": recovery.MANIFEST_VERSION,
            "createdAt": "2026-10-03T00:00:00+00:00",
            "schemaVersion": "12",
            "applicationVersion": "0.1.0-SNAPSHOT",
            "applicationCommit": "0" * 40,
            "database": recovery.file_record(self.backup / "database.dump"),
            "rootInventory": recovery.file_record(self.backup / "roots.csv"),
            "lineage": recovery.file_record(self.backup / "lineage.csv"),
            "managedRoots": [
                {
                    "id": ROOT_ID,
                    "mount_alias": "managed",
                    "relative_base_path": ".",
                    "expected_identity_digest": "0" * 64,
                    "availability": "AVAILABLE",
                    "default_for_covers": "t",
                    "default_for_conversions": "t",
                    "entries": recovery.regular_tree_entries(self.backup / "managed" / ROOT_ID),
                }
            ],
        }
        (self.backup / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def test_verifies_an_exact_backup(self):
        manifest = recovery.verify_backup(self.backup)
        self.assertEqual("12", manifest["schemaVersion"])

    def test_rejects_changed_managed_content(self):
        (self.backup / "managed" / ROOT_ID / "covers" / "ab" / "cover.jpg").write_bytes(b"changed")
        with self.assertRaisesRegex(recovery.RecoveryError, "exact integrity"):
            recovery.verify_backup(self.backup)

    def test_rejects_unexpected_files(self):
        (self.backup / "private-source.epub").write_bytes(b"must not be copied")
        with self.assertRaisesRegex(recovery.RecoveryError, "Unexpected backup entries"):
            recovery.verify_backup(self.backup)

    def test_rejects_group_readable_backup_directory(self):
        self.backup.chmod(0o750)
        with self.assertRaisesRegex(recovery.RecoveryError, "group or world"):
            recovery.verify_backup(self.backup)

    def test_rejects_traversal_in_manifest(self):
        manifest_path = self.backup / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["managedRoots"][0]["entries"][0]["path"] = "../escape"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(recovery.RecoveryError, "Unsafe managed path"):
            recovery.verify_backup(self.backup)

    def test_rejects_unsupported_manifest_version(self):
        manifest_path = self.backup / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["version"] = 2
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(recovery.RecoveryError, "Unsupported"):
            recovery.verify_backup(self.backup)

    def test_rejects_symbolic_links_in_managed_roots(self):
        root = self.backup / "managed" / ROOT_ID
        (root / "escape").symlink_to(self.backup / "database.dump")
        with self.assertRaisesRegex(recovery.RecoveryError, "symbolic links"):
            recovery.regular_tree_entries(root)

    def test_restores_modes_and_bytes_to_a_new_tree(self):
        source = self.backup / "managed" / ROOT_ID
        file_path = source / "covers" / "ab" / "cover.jpg"
        file_path.chmod(0o640)
        destination = Path(self.temporary.name) / "restored"
        recovery.copy_regular_tree(source, destination)
        restored = destination / "covers" / "ab" / "cover.jpg"
        self.assertEqual(b"verified-cover", restored.read_bytes())
        self.assertEqual(0o640, stat.S_IMODE(restored.stat().st_mode))


if __name__ == "__main__":
    unittest.main()
