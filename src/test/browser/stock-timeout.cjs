const assert = require('node:assert/strict');
const fs = require('node:fs');
const {chromium} = require('playwright');

(async () => {
  const browser = await chromium.launch({executablePath:process.env.CHROME_PATH, headless:true});
  const source = process.env.BABYCARE_STOCK_SOURCE;
  const flag = process.env.BABYCARE_STOCK_DELAY_FILE;
  const url = process.env.BABYCARE_URL;
  try {
    async function login(name, password) {
      const page = await browser.newPage();
      await page.goto(url);
      await page.locator('#login select').selectOption(name);
      await page.locator('#login input').fill(password);
      await page.locator('#login button').click();
      return page;
    }
    const ops = await login('ops', process.env.BABYCARE_OPS_PASSWORD);
    const link = ops.getByRole('link',{name:'库存超时告警'});
    await link.waitFor();
    const alerts = new URL(await link.getAttribute('href'), url).href;
    const guide = await login('direct', process.env.BABYCARE_DEMO_PASSWORD);
    await guide.locator('#fresh').click();
    await guide.waitForFunction(() => !document.querySelector('#fresh').disabled);
    assert.equal((await guide.request.get(alerts)).status(),403);

    async function ask(text) {
      await guide.locator('#question').fill(text);
      const received = guide.waitForResponse(r => r.url().endsWith('/messages'));
      await guide.getByRole('button',{name:'发送',exact:true}).click();
      const response = await received;
      assert.equal(response.status(),200);
      await guide.waitForFunction(() => document.querySelector('#question').value === '');
      return response.json();
    }

    const first = await ask('本店绒朵 L 码两包，总预算 218 元，有库存和活动吗？');
    assert.equal(first.turns.at(-1).reply.products[0].packs,3);
    assert.equal(first.turns.at(-1).reply.totals['DEMO-008'],218);

    fs.writeFileSync(flag,'',{flag:'wx'});
    try {
      const start = performance.now();
      const failed = await ask('帮我再核一下库存');
      assert.ok(performance.now() - start < 10000, '页面应在模拟源的 20 秒延迟结束前恢复');
      const {messageId,reply} = failed.turns.at(-1);
      assert.equal(failed.id, first.id);
      assert.equal(reply.type,'stock_unavailable');
      assert.deepEqual(reply.products,[]);
      assert.deepEqual(reply.totals,{});
      await guide.locator('#history article').last().getByText('查询编号 ' + messageId).waitFor();
      const response = await ops.request.get(alerts);
      assert.equal(response.status(),200);
      const event = (await response.json()).find(a => a.messageId === messageId);
      assert.ok(event);
      assert.equal(event.tool,'stock.lookup');
      assert.equal(event.outcome,'timeout');
      assert.equal(event.store,'STORE-01');
      assert.equal(event.timeoutMs,500);
      assert.ok(event.elapsedMs >= 490 && event.elapsedMs < 10000);
      console.log('Stock timeout: budget=%dms elapsed=%dms, matched by messageId',event.timeoutMs,event.elapsedMs);
    } finally { fs.unlinkSync(flag); }

    const original = fs.readFileSync(source);
    try {
      const changed = JSON.parse(original);
      changed.products.find(p => p.sku === 'DEMO-008').store_offers
        .find(o => o.store_id === 'STORE-01').available_packs = 1;
      fs.writeFileSync(source,JSON.stringify(changed));
      const resumed = await ask('再查一下');
      assert.equal(resumed.id, first.id);
      assert.equal(resumed.version,3);
      assert.equal(resumed.turns.at(-1).reply.type,'empty');
      assert.match(resumed.turns.at(-1).reply.text,/需要同款 2 包.*仅有 1 包库存/);
    } finally { fs.writeFileSync(source,original); }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
