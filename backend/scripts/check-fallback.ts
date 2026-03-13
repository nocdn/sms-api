import { extractCode } from "../src/code";

const cases = [
  {
    label: "Duo passcodes",
    message: "Duo SMS passcodes: 7581584",
    expected: "7581584",
  },
  {
    label: "Standard numeric OTP",
    message: "Your verification code is: 123456",
    expected: "123456",
  },
  {
    label: "Alphanumeric code",
    message: "Your code is: 5WGU8G",
    expected: "5WGU8G",
  },
  {
    label: "Dashed code",
    message: "Your Stripe verification code is: 719-839.",
    expected: "719839",
  },
  {
    label: "Ignore phone number",
    message: "USAA will never contact you for this code, don't share it: 123456. Call 800-531-8722 if you gave it to anyone.",
    expected: "123456",
  },
  {
    label: "No code",
    message: "Your package has been delivered.",
    expected: null,
  },
];

let failed = false;

for (const testCase of cases) {
  const actual = extractCode(testCase.message);
  const passed = actual === testCase.expected;

  console.log(JSON.stringify({
    label: testCase.label,
    message: testCase.message,
    expected: testCase.expected,
    actual,
    passed,
  }));

  if (!passed) {
    failed = true;
  }
}

if (failed) {
  process.exit(1);
}
