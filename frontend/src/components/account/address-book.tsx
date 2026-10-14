"use client";

import { Button } from "@heroui/react";
import { useEffect, useState, type FormEvent } from "react";
import {
  addMyAddress, addressErrors, addressLimits, deleteMyAddress, myAddresses, updateMyAddress,
  type AddressFields, type SavedAddress,
} from "@/lib/graphql/address-operations";
import { requestCustomer, requestGuest } from "@/lib/graphql/browser";
import { shippingOptions } from "@/lib/graphql/operations";

type Errors = Partial<Record<keyof AddressFields, string>>;
type Editing = { id: string | null; fields: AddressFields };

const textFields = [
  ["firstName", "given-name"], ["lastName", "family-name"], ["addressLine1", "address-line1"],
  ["addressLine2", "address-line2"], ["city", "address-level2"], ["postalCode", "postal-code"], ["mobile", "tel"],
] as const;

const blank = (country = ""): AddressFields => ({
  firstName: "", lastName: "", addressLine1: "", addressLine2: "", city: "", postalCode: "", country, mobile: "",
});

const fromSaved = (address: SavedAddress): AddressFields => ({
  firstName: address.firstName ?? "", lastName: address.lastName ?? "", addressLine1: address.addressLine1 ?? "",
  addressLine2: address.addressLine2 ?? "", city: address.city ?? "", postalCode: address.postalCode ?? "",
  country: address.country ?? "", mobile: address.mobile ?? "",
});

function countryName(code: string | null) {
  if (!code) return "";
  try { return new Intl.DisplayNames(["en"], { type: "region" }).of(code) ?? code; } catch { return code; }
}

function AddressForm({ editing, countries, busy, serverError, onCancel, onSubmit }: {
  editing: Editing; countries: string[]; busy: boolean; serverError: string | null;
  onCancel(): void; onSubmit(fields: AddressFields): void;
}) {
  const [fields, setFields] = useState(editing.fields);
  const [errors, setErrors] = useState<Errors>({});
  const options = fields.country && !countries.includes(fields.country) ? [fields.country, ...countries] : countries;

  function submit(event: FormEvent) {
    event.preventDefault();
    const found = addressErrors(fields, countries);
    setErrors(found);
    if (Object.keys(found).length === 0) onSubmit(fields);
  }

  const heading = editing.id ? "Edit address" : "Add a new address";
  return (
    <form className="account-card checkout-section address-form" onSubmit={submit} noValidate aria-labelledby="address-form-heading">
      <h2 id="address-form-heading" className="text-lg font-semibold">{heading}</h2>
      <div className="checkout-address-grid">
        {textFields.map(([key, autocomplete]) => {
          const { label, max, required } = addressLimits[key];
          return (
            <label key={key} className={key.startsWith("address") ? "checkout-wide" : ""}>
              <span>{label}{required ? "" : " (optional)"}</span>
              <input value={fields[key]} maxLength={max} required={required} autoComplete={autocomplete}
                type={key === "mobile" ? "tel" : "text"} aria-invalid={!!errors[key]}
                aria-describedby={errors[key] ? `address-${key}-error` : undefined}
                onChange={(event) => setFields({ ...fields, [key]: event.target.value })} />
              {errors[key] ? <small id={`address-${key}-error`} className="cart-line-issue">{errors[key]}</small> : null}
            </label>
          );
        })}
        <label>
          <span>Country</span>
          <select value={fields.country} required autoComplete="country" aria-invalid={!!errors.country}
            aria-describedby={errors.country ? "address-country-error" : undefined}
            onChange={(event) => setFields({ ...fields, country: event.target.value })}>
            <option value="">Select country</option>
            {options.map((code) => <option key={code} value={code}>{countryName(code)}</option>)}
          </select>
          {errors.country ? <small id="address-country-error" className="cart-line-issue">{errors.country}</small> : null}
        </label>
      </div>
      <p className="text-sm text-muted">We currently deliver to: {countries.map(countryName).join(", ") || "—"}.</p>
      {serverError ? <p role="alert" className="cart-line-issue">{serverError}</p> : null}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" isDisabled={busy}>{busy ? "Saving…" : "Save address"}</Button>
        <Button type="button" variant="secondary" isDisabled={busy} onPress={onCancel}>Cancel</Button>
      </div>
    </form>
  );
}

