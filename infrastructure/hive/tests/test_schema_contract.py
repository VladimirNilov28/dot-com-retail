from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[3]


def root_fields(text):
    fields = {}
    for root, body in re.findall(
        r"(?:extend\s+)?type\s+(Query|Mutation|Subscription)\b[^{]*\{([^}]*)\}",
        text,
    ):
        fields.setdefault(root, set()).update(
            re.findall(r"(?m)^\s*([A-Za-z_]\w*)\s*(?:\(|:)", body)
        )
    return fields


class SchemaContractTest(unittest.TestCase):
    def test_committed_router_schemas_expose_every_application_root_field(self):
        expected = {}
        for path in (ROOT / "backend/src/main/resources/schema").rglob("*.graphqls"):
            for root, fields in root_fields(path.read_text()).items():
                expected.setdefault(root, set()).update(fields)
        self.assertIn("searchProducts", expected["Query"])
        self.assertIn("productSearchSuggestions", expected["Query"])
        for filename in ("retail.graphql", "supergraph.graphql"):
            actual = root_fields((ROOT / "infrastructure/hive" / filename).read_text())
            for root, fields in expected.items():
                with self.subTest(schema=filename, root=root):
                    missing = fields - actual.get(root, set())
                    self.assertFalse(missing, f"{filename}: missing {root} fields {sorted(missing)}")


if __name__ == "__main__":
    unittest.main()
