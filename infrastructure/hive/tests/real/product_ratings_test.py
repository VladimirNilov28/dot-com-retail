"""Opt-in P1 rating verification against the running Hive/Hydra/Kratos stack.

Creates uniquely named products and registered USER accounts through real APIs.
Only these fixture products/accounts are removed, through their normal APIs.
No database reset, seed ratings, mocked authentication or owner-product edits.
Tokens/passwords stay in memory and are never included in assertion messages.
"""

from concurrent.futures import ThreadPoolExecutor
import json
import os
import secrets
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen


class RealProductRatingsTest(unittest.TestCase):
    def post(self, url, body, token=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        request = Request(url, json.dumps(body).encode(), headers=headers)
        try:
            response = urlopen(request, timeout=30)
        except HTTPError as error:
            response = error
        with response:
            return response.status, json.load(response), response.headers

    def graphql(self, query, variables=None, token=None):
        return self.post(self.hive, {"query": query, "variables": variables or {}}, token)

    def success(self, query, variables=None, token=None):
        status, result, _ = self.graphql(query, variables, token)
        self.assertEqual(status, 200)
        self.assertFalse(result.get("errors"), "GraphQL request failed; credentials/body suppressed")
        return result["data"]

    def issue_token(self, email, password):
        status, result, _ = self.post(self.token_url, {"email": email, "password": password})
        self.assertEqual(status, 200, "Real token issuance failed; body suppressed")
        self.assertIn("rating:write", result["scope"].split())
        return result["access_token"]

    def setUp(self):
        self.hive = os.environ.get("HIVE_GRAPHQL_URL", "http://127.0.0.1:4002/graphql")
        self.backend = os.environ.get("SPRING_BASE_URL", "http://127.0.0.1:8080")
        self.token_url = os.environ.get("OAUTH_TOKEN_URL", "http://127.0.0.1:4447/internal/token")
        self.prefix = "p1-rating-check-" + secrets.token_hex(8)
        self.product_ids = []
        self.user_ids = []
        self.customer_tokens = []
        self.admin = os.environ.get("HIVE_BEARER_TOKEN") or self.issue_token(
            os.environ.get("DEV_EMAIL", "admin@bytecore.ee"),
            os.environ.get("DEV_PASSWORD", "admin-dev-password"),
        )
        self.addCleanup(self.cleanup_fixtures)
        for index in range(4):
            name = f"{self.prefix}-{index}"
            result = self.success(
                "mutation($input: CreateProductInput!) { createProduct(input: $input) { id } }",
                {"input": {"name": name, "slug": name}}, self.admin,
            )
            self.product_ids.append(result["createProduct"]["id"])
        for index in range(3):
            name = f"{self.prefix}-user-{index}"
            password = secrets.token_urlsafe(32)
            email = name + "@example.com"
            status, user, _ = self.post(self.backend + "/auth/register", {
                "username": name, "email": email, "password": password, "dateOfBirth": "2000-01-01",
            })
            self.assertEqual(status, 200, "Fixture registration failed; body suppressed")
            self.user_ids.append(str(user["id"]))
            self.customer_tokens.append(self.issue_token(email, password))

    def cleanup_fixtures(self):
        failures = []
        for product in self.product_ids:
            status, body, _ = self.graphql(
                "mutation($id: ID!) { deleteProduct(productId: $id) }", {"id": product}, self.admin,
            )
            if status != 200 or body.get("errors") or body.get("data", {}).get("deleteProduct") is not True:
                failures.append(f"product {product}")
        for user in self.user_ids:
            status, body, _ = self.graphql(
                "mutation($id: ID!) { deleteUser(userId: $id) }", {"id": user}, self.admin,
            )
            if status != 200 or body.get("errors") or body.get("data", {}).get("deleteUser") is not True:
                failures.append(f"user {user}")
        self.assertFalse(failures, "Fixture cleanup failed: " + ", ".join(failures))
        self.assertEqual(self.listing(size=1)["pageInfo"]["totalItems"], 0,
                         "Fixture products remain discoverable after cleanup")
        print("Cleanup: fixture products/ratings removed; USER identities/grants revoked "
              "and canonical accounts anonymized through the normal deletion API.")

    def rate(self, product, stars, token):
        return self.success(
            """mutation($id: ID!, $stars: Int!) {
              rateProduct(productId: $id, stars: $stars) { id averageRating ratingCount }
            }""", {"id": product, "stars": stars}, token,
        )["rateProduct"]

    def listing(self, sort="RATING_DESC", page=0, size=2):
        return self.success(
            """query($input: ProductSearchInput!) {
              searchProducts(input: $input) {
                items { id averageRating ratingCount }
                pageInfo { page size totalItems totalPages }
                facets { price { min max } categories { id count } attributes { name values { value count } } }
              }
            }""",
            {"input": {"query": self.prefix, "sort": sort, "page": page, "size": size}},
        )["searchProducts"]

    def test_real_tokens_persistence_public_roots_rating_sort_and_protections(self):
        unrated, first, tied, low = self.product_ids
        alice, bob, third = self.customer_tokens
        empty = self.success(
            "query($id: ID!) { product(id: $id) { averageRating ratingCount } }", {"id": unrated},
        )["product"]
        self.assertEqual(empty, {"averageRating": None, "ratingCount": 0})
        self.assertEqual(self.rate(first, 5, alice)["ratingCount"], 1)
        self.rate(first, 3, bob)
        self.rate(tied, 4, alice)
        self.rate(low, 1, alice)
        self.assertEqual(self.rate(first, 5, alice)["ratingCount"], 2)
        self.assertEqual(self.rate(first, 5, alice)["averageRating"], 4.0)

        mutation = "mutation($id: ID!, $stars: Int!) { rateProduct(productId: $id, stars: $stars) { id } }"
        for stars in (0, 6, 1.5, None):
            _, result, _ = self.graphql(mutation, {"id": first, "stars": stars}, alice)
            self.assertTrue(result.get("errors"), "Invalid rating was accepted")
        _, anonymous_write, _ = self.graphql(mutation, {"id": first, "stars": 5})
        self.assertTrue(anonymous_write.get("errors"), "Anonymous write was accepted")
        _, bad_token, _ = self.graphql(mutation, {"id": first, "stars": 5}, "invalid-jwt")
        self.assertTrue(bad_token.get("errors"), "Invalid bearer was accepted")
        _, forged_owner, _ = self.graphql(
            "mutation($id: ID!, $owner: ID!) { rateProduct(productId: $id, stars: 5, userId: $owner) { id } }",
            {"id": first, "owner": self.user_ids[1]}, alice,
        )
        self.assertTrue(forged_owner.get("errors"), "Caller-supplied owner was accepted")

        first_page = self.listing()
        second_page = self.listing(page=1)
        self.assertEqual([item["id"] for item in first_page["items"]], [first, tied])
        self.assertEqual([item["id"] for item in second_page["items"]], [low, unrated])
        self.assertEqual(first_page["pageInfo"], {"page": 0, "size": 2, "totalItems": 4, "totalPages": 2})
        self.assertEqual(self.listing(page=2)["items"], [])
        for sort in ("RELEVANCE", "PRICE_ASC", "PRICE_DESC"):
            result = self.listing(sort=sort, size=4)
            self.assertEqual([item["id"] for item in result["items"]], self.product_ids)
            self.assertEqual(result["facets"], first_page["facets"])
        _, oversized, _ = self.graphql(
            "query($term: String!) { searchProducts(input: {query: $term, sort: RATING_DESC, size: 101}) { items { id } } }",
            {"term": self.prefix},
        )
        self.assertTrue(oversized.get("errors"))

        query = """query($id: ID!) {
          product(id: $id) { averageRating ratingCount }
          products { id averageRating ratingCount }
        }"""
        status, public, headers = self.graphql(query, {"id": first})
        self.assertEqual(status, 200)
        self.assertFalse(public.get("errors"))
        self.assertEqual(public["data"]["product"], {"averageRating": 4.0, "ratingCount": 2})
        listed = next(item for item in public["data"]["products"] if item["id"] == first)
        self.assertEqual(listed, {"id": first, "averageRating": 4.0, "ratingCount": 2})
        self.assertIn("public", headers.get("Cache-Control", ""))
        self.assertIn("max-age=60", headers.get("Cache-Control", ""))
        for name in ("Origin", "Authorization", "Cookie"):
            self.assertIn(name.lower(), ", ".join(headers.get_all("Vary", [])).lower())

        # Simultaneous real-token writes and owner updates, with exact final persisted aggregates.
        with ThreadPoolExecutor(max_workers=3) as executor:
            list(executor.map(lambda pair: self.rate(low, pair[1], pair[0]), [(alice, 2), (bob, 4), (third, 3)]))
        low_result = self.success(
            "query($id: ID!) { product(id: $id) { averageRating ratingCount } }", {"id": low},
        )["product"]
        self.assertEqual(low_result, {"averageRating": 3.0, "ratingCount": 3})
        with ThreadPoolExecutor(max_workers=3) as executor:
            list(executor.map(lambda pair: self.rate(low, pair[1], pair[0]), [(alice, 5), (bob, 5), (third, 5)]))
        self.assertEqual(self.listing()["items"][0], {"id": low, "averageRating": 5.0, "ratingCount": 3})

        # Even an empty inventory collection on a real variant remains protected.
        variant = self.success(
            "mutation($input: CreateProductVariantInput!) { createProductVariant(input: $input) { id } }",
            {"input": {"productId": first, "sku": self.prefix, "price": "10"}}, self.admin,
        )["createProductVariant"]["id"]
        _, private, _ = self.graphql(
            "query($id: ID!) { product(id: $id) { variants { inventory { quantity warehouse { name } } } } }",
            {"id": first},
        )
        self.assertTrue(private.get("errors"), "Inventory became anonymously reachable")
        self.assertEqual(private["errors"][0]["path"], ["product", "variants", 0, "inventory"])
        # Customer scope does not authorize catalog writes.
        _, denied, _ = self.graphql(
            "mutation($id: ID!) { deleteProduct(productId: $id) }", {"id": first}, alice,
        )
        self.assertTrue(denied.get("errors"))
        self.assertIsNotNone(variant)
        print("Live Hive: real USER tokens, persisted updates/concurrency, anonymous aggregates, "
              "rating/id/null order, pagination, validation, ownership and inventory protections passed.")


if __name__ == "__main__":
    unittest.main(verbosity=2)
