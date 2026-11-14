import copy
import unittest

from artifact_selection import MAX_ARTIFACTS, PROFILES, select


class ArtifactSelectionTest(unittest.TestCase):
    workflow_run = 38394403622
    head = "4b178789c9d4f09b067941d3f2aef4905d058b59"

    def names(self):
        return [f"real-gameplay-{profile}-{self.workflow_run}" for profile in PROFILES]

    def artifact(self, name, artifact_id, expired=False):
        return {"name": name, "id": artifact_id, "expired": expired,
                "workflow_run": {"id": self.workflow_run, "head_sha": self.head}}

    def inventory(self):
        rows = [self.artifact(name, 100 + i) for i, name in enumerate(self.names())]
        return {"total_count": len(rows), "artifacts": rows}

    def test_rerun_acquisition_uses_new_archive_without_old_report_collision(self):
        document = self.inventory()
        document["artifacts"][0]["id"] = 11811959224  # Actual failed attempt-1 archive.
        replacement = self.artifact(self.names()[0], 11812656291)  # Actual passing rerun archive.
        document["artifacts"].insert(0, replacement)
        document["total_count"] += 1
        selected = select(document, self.names(), self.workflow_run, self.head)
        self.assertEqual([11812656291, 101, 102, 103, 104], [row["id"] for row in selected])
        document["artifacts"].reverse()
        self.assertEqual(selected, select(document, self.names(), self.workflow_run, self.head))
        self.assertEqual(6, len(document["artifacts"]), "Earlier failure evidence must remain preserved")

    def test_newest_failure_is_selected_instead_of_promoting_an_older_success(self):
        document = self.inventory()
        document["artifacts"][0]["recordedResult"] = "PASS"
        newest = self.artifact(self.names()[0], 500)
        newest["recordedResult"] = "FAIL"
        document["artifacts"].append(newest)
        document["total_count"] += 1
        self.assertEqual("FAIL", select(document, self.names(), self.workflow_run, self.head)[0]["recordedResult"])

    def test_missing_profile_is_not_replaced_by_smoke_or_qualification_output(self):
        document = self.inventory()
        document["artifacts"][0]["name"] = f"real-gameplay-qualification-{self.workflow_run}"
        with self.assertRaises(ValueError):
            select(document, self.names(), self.workflow_run, self.head)

    def test_latest_expired_archive_cannot_fall_back_to_old_available_data(self):
        document = self.inventory()
        document["artifacts"].append(self.artifact(self.names()[0], 500, expired=True))
        document["total_count"] += 1
        with self.assertRaises(ValueError):
            select(document, self.names(), self.workflow_run, self.head)

    def test_wrong_head_or_run_refuses_relevant_evidence(self):
        for key, value in (("id", self.workflow_run + 1), ("head_sha", "b" * 40)):
            with self.subTest(key=key):
                document = self.inventory()
                document["artifacts"][0]["workflow_run"][key] = value
                with self.assertRaises(ValueError):
                    select(document, self.names(), self.workflow_run, self.head)

    def test_truncated_inventory_and_duplicate_ids_are_rejected(self):
        document = self.inventory()
        document["total_count"] += 1
        with self.assertRaises(ValueError):
            select(document, self.names(), self.workflow_run, self.head)
        document = self.inventory()
        document["artifacts"].append(copy.deepcopy(document["artifacts"][0]))
        document["total_count"] += 1
        with self.assertRaises(ValueError):
            select(document, self.names(), self.workflow_run, self.head)

    def test_zero_and_excess_inventory_fail_and_exact_maximum_remains_bounded(self):
        with self.assertRaises(ValueError):
            select({"total_count": 0, "artifacts": []}, self.names(), self.workflow_run, self.head)
        document = self.inventory()
        document["artifacts"] += [self.artifact("unrelated", 1000 + i)
                                  for i in range(MAX_ARTIFACTS - len(document["artifacts"]))]
        document["total_count"] = MAX_ARTIFACTS
        self.assertEqual(5, len(select(document, self.names(), self.workflow_run, self.head)))
        document["artifacts"].append(self.artifact("unrelated", 2000))
        document["total_count"] += 1
        with self.assertRaises(ValueError):
            select(document, self.names(), self.workflow_run, self.head)

    def test_certified_seed_selects_one_latest_exact_source_archive(self):
        name = f"certified-known-skill-{self.workflow_run}"
        document = {"total_count": 2, "artifacts": [self.artifact(name, 10), self.artifact(name, 20)]}
        self.assertEqual([20], [row["id"] for row in select(document, [name], self.workflow_run, self.head)])

    def test_invalid_source_and_numeric_identity_cannot_select_data(self):
        for run, head in ((0, self.head), (-1, self.head), (True, self.head), (2**63, self.head),
                          (self.workflow_run, "bad")):
            with self.subTest(run=run, head=head), self.assertRaises(ValueError):
                select(self.inventory(), self.names(), run, head)
        for value in (0, -1, True, 2**63):
            document = self.inventory()
            document["artifacts"][0]["id"] = value
            with self.subTest(id=value), self.assertRaises(ValueError):
                select(document, self.names(), self.workflow_run, self.head)


if __name__ == "__main__":
    unittest.main()
