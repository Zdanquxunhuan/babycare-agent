const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');

(async () => {
  const browser = await chromium.launch({executablePath: process.env.CHROME_PATH, headless: true});
  const url = process.env.BABYCARE_URL;
  const saved = 'target/checkpoint-browser-id.txt';
  try {
    async function login(user) {
      const page = await browser.newPage();
      await page.goto(url);
      await page.locator('#login select').selectOption(user);
      await page.locator('#login input').fill(process.env.BABYCARE_DEMO_PASSWORD);
      const opened = page.waitForResponse(r => r.url().endsWith('/api/conversations/current'));
      await page.locator('#login button').click();
      const response = await opened;
      assert.equal(response.status(), 200);
      return {page, view: await response.json()};
    }
    async function ask(page, text) {
      await page.locator('#question').fill(text);
      const received = page.waitForResponse(r => r.url().endsWith('/messages'));
      await page.getByRole('button',{name:'发送',exact:true}).click();
      const response = await received;
      assert.equal(response.status(),200);
      return response.json();
    }
    if (process.argv[2] === 'prepare') {
      const {page} = await login('direct');
      const fresh = page.waitForResponse(r => r.url().endsWith('/api/conversations'));
      await page.locator('#fresh').click();
      await fresh;
      const checkpoint = await ask(page,'买两包 L 码，预算 220 元');
      assert.equal(checkpoint.version,1);
      assert.match(checkpoint.turns[0].reply.text,/每包上限.*总预算/);
      fs.writeFileSync(saved,checkpoint.id);
    } else {
      const id = fs.readFileSync(saved,'utf8');
      const other = await login('franchise');
      assert.notEqual(other.view.id,id);
      assert.equal((await other.page.request.get(url+'/api/conversations/'+id)).status(),404);
      const {page,view} = await login('direct');
      assert.equal(view.id,id);
      assert.equal(view.version,1);
      await page.getByText(/220 元是每包上限/).waitFor();
      const done = await ask(page,'总价');
      const reply = done.turns[1].reply;
      assert.equal(done.version,2);
      assert.equal(reply.quantity,2);
      assert.equal(reply.totals['DEMO-003'],216);
      assert.equal(reply.products.find(p => p.sku==='DEMO-003').packs,4);
      await page.getByText('108 × 2 = 216 元').waitFor();
      console.log('Fresh JVM and browser: original task resumed; updated price and stock verified.');
    }
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode=1; });
