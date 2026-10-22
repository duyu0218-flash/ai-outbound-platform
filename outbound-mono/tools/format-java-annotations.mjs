import fs from 'node:fs';

// Spotless supplies Java source on stdin. Keep declaration annotations on separate lines.
const source = fs.readFileSync(0, 'utf8');
const edits = [];
function skipLiteral(index) {
  if (source.startsWith('"""', index)) {
    let end = index + 3;
    while (end < source.length) {
      if (source[end] === '\\') end += 2;
      else if (source.startsWith('"""', end)) return end + 3;
      else end++;
    }
    return end;
  }
  const quote = source[index++];
  while (index < source.length) {
    if (source[index] === '\\') index += 2;
    else if (source[index++] === quote) break;
  }
  return index;
}
function skipComment(index) {
  const end = source.indexOf(source[index + 1] === '/' ? '\n' : '*/', index + 2);
  return end < 0 ? source.length : end + (source[index + 1] === '/' ? 0 : 2);
}
for (let index = 0; index < source.length; ) {
  if (source[index] === '"' || source[index] === "'") {
    index = skipLiteral(index);
  } else if (source.startsWith('//', index) || source.startsWith('/*', index)) {
    index = skipComment(index);
  } else if (source[index] === '@') {
    const start = index++;
    const name = source.slice(index).match(/^[\w$.]+/)?.[0];
    if (!name) continue;
    index += name.length;
    if (name === 'interface') continue;
    let end = index;
    while (/[ \t]/.test(source[end] ?? '') && end < source.length) end++;
    if (source[end] === '(') {
      let depth = 1;
      index = end + 1;
      while (index < source.length && depth) {
        if (source[index] === '"' || source[index] === "'") index = skipLiteral(index);
        else if (source.startsWith('//', index) || source.startsWith('/*', index))
          index = skipComment(index);
        else {
          if (source[index] === '(') depth++;
          if (source[index] === ')') depth--;
          index++;
        }
      }
    }
    end = index;
    while (end < source.length && /[ \t]/.test(source[end])) end++;
    // A comma or closing delimiter ends an annotation used inside another annotation.
    if (end < source.length && !/[\r\n,)}]/.test(source[end])) {
      const lineStart = source.lastIndexOf('\n', start - 1) + 1;
      const indent = source.slice(lineStart).match(/^[ \t]*/)[0];
      edits.push({ start: index, end, text: '\n' + indent });
    }
  } else index++;
}
let output = source;
for (const edit of edits.reverse())
  output = output.slice(0, edit.start) + edit.text + output.slice(edit.end);
process.stdout.write(output);
