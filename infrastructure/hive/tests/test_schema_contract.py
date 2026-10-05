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
    def test_checkout_snapshots_and_required_placement_input_are_composed(self):
        for filename in ("retail.graphql", "supergraph.graphql"):
            text = (ROOT / "infrastructure/hive" / filename).read_text()
            with self.subTest(schema=filename):
                self.assertIn("createOrder(input: PlaceOrderInput!): Order!", text)
                self.assertIn("acceptedQuoteVersion: String!", text)
                self.assertIn("guestCheckoutPreview(input: CheckoutInput!): CheckoutPreview!", text)
                self.assertIn("guestOrder(publicId: UUID, requestId: UUID): CheckoutOrder", text)
                projection = re.search(r"type CheckoutOrder[^{]*\{([^}]+)\}", text).group(1)
                self.assertNotIn("user:", projection)
                self.assertNotIn("credential", projection.lower())

    def test_cancellation_and_financial_states_are_composed(self):
        for filename in ("retail.graphql", "supergraph.graphql"):
            text = (ROOT / "infrastructure/hive" / filename).read_text()
            with self.subTest(schema=filename):
                for operation in ("cancelOrder", "cancelGuestOrder", "cancelOrderAsStaff"):
                    self.assertIn(f"{operation}(input: CancelOrderInput!): OrderCancellationPayload!", text)
                for typename in ("CheckoutOrder", "Order", "CancellationOrder"):
                    projection = re.search(rf"type {typename}[^\{{]*\{{([^}}]+)\}}", text).group(1)
                    self.assertIn("cancellationEligibility: OrderCancellationEligibility!", projection)
                    self.assertIn("payment: OrderPayment!", projection)
                    self.assertIn("refund: OrderRefund!", projection)

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
