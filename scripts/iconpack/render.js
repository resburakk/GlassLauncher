const { chromium } = require('playwright');
const fs = require('fs'); const path = require('path');
(async () => {
  const dir = process.argv[2], out = process.argv[3];
  fs.mkdirSync(out, { recursive: true });
  const names = JSON.parse(fs.readFileSync(path.join(dir, 'names.json')));
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 432, height: 432 } });
  for (const n of names) for (const k of ['bg', 'fg']) {
    const svg = fs.readFileSync(path.join(dir, `${n}_${k}.svg`), 'utf8');
    await p.setContent(`<html><body style="margin:0;background:transparent">${svg.replace('<svg ', '<svg width="432" height="432" ')}</body></html>`);
    await p.screenshot({ path: path.join(out, `${n}_${k}.png`), omitBackground: true, clip: { x: 0, y: 0, width: 432, height: 432 } });
  }
  // Önizleme: launcher gibi orta 72/108 alan, yuvarlak köşe.
  let html = '<html><body style="margin:0;background:#1b2340;display:flex;flex-wrap:wrap;gap:22px;padding:24px;width:760px;font:13px sans-serif;color:#fff">';
  for (const n of names) {
    const bg = fs.readFileSync(path.join(out, `${n}_bg.png`)).toString('base64');
    const fg = fs.readFileSync(path.join(out, `${n}_fg.png`)).toString('base64');
    html += `<div style="width:120px;text-align:center"><div style="width:120px;height:120px;border-radius:28px;overflow:hidden;position:relative">` +
      `<img src="data:image/png;base64,${bg}" style="position:absolute;width:180px;left:-30px;top:-30px">` +
      `<img src="data:image/png;base64,${fg}" style="position:absolute;width:180px;left:-30px;top:-30px"></div>${n.replace('cam_','')}</div>`;
  }
  html += '</body></html>';
  await p.setViewportSize({ width: 808, height: 1400 });
  await p.setContent(html);
  await p.screenshot({ path: path.join(dir, 'preview.png'), fullPage: true });
  await b.close();
})();
