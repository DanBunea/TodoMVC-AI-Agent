// Renders an animated (SMIL) SVG, e.g. .docs/media/agent-flow.svg, to a GIF, frame by frame,
// by stepping the SVG's own clock — so the GIF is exact, not screen-recorded.
//   node render-flow.mjs <in.svg> <out.gif> [fps=10] [seconds=13]
// Needs @playwright/test (npm ci in e2e/) and ffmpeg (PATH, or Playwright's).
import { chromium } from '@playwright/test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const [svgPath, outPath, fpsArg = '10', secondsArg = '13'] = process.argv.slice(2);
if (!svgPath || !outPath) {
  console.error('usage: node render-flow.mjs <in.svg> <out.gif> [fps] [seconds]');
  process.exit(2);
}
const fps = Number(fpsArg);
const seconds = Number(secondsArg);
const svg = fs.readFileSync(svgPath, 'utf8');
const [, w, h] = svg.match(/viewBox="0 0 (\d+) (\d+)"/);

function ffmpegBinary() {
  try { execFileSync('ffmpeg', ['-version'], { stdio: 'ignore' }); return 'ffmpeg'; } catch {}
  const cache = path.join(os.homedir(), 'Library/Caches/ms-playwright');
  const dir = fs.existsSync(cache) ? fs.readdirSync(cache).find((d) => d.startsWith('ffmpeg-')) : null;
  if (dir) return path.join(cache, dir, process.platform === 'darwin' ? 'ffmpeg-mac' : 'ffmpeg-linux');
  throw new Error('ffmpeg not found (install it, or run `npx playwright install ffmpeg`)');
}

const frames = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-flow-'));
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: Number(w), height: Number(h) }, deviceScaleFactor: 1 });
await page.setContent(`<!doctype html><html><body style="margin:0;background:#f6f7f9">${svg}</body></html>`);
await page.evaluate(() => document.querySelector('svg').pauseAnimations());
const total = Math.round(fps * seconds);
for (let i = 0; i < total; i++) {
  await page.evaluate((t) => document.querySelector('svg').setCurrentTime(t), i / fps);
  await page.screenshot({ path: path.join(frames, `f-${String(i).padStart(4, '0')}.png`) });
}
await browser.close();

execFileSync(ffmpegBinary(), [
  '-y', '-loglevel', 'error',
  '-framerate', String(fps), '-i', path.join(frames, 'f-%04d.png'),
  '-vf', 'split[a][b];[a]palettegen=max_colors=96:stats_mode=diff[p];[b][p]paletteuse=dither=none',
  '-loop', '0', outPath,
]);
if (process.env.KEEP_FRAMES) console.log('frames kept in', frames); else fs.rmSync(frames, { recursive: true });
console.log('wrote', outPath, `(${(fs.statSync(outPath).size / 1024).toFixed(0)} KB, ${total} frames)`);
