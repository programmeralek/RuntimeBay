import { isMap, isSeq, isScalar, parseAllDocuments } from 'yaml';
export interface ValueField { key: string; value: string; type: string; start: number; end: number }

export function yamlFields(source: string): ValueField[] {
  const documents = parseAllDocuments(source, { keepSourceTokens: true });
  if (documents.some(document => document.errors.length)) return [];
  const fields: ValueField[] = [];
  const visit = (node: unknown, path: string) => {
    if (isMap(node)) for (const pair of node.items) { if (isScalar(pair.key)) visit(pair.value, path ? `${path}.${String(pair.key.value)}` : String(pair.key.value)); }
    else if (isSeq(node)) node.items.forEach((item, i) => visit(item, `${path}[${i}]`));
    else if (isScalar(node) && node.type !== 'BLOCK_LITERAL' && node.type !== 'BLOCK_FOLDED' && node.range && ['string', 'number', 'boolean', 'object'].includes(typeof node.value) && (typeof node.value !== 'object' || node.value === null) && !(typeof node.value === 'number' && Number.isInteger(node.value) && !Number.isSafeInteger(node.value))) {
      fields.push({ key: path, value: node.value === null ? 'null' : String(node.value), type: node.value === null ? 'null' : typeof node.value, start: node.range[0], end: node.range[1] });
    }
  };
  documents.forEach((document, index) => visit(document.contents, documents.length > 1 ? `document[${index + 1}]` : '')); return fields;
}
export function setYamlField(source: string, field: ValueField, value: string): string {
  if (value === field.value) return source;
  let replacement: string;
  if (field.type === 'number') { if (!value.trim() || !Number.isFinite(Number(value))) throw new Error('Enter a valid number'); replacement = String(Number(value)); }
  else if (field.type === 'boolean') { if (!['true', 'false'].includes(value)) throw new Error('Use true or false'); replacement = value; }
  else if (field.type === 'null' && value === 'null') replacement = 'null';
  else replacement = JSON.stringify(value);
  const result = source.slice(0, field.start) + replacement + source.slice(field.end);
  const errors = parseAllDocuments(result).flatMap(document => document.errors); if (errors.length) throw new Error(errors[0].message);
  return result;
}
export function envFields(source: string): ValueField[] {
  const fields: ValueField[] = []; let offset = 0; let multilineQuote = '';
  const closingQuote = (text: string, quote: string, start: number) => { for (let i = start; i < text.length; i++) { if (text[i] !== quote) continue; let slashes = 0; for (let j = i - 1; j >= 0 && text[j] === '\\'; j--) slashes++; if (quote === "'" || slashes % 2 === 0) return i + 1; } return -1; };
  for (const line of source.split(/(?<=\n)/)) {
    const text = line.replace(/[\r\n]+$/, '');
    if (multilineQuote) { if (closingQuote(text, multilineQuote, 0) >= 0) multilineQuote = ''; offset += line.length; continue; }
    const match = /^(\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*)(.*)$/.exec(text);
    if (match) {
      const raw = match[3], quote = raw.startsWith('"') ? '"' : raw.startsWith("'") ? "'" : '';
      let end = raw.length;
      if (quote) { end = closingQuote(raw, quote, 1); if (end < 0) multilineQuote = quote; }
      else { const comment = /\s+#/.exec(raw); if (comment) end = comment.index; while (end > 0 && /\s/.test(raw[end - 1])) end--; }
      if (end >= 0) fields.push({ key: match[2], value: quote ? raw.slice(1, end - 1) : raw.slice(0, end), type: quote || 'unquoted', start: offset + match[1].length, end: offset + match[1].length + end });
    }
    offset += line.length;
  }
  return fields;
}
export function setEnvField(source: string, field: ValueField, value: string): string {
  if (/[\r\n\0]/.test(value)) throw new Error('Edit multiline values in Source mode');
  if (value === field.value) return source;
  let replacement = value;
  if (field.type === "'" && !value.includes("'")) replacement = "'" + value + "'";
  else if (field.type === '"' || field.type === "'" || /[\s#"']/.test(value)) replacement = '"' + value.replace(/(?<!\\)"/g, '\\"') + '"';
  const result = source.slice(0, field.start) + replacement + source.slice(field.end);
  if (!envFields(result).some(candidate => candidate.start === field.start && candidate.key === field.key)) throw new Error('This value needs different quoting. Edit it in Source mode.');
  return result;
}
