import {
  AddMyAddressSource, DeleteMyAddressSource, MyAddressesSource, UpdateMyAddressSource,
  type AddMyAddressMutation, type CreateAddressInput, type DeleteMyAddressMutation, type MyAddressesQuery,
  type UpdateAddressInput, type UpdateMyAddressMutation,
} from "./generated";
import type { Operation } from "./operations";
import { boolean, list, object, text } from "./scalars";

export type SavedAddress = MyAddressesQuery["me"]["addresses"][number];
export type AddressFields = Required<{ [Key in keyof CreateAddressInput]: string }>;

export const addressFieldNames = [
  "firstName", "lastName", "addressLine1", "addressLine2", "city", "postalCode", "country", "mobile",
] as const satisfies readonly (keyof AddressFields)[];

/** Same limits as the backend (CheckoutSupport.normalize). */
export const addressLimits: Record<keyof AddressFields, { max: number; required: boolean; label: string }> = {
  firstName: { max: 50, required: true, label: "First name" },
  lastName: { max: 50, required: true, label: "Last name" },
  addressLine1: { max: 255, required: true, label: "Street address" },
  addressLine2: { max: 255, required: false, label: "Apartment, suite, etc." },
  city: { max: 100, required: true, label: "City" },
  postalCode: { max: 10, required: true, label: "Postal code" },
  country: { max: 2, required: true, label: "Country" },
  mobile: { max: 20, required: false, label: "Phone" },
};

/** Field errors for an address form; empty when the address is acceptable. */
export function addressErrors(fields: AddressFields, countries: readonly string[]): Partial<Record<keyof AddressFields, string>> {
  const errors: Partial<Record<keyof AddressFields, string>> = {};
  for (const name of addressFieldNames) {
    const value = fields[name].trim();
    const { max, required, label } = addressLimits[name];
    if (!value) {
      if (required) errors[name] = `${label} is required.`;
    } else if (value.length > max) {
      errors[name] = `${label} must be at most ${max} characters.`;
    } else if (/[\u0000-\u001f\u007f-\u009f]/.test(value)) {
      errors[name] = `${label} contains invalid characters.`;
    }
  }
  if (!errors.country && !countries.includes(fields.country.trim().toUpperCase())) {
    errors.country = "We don't deliver to this country yet.";
  }
  if (!errors.mobile && fields.mobile.trim() && !/^\+?[0-9 ()-]{5,20}$/.test(fields.mobile.trim())) {
    errors.mobile = "Enter a phone number using digits, spaces and an optional leading +.";
  }
  return errors;
}

function addressInput(value: unknown): CreateAddressInput {
  const input = object(value);
  if (Object.keys(input).some((key) => !(addressFieldNames as readonly string[]).includes(key))) {
    throw new TypeError("Unexpected address fields");
  }
  const result: CreateAddressInput = {};
  for (const name of addressFieldNames) {
    const field = input[name];
    if (field === undefined || field === null) continue;
    const value = text(field);
    if (value.length > 255) throw new TypeError("Address field too long");
    result[name] = value;
  }
  return result;
}

function addressId(value: unknown): string {
  const id = text(value);
  if (!/^[1-9][0-9]{0,18}$/.test(id)) throw new TypeError("Invalid address id");
  return id;
}

function only(value: unknown, keys: readonly string[]) {
  const input = object(value);
  if (Object.keys(input).some((key) => !keys.includes(key))) throw new TypeError("Unexpected variables");
  return input;
}

const nullableText = (value: unknown) => value === null ? null : text(value);

export const myAddresses: Operation<MyAddressesQuery, Record<string, never>> = {
  name: "MyAddresses", source: MyAddressesSource, kind: "query", access: "authenticated",
  variables(value) { only(value, []); return {}; },
  decode(value) {
    return { me: { addresses: list(object(object(value).me).addresses, (item) => {
      const a = object(item);
      return {
        id: addressId(a.id), firstName: nullableText(a.firstName), lastName: nullableText(a.lastName),
        city: nullableText(a.city), country: nullableText(a.country), postalCode: nullableText(a.postalCode),
        addressLine1: nullableText(a.addressLine1), addressLine2: nullableText(a.addressLine2), mobile: nullableText(a.mobile),
      };
    }) } };
  },
};

export const addMyAddress: Operation<AddMyAddressMutation, { input: CreateAddressInput }> = {
  name: "AddMyAddress", source: AddMyAddressSource, kind: "mutation", access: "authenticated",
  variables(value) { return { input: addressInput(only(value, ["input"]).input) }; },
  decode(value) { return { addMyAddress: { id: addressId(object(object(value).addMyAddress).id) } }; },
};

export const updateMyAddress: Operation<UpdateMyAddressMutation, { id: string; input: UpdateAddressInput }> = {
  name: "UpdateMyAddress", source: UpdateMyAddressSource, kind: "mutation", access: "authenticated",
  variables(value) {
    const input = only(value, ["id", "input"]);
    return { id: addressId(input.id), input: addressInput(input.input) };
  },
  decode(value) { return { updateMyAddress: { id: addressId(object(object(value).updateMyAddress).id) } }; },
};

export const deleteMyAddress: Operation<DeleteMyAddressMutation, { id: string }> = {
  name: "DeleteMyAddress", source: DeleteMyAddressSource, kind: "mutation", access: "authenticated",
  variables(value) { return { id: addressId(only(value, ["id"]).id) }; },
  decode(value) { return { deleteMyAddress: boolean(object(value).deleteMyAddress) }; },
};

export const addressOperations = [myAddresses, addMyAddress, updateMyAddress, deleteMyAddress] as const;
