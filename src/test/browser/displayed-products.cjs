const assert = require('node:assert/strict');
const {chromium} = require('playwright');

(async () => {
  const browser = await chromium.launch({executablePath:process.env.CHROME_PATH, headless:true});
  try {
    const page = await browser.newPage();
    await page.goto(process.env.BABYCARE_URL || 'http://127.0.0.1:18136');
    await page.locator('#login select').selectOption('direct');
    await page.locator('#login input').fill(process.env.BABYCARE_DEMO_PASSWORD || 'BabyCare-demo');
    await page.locator('#login button').click();
    await page.locator('#fresh').click();
    await page.waitForFunction(() => !document.querySelector('#fresh').disabled);

    async function ask(text) {
      await page.locator('#question').fill(text);
      const response = page.waitForResponse(r => r.url().endsWith('/messages'));
      await page.getByRole('button',{name:'发送',exact:true}).click();
      const result = await response;
      assert.equal(result.status(),200);
      await page.waitForFunction(() => document.querySelector('#question').value==='');
      return (await result.json()).turns.at(-1).reply;
    }
    const list = await ask('本店有哪些 L 码纸尿裤？只看芽禾和绒朵。');
    assert.deepEqual(list.products.map(p => p.sku),['DEMO-003','DEMO-008']);
    assert.match(await page.locator('#history article').last().innerText(),/1\. 芽禾[\s\S]*2\. 绒朵/);

    const quote = await ask('第二款买两包，今天有活动吗？');
    assert.deepEqual(quote.products.map(p => p.sku),['DEMO-008']);
    assert.equal(quote.totals['DEMO-008'],218);
    assert.equal(quote.products[0].packs,3);

    const resumed = page.waitForResponse(r => r.url().endsWith('/conversations/current'));
    await page.reload();
    assert.equal((await resumed).status(),200);
    const other = await ask('那第一款呢？也是两包。');
    assert.deepEqual(other.products.map(p => p.sku),['DEMO-003']);
    assert.equal(other.quantity,2);
    assert.equal(other.totals['DEMO-003'],212);
    assert.equal(other.quotes['DEMO-003'].discount,0);
    console.log('Candidate positions after resume: DEMO-008 = 218; DEMO-003 = 212.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
