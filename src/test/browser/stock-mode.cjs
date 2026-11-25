const assert = require('node:assert/strict');
const fs = require('node:fs');
const {chromium} = require('playwright');

(async () => {
  const browser = await chromium.launch({executablePath:process.env.CHROME_PATH,headless:true});
  const url = process.env.BABYCARE_URL;
  try {
    async function login(user, password) {
      const page = await browser.newPage();
      await page.goto(url);
      await page.locator('#login select').selectOption(user);
      await page.locator('#login input').fill(password);
      await page.locator('#login button').click();
      if (user !== 'ops') {
        await page.locator('#fresh').click();
        await page.waitForFunction(() => !document.querySelector('#fresh').disabled);
      }
      return page;
    }
    async function ask(page,text) {
      await page.locator('#question').fill(text);
      const received = page.waitForResponse(r => r.url().endsWith('/messages'));
      await page.getByRole('button',{name:'发送',exact:true}).click();
      const response = await received;
      assert.equal(response.status(),200);
      await page.waitForFunction(() => document.querySelector('#question').value === '');
      return response.json();
    }

    const direct = await login('direct',process.env.BABYCARE_DEMO_PASSWORD);
    const franchise = await login('franchise',process.env.BABYCARE_DEMO_PASSWORD);
    const ops = await login('ops',process.env.BABYCARE_OPS_PASSWORD);
    async function switchMode(paused,reason) {
      await ops.locator('#stock-store').selectOption('STORE-01');
      await ops.locator('#stock-reason').fill(reason);
      const received = ops.waitForResponse(r => r.url().endsWith('/stock-mode/STORE-01') && r.request().method()==='POST');
      await ops.getByRole('button',{name:paused ? '暂停库存推荐' : '恢复库存推荐'}).click();
      const res = await received;
      assert.equal(res.status(),200);
      const change = await res.json();
      assert.equal(change.paused,paused);
      assert.equal(change.operator,'ops');
      assert.equal(change.reason,reason);
      assert.ok(Date.parse(change.changedAt));
      await ops.getByRole('button',{name:paused ? '恢复库存推荐' : '暂停库存推荐'}).waitFor();
      return change;
    }
    const question = '本店绒朵 L 码买两包，总预算 300 元，库存够吗？';
    const source = process.env.BABYCARE_STOCK_SOURCE;
    const original = fs.readFileSync(source);
    const paused = await switchMode(true,'库存接口持续故障');
    let resumed = false;
    try {
      const view = await ask(direct,question);
      const manual = view.turns.at(-1).reply;
      assert.equal(manual.type,'stock_manual');
      assert.deepEqual(manual.products,[]);
      assert.deepEqual(manual.quotes,{});
      assert.deepEqual(manual.trace,[]);
      await direct.locator('#history article').last().getByText('本店库存核验已暂停，请到 POS 核实。').waitFor();
      const detail = await direct.request.get(url+'/api/stores/STORE-01/offers/DEMO-008');
      assert.equal(detail.status(),423);
      const other = (await ask(franchise,question)).turns.at(-1).reply;
      assert.equal(other.products[0].packs,6);
      const knowledge = (await ask(direct,'手上有明显污垢该怎么清洁？')).turns.at(-1).reply;
      assert.equal(knowledge.type,'knowledge');
      assert.equal(knowledge.citations[0].id,'hand-hygiene-1.0');

      const stock = JSON.parse(original);
      stock.products.find(p => p.sku === 'DEMO-008').store_offers.find(o => o.store_id === 'STORE-01').available_packs = 1;
      fs.writeFileSync(source,JSON.stringify(stock));
      const recovery = await switchMode(false,'已与 POS 对账');
      resumed = true;
      const historyUrl = await ops.locator('#stock-history').getAttribute('href');
      const records = await (await ops.request.get(new URL(historyUrl,url).href)).json();
      assert.deepEqual(records.slice(0,2),[recovery,paused]);
      const next = await ask(direct,'再查一下');
      assert.equal(next.id,view.id);
      assert.match(next.turns.at(-1).reply.text,/需要同款 2 包.*仅有 1 包库存/);
    } finally {
      fs.writeFileSync(source,original);
      if (!resumed) await switchMode(false,'结束测试');
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
