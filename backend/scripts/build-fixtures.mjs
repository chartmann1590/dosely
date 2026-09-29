/**
 * Script: build fixtures from REAL captured Play Store HTML.
 *
 * Usage:  node scripts/build-fixtures.mjs <developer-page.html> <app-page.html> <out-dir>
 *
 * The full pages are ~1 MB of Google boilerplate. We keep the segments the
 * parsers read (AF blobs, anchors, stat boxes, description divs, canonical
 * links) plus a sentinel prefix/suffix so offsets stay realistic. This keeps
 * the repo small while testing against genuine markup.
 */
import fs from 'fs';
import path from 'path';

const [, , devIn, appIn, outDir] = process.argv;
if (!devIn || !appIn || !outDir) {
  console.error('usage: build-fixtures.mjs <dev.html> <app.html> <outdir>');
  process.exit(1);
}

function trimPlayPage(html) {
  // Keep everything between the first AF_initDataCallback minus a margin and
  // the last one plus a margin; plus head links & stat boxes if present.
  const parts = [];
  const head = html.match(/^[\s\S]{0,6000}?<link rel="canonical"[^>]*>/);
  if (head) parts.push(head[0].slice(0, 6000));

  const idx = [];
  let i = html.indexOf('AF_initDataCallback(');
  while (i !== -1) {
    idx.push(i);
    i = html.indexOf('AF_initDataCallback(', i + 1);
  }
  if (idx.length > 0) {
    const start = Math.max(0, idx[0] - 200);
    const end = Math.min(html.length, idx[idx.length - 1] + 20_000);
    parts.push(html.slice(start, end));
  }
  const stats = html.match(/<div class="wVqUob">[\s\S]{0,400}?Downloads<\/div><\/div>/);
  if (stats) parts.push(stats[0]);
  const desc = html.match(/<div data-g-id="description">[\s\S]{0,8000}?<\/div>/);
  if (desc) parts.push(desc[0]);
  const cards = html.match(/<a class="Si6A0c Gy4nib" href="\/store\/apps\/details\?id=[\s\S]{0,2500}?<\/a>/g);
  if (cards) parts.push(cards.join('\n'));
  return parts.join('\n<!--SEGMENT-->\n');
}

fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'developer-page.html'), trimPlayPage(fs.readFileSync(devIn, 'utf8')));
fs.writeFileSync(path.join(outDir, 'app-detail-page.html'), trimPlayPage(fs.readFileSync(appIn, 'utf8')));
console.log('fixtures written to', outDir);