export function AddressBook() {
  const [addresses, setAddresses] = useState<SavedAddress[] | null>(null);
  const [countries, setCountries] = useState<string[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const [status, setStatus] = useState("");

  async function load() {
    const result = await requestCustomer(myAddresses, {});
    if (result.status === "success") { setAddresses(result.data.me.addresses); setLoadError(null); }
    else setLoadError(result.error.message);
  }

  useEffect(() => {
    const controller = new AbortController();
    async function setup() {
      const [saved, methods] = await Promise.all([
        requestCustomer(myAddresses, {}, { signal: controller.signal }),
        requestGuest(shippingOptions, {}, { signal: controller.signal }),
      ]);
      if (controller.signal.aborted) return;
      if (saved.status === "success") setAddresses(saved.data.me.addresses);
      else setLoadError(saved.error.message);
      if (methods.status === "success") {
        setCountries([...new Set(methods.data.checkoutShippingOptions.flatMap((option) => option.supportedCountries))].sort());
      }
    }
    void setup();
    return () => controller.abort();
  }, []);

  async function save(fields: AddressFields) {
    if (!editing) return;
    setBusy(true); setServerError(null);
    const input = Object.fromEntries(Object.entries(fields).map(([key, value]) => [key, key === "country" ? value.trim().toUpperCase() : value.trim()]));
    const result = editing.id
      ? await requestCustomer(updateMyAddress, { id: editing.id, input })
      : await requestCustomer(addMyAddress, { input });
    setBusy(false);
    if (result.status !== "success") { setServerError(result.error.message); return; }
    setStatus(editing.id ? "Address updated." : "Address added.");
    setEditing(null);
    await load();
  }

  async function remove(id: string) {
    setBusy(true); setStatus("");
    const result = await requestCustomer(deleteMyAddress, { id });
    setBusy(false); setConfirming(null);
    if (result.status !== "success") { setStatus(result.error.message); return; }
    setStatus("Address deleted.");
    await load();
  }

  if (loadError) return <p role="alert" className="account-card cart-line-issue">{loadError}</p>;
  if (!addresses) return <p className="account-card text-muted" aria-busy="true">Loading saved addresses…</p>;

  return (
    <>
      <p className="text-sm text-muted empty:hidden" role="status" aria-live="polite">{status}</p>
      {editing ? (
        <AddressForm key={editing.id ?? "new"} editing={editing} countries={countries} busy={busy}
          serverError={serverError} onCancel={() => { setEditing(null); setServerError(null); }} onSubmit={save} />
      ) : (
        <div>
          <Button onPress={() => { setStatus(""); setServerError(null); setEditing({ id: null, fields: blank(countries.length === 1 ? countries[0] : "") }); }}>
            Add address
          </Button>
        </div>
      )}
      {addresses.length === 0 ? (
        <section className="account-card"><p className="text-muted">You have no saved addresses yet. Addresses you save here are offered at checkout.</p></section>
      ) : (
        <ul className="grid gap-4" aria-label="Saved addresses">
          {addresses.map((address) => (
            <li key={address.id} className="account-card">
              <address className="not-italic">
                <strong>{[address.firstName, address.lastName].filter(Boolean).join(" ")}</strong><br />
                {address.addressLine1}{address.addressLine2 ? <><br />{address.addressLine2}</> : null}<br />
                {[address.postalCode, address.city].filter(Boolean).join(" ")}<br />
                {countryName(address.country)}
                {address.mobile ? <><br /><span className="text-muted">{address.mobile}</span></> : null}
              </address>
              <div className="flex flex-wrap gap-2">
                <Button variant="secondary" isDisabled={busy}
                  onPress={() => { setStatus(""); setServerError(null); setEditing({ id: address.id, fields: fromSaved(address) }); }}>Edit</Button>
                {confirming === address.id ? (
                  <>
                    <Button variant="danger" isDisabled={busy} onPress={() => void remove(address.id)}>Confirm delete</Button>
                    <Button variant="secondary" isDisabled={busy} onPress={() => setConfirming(null)}>Keep</Button>
                  </>
                ) : (
                  <Button variant="secondary" isDisabled={busy} onPress={() => setConfirming(address.id)}>Delete</Button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
