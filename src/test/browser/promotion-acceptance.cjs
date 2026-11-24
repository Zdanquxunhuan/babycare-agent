const assert = require('node:assert/strict');
const fs = require('node:fs');
const {chromium} = require('playwright');

(async () => {
  const browser = await chromium.launch({executablePath:process.env.CHROME_PATH,headless:true});
  try {
    const page = await browser.newPage();
    await page.goto(process.env.BABYCARE_URL);
    await page.locator('#login select').selectOption('direct');
    await page.locator('#login input').fill(process.env.BABYCARE_DEMO_PASSWORD);
    await page.locator('#login button').click();
    await page.locator('#fresh').click();
    async function ask(text) {
      await page.locator('#question').fill(text);
      const completed = page.waitForResponse(r => r.url().endsWith('/messages'));
      await page.getByRole('button',{name:'发送',exact:true}).click();
      const response = await completed;
      assert.equal(response.status(),200);
      const reply = (await response.json()).turns.at(-1).reply;
      await page.waitForFunction(() => document.querySelector('#question').value==='');
      return reply;
    }
    const regular = await ask('两包 L 码总共不超 220 元');
    assert.equal(regular.totals['DEMO-003'],212);
    assert.equal(regular.totals['DEMO-008'],undefined);
    const discounted = await ask('那本店今天有活动吗？');
    assert.equal(discounted.totals['DEMO-008'],218);
    assert.equal(discounted.products.find(p => p.sku==='DEMO-008').packs,3);
    await page.locator('article').last().getByText(/238 元.*减 20 元，合计 218 元/).waitFor();
    const flag = process.env.BABYCARE_PROMOTION_FAULT_FILE;
    fs.writeFileSync(flag,'',{flag:'wx'});
    try {
      const offline = await ask('再查一下活动');
      assert.equal(offline.totals['DEMO-003'],212);
      assert.equal(offline.quotes['DEMO-008'].regularTotal,238);
      assert.equal(offline.quotes['DEMO-008'].payable,null);
      assert.ok(!offline.products.some(p => p.sku==='DEMO-008'));
      await page.locator('article').last().getByText(/普通价合计 238 元，超出预算，优惠待确认/).waitFor();
    } finally { fs.unlinkSync(flag); }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
