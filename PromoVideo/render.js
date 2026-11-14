/**
 * 逐帧渲染宣传片
 *   node render.js --test          只渲染若干关键帧到 out/test/
 *   node render.js --all           渲染全部帧并合成 MP4
 *   node render.js --frames 0-300  渲染指定帧区间（调试用）
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');
const ffmpegPath = require('@ffmpeg-installer/ffmpeg').path;

const ROOT = __dirname;
const OUT = path.join(ROOT, 'out');
const FRAMES = path.join(OUT, 'frames');
const FPS = 30;
const DURATION = 42;
const TOTAL = Math.round(FPS * DURATION);

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml'
};

function startServer() {
  return new Promise(resolve => {
    const srv = http.createServer((req, res) => {
      const url = decodeURIComponent(req.url.split('?')[0]);
      const file = path.join(ROOT, url === '/' ? 'film.html' : url);
      if (!file.startsWith(ROOT) || !fs.existsSync(file)) {
        res.writeHead(404); res.end('not found'); return;
      }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
      fs.createReadStream(file).pipe(res);
    });
    srv.listen(0, '127.0.0.1', () => resolve({ srv, port: srv.address().port }));
  });
}

(async () => {
  const args = process.argv.slice(2);
  const isTest = args.includes('--test');
  const isAll = args.includes('--all');

  fs.mkdirSync(OUT, { recursive: true });
  if (!fs.existsSync(FRAMES)) fs.mkdirSync(FRAMES, { recursive: true });

  const { srv, port } = await startServer();
  console.log('[server] http://127.0.0.1:' + port);

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: [
      '--force-color-profile=srgb',
      '--font-render-hinting=none',
      '--hide-scrollbars',
      '--disable-lcd-text',
      '--allow-file-access-from-files',
      '--disable-dev-shm-usage'
    ]
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 1080, deviceScaleFactor: 1 });
  page.on('console', m => { if (m.type() === 'error') console.log('[page]', m.text()); });
  await page.goto(`http://127.0.0.1:${port}/film.html?render=1`, { waitUntil: 'load' });
  await page.waitForFunction('window.__ready === true', { timeout: 30000 });
  await new Promise(r => setTimeout(r, 400));

  if (isTest) {
    const dir = path.join(OUT, 'test');
    fs.mkdirSync(dir, { recursive: true });
    const times = [1.4, 4.9, 7.6, 9.4, 13.2, 16.4, 18.4, 21.2, 21.45, 22.2, 27.0, 29.6, 33.4, 38.4, 41.2];
    for (const t of times) {
      await page.evaluate(tt => window.seek(tt), t);
      await new Promise(r => setTimeout(r, 60));
      const f = path.join(dir, `t${t.toFixed(1).replace('.', '_')}.jpg`);
      await page.screenshot({ path: f, type: 'jpeg', quality: 95 });
      console.log('  frame t=' + t + 's');
    }
    await browser.close();
    srv.close();
    return;
  }

  if (isAll) {
    const t0 = Date.now();
    for (let i = 0; i < TOTAL; i++) {
      const t = i / FPS;
      await page.evaluate(tt => window.seek(tt), t);
      const f = path.join(FRAMES, String(i).padStart(5, '0') + '.jpg');
      await page.screenshot({ path: f, type: 'jpeg', quality: 94 });
      if (i % 60 === 0) {
        const el = (Date.now() - t0) / 1000;
        const eta = (el / (i + 1)) * (TOTAL - i - 1);
        console.log(`  ${i}/${TOTAL}  ${el.toFixed(0)}s elapsed  ETA ${eta.toFixed(0)}s`);
      }
    }
    console.log('[frames] done in ' + ((Date.now() - t0) / 1000).toFixed(0) + 's');
    await browser.close();
    srv.close();

    // 合成 MP4
    const { execFileSync } = require('child_process');
    const mp4 = path.join(OUT, 'promo.mp4');
    console.log('[ffmpeg] encoding...');
    execFileSync(ffmpegPath, [
      '-y',
      '-framerate', String(FPS),
      '-i', path.join(FRAMES, '%05d.jpg'),
      '-c:v', 'libx264',
      '-profile:v', 'high',
      '-crf', '16',
      '-preset', 'slow',
      '-pix_fmt', 'yuv420p',
      '-movflags', '+faststart',
      mp4
    ], { stdio: 'inherit' });
    console.log('[done] ' + mp4);
    return;
  }

  console.log('usage: node render.js --test | --all');
  await browser.close();
  srv.close();
})().catch(e => { console.error(e); process.exit(1); });
