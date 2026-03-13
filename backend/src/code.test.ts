import { describe, expect, it } from "bun:test";
import { extractCode } from "./code";

describe("fallback code extraction", () => {
  it("extracts common numeric codes", () => {
    expect(extractCode("Your Airbnb verification code is: 1234.")).toBe("1234");
    expect(extractCode("Your code is 45678!")).toBe("45678");
    expect(extractCode("G-315643 is your Google verification code")).toBe("315643");
    expect(extractCode("Duo SMS passcodes: 7581584")).toBe("7581584");
  });

  it("extracts alphanumeric and dashed codes", () => {
    expect(extractCode("Your code is: 5WGU8G")).toBe("5WGU8G");
    expect(extractCode("Your Stripe verification code is: 719-839.")).toBe("719839");
  });

  it("ignores phone numbers when searching for codes", () => {
    expect(
      extractCode(
        "USAA will never contact you for this code, don't share it: 123456. Call 800-531-8722 if you gave it to anyone.",
      ),
    ).toBe("123456");
  });

  it("handles plural code keywords before phone-number stripping", () => {
    expect(extractCode("Your security codes: 7654321")).toBe("7654321");
    expect(extractCode("Auth codes: 4321987")).toBe("4321987");
  });
});
