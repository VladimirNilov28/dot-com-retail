import { describe, it, expect } from "bun:test";
import { validation, emptyAddress, confirmationHref, requestSelection } from "./model";
import { checkoutOperations, customerPlace, guestPayment } from "../graphql/checkout-operations";
import { uuid } from "../graphql/scalars";

describe("checkout safety", () => {
  const address = { ...emptyAddress(), firstName: "Ada", lastName: "Example", city: "Tallinn",
    countryCode: "EE", postalCode: "10111", addressLine1: "Fixture 1" };
  const input = { contactEmail: "ada@example.test", contactPhone: "", shippingMethod: "STANDARD",
    shippingAddress: address, billingSameAsShipping: true, billingAddress: null, paymentSelection: "STRIPE" };
  it("matches required structural fields and supported destinations", () => {
    expect(validation(input, ["EE"])).toEqual({});
    expect(validation({ ...input, shippingAddress: { ...address, city: "" } }, ["EE"])["shipping.city"]).toBeTruthy();
    expect(validation({ ...input, shippingAddress: { ...address, countryCode: "DE" } }, ["EE"])["shipping.countryCode"]).toBeTruthy();
    expect(validation({ ...input, billingSameAsShipping: false }, ["EE"]).billing).toBeTruthy();
    expect(validation({ ...input, contactEmail: "invalid" }, ["EE"]).contactEmail).toBeTruthy();
  });
  it("rejects extra instrument fields and caller-supplied amounts", () => {
    const placement = { requestId: uuid(crypto.randomUUID()), acceptedQuoteVersion: "a".repeat(64), checkout: input };
    expect(() => customerPlace.variables({ input: { ...placement, amount: "1.00" } })).toThrow();
    expect(() => customerPlace.variables({ input: { ...placement, checkout: { ...input, cardNumber: "fixture" } } })).toThrow();
    expect(customerPlace.variables({ input: placement }).input.requestId).toBe(placement.requestId);
    expect(() => guestPayment.variables({ requestId: placement.requestId, orderId: "25" })).toThrow();
  });
  it("uses non-secret request references, never contact data in URLs", () => {
    const id = uuid(crypto.randomUUID());
    expect(confirmationHref(id, "guest")).toBe(`/order-confirmation/${id}?guest=1`);
    expect(new Set(checkoutOperations.map((operation) => operation.name)).size).toBe(checkoutOperations.length);
  });
  it("submits one address source while retaining editable saved-address prefill", () => {
    const saved = { ...input, shippingAddressId: "74", billingAddressId: "75", billingAddress: address };
    expect(requestSelection(saved)).toMatchObject({ shippingAddressId: "74", shippingAddress: null,
      billingAddressId: "75", billingAddress: null });
    expect(saved.shippingAddress).toBe(address);
    expect(requestSelection(input).shippingAddress).toBe(address);
  });
});
