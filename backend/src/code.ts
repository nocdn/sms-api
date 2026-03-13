const CODE_KEYWORD_PATTERN =
  "(?:codes?|passcodes?|one-time passcodes?|one-time codes?|verification codes?|security codes?|auth(?:orization)? codes?|pins?|otp|码|use code|autoriza(?:ca|çã)o|c(?:o|ó)digo)";

const NUMERIC_CODE_PATTERN = new RegExp(
  `(${CODE_KEYWORD_PATTERN}\\s*[:：]|is\\s*[:：])\\s*(\\d{4,8})($|\\s|\\\\R|\\t|\\b|\\.|,)`,
  "i",
);

const ALPHANUMERIC_CODE_PATTERN = new RegExp(
  `(${CODE_KEYWORD_PATTERN}\\s*[:：]|is\\s*[:：])\\s*([A-Z0-9]{4,8})($|\\s|\\\\R|\\t|\\b|\\.|,)`,
  "i",
);

export const extractCode = (original: string): string | null => {
  if (!original) {
    return null;
  }

  const urlRegex = new RegExp(
    "\\b((https?|ftp|file):\\/\\/|www\\.)[-A-Z0-9+&@#\\/%?=~_|$!:,.;]*[A-Z0-9+&@#\\/%=~_|$]",
    "ig",
  );
  let message = original.replaceAll(urlRegex, "");

  if (message.trim() == "") {
    return null;
  }

  let m: RegExpExecArray | null;
  let code: string | undefined;

  if ((m = /^(\d{4,8})(\sis your.*code)/.exec(message)) !== null) {
    code = m[1];
  } else if ((m = NUMERIC_CODE_PATTERN.exec(message)) !== null) {
    code = findLastMatchingCode(message, m, NUMERIC_CODE_PATTERN);
  } else if ((m = ALPHANUMERIC_CODE_PATTERN.exec(message)) !== null) {
    code = findLastMatchingCode(message, m, ALPHANUMERIC_CODE_PATTERN);
  } else {
    const phoneRegex = new RegExp(
      /(?:(?:\+?1\s*(?:[.-]\s*)?)?(?:\(\s*([2-9]1[02-9]|[2-9][02-8]1|[2-9][02-8][02-9])\s*\)|([2-9]1[02-9]|[2-9][02-8]1|[2-9][02-8][02-9]))\s*(?:[.-]\s*)?)?([2-9]1[02-9]|[2-9][02-9]1|[2-9][02-9]{2})\s*(?:[.-]\s*)?([0-9]{4})(?:\s*(?:#|x\.?|ext\.?|extension)\s*(\d+))?/,
      "ig",
    );

    message = message.replaceAll(phoneRegex, "");

    if ((m = /(^|\s|\\R|\t|\b|G-|[:：])(\d{5,8})($|\s|\\R|\t|\b|\.|,)/.exec(message)) !== null) {
      code = m[2];
    } else if ((m = /\b(?=[A-Z]*[0-9])(?=[0-9]*[A-Z])[0-9A-Z]{3,8}\b/.exec(message)) !== null) {
      code = m[0];
    } else if ((m = /(^|code[:：]|is[:：]|\b)\s*(\d{3})-(\d{3})($|\s|\\R|\t|\b|\.|,)/i.exec(message)) !== null) {
      code = `${m[2]}${m[3]}`;
    } else if ((m = /(code|is)[:：]?\s*(\d{3,8})($|\s|\\R|\t|\b|\.|,)/i.exec(message)) !== null) {
      code = m[2];
    }
  }

  return code ?? null;
};

const findLastMatchingCode = (message: string, initialMatch: RegExpExecArray, pattern: RegExp): string => {
  let code = initialMatch[2];
  let lastIndex = initialMatch.index + initialMatch[0].length;

  let nextMatch: RegExpExecArray | null;
  while ((nextMatch = pattern.exec(message.substring(lastIndex))) !== null) {
    code = nextMatch[2];
    lastIndex += nextMatch.index + nextMatch[0].length;
  }

  return code;
};
