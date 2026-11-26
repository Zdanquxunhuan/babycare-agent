const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');

(async () => {
  const browser = await chromium.launch({executablePath: process.env.CHROME_PATH, headless: true});
  try {
    const url = process.env.BABYCARE_URL || 'http://127.0.0.1:18136';
    const key = 'acceptance-' + randomUUID();
    const ops = await browser.newPage();
    const guide = await browser.newPage();
    async function login(page, name, password) {
      await page.goto(url);
      await page.locator('#login select').selectOption(name);
      await page.locator('#login input').fill(password);
      await page.locator('#login button').click();
    }
    await login(ops, 'ops', process.env.BABYCARE_OPS_PASSWORD);
    await login(guide, 'direct', process.env.BABYCARE_DEMO_PASSWORD || 'BabyCare-demo');
    async function upload(version) {
      await ops.locator('[name=key]').fill(key);
      await ops.locator('[name=title]').fill('模拟售后政策');
      await ops.locator('[name=scope]').selectOption('STORE-01');
      await ops.locator('[name=file]').setInputFiles('fixtures/policies/returns-v'+version+'.md');
      await ops.getByRole('button',{name:'导入草稿'}).click();
      await ops.getByText('已导入草稿 V'+version,{exact:true}).waitFor();
      return ops.locator('#versions article').filter({hasText:key}).filter({hasText:'V'+version});
    }
    async function ask(question = '顾客拆包了还能退吗？') {
      await guide.locator('#question').fill(question);
      const received = guide.waitForResponse(r => r.url().endsWith('/messages'));
      await guide.getByRole('button',{name:'发送',exact:true}).click();
      assert.equal((await received).status(),200);
      await guide.waitForFunction(() => document.querySelector('#question').value==='');
      return guide.locator('#history article').last();
    }
    async function publish(row) {
      await row.getByRole('button',{name:'审核并发布'}).click();
      await row.getByRole('button',{name:'下线'}).waitFor();
    }
    const first = await upload(1);
    if (process.argv.includes('--quote-policy')) {
      const second = await upload(2);
      await publish(second);
      try {
        await guide.locator('#fresh').click();
        await guide.waitForFunction(() => !document.querySelector('#fresh').disabled);
        await ask('本店有哪些 L 码纸尿裤？只看芽禾和绒朵。');
        const reply = await ask('第二款买两包，今天有活动吗？如果拆了一包还能退吗？');
        assert.equal(await reply.locator('table tr').count(),2);
        assert.match(await reply.locator('table').innerText(),/绒朵[\s\S]*3 包[\s\S]*合计 218 元/);
        assert.match(await reply.innerText(),/售后政策：.*批次与照片/);
        assert.match(await reply.locator('a').innerText(),/第4条 已拆包.*v2/);
        if (process.argv.includes('--review')) {
          const draft = await ask('帮我把第二款的拆包问题交给售后');
          assert.match(await draft.innerText(),/绒朵.*DEMO-008/);
          assert.match(await draft.innerText(),/当时报价：2 包，共 218 元/);
          assert.match(await draft.locator('a').innerText(),/第4条 已拆包.*v2/);
          const confirm = draft.getByRole('button',{name:'提交复核',exact:true});
          const saved = guide.waitForResponse(r => r.url().endsWith('/review'));
          await confirm.click();
          const ticket = await (await saved).json();
          await draft.getByText(/待答复/).waitFor();
          const reviewer = await browser.newPage();
          await login(reviewer,'direct-reviewer',process.env.BABYCARE_DIRECT_REVIEW_PASSWORD);
          const caseRow = reviewer.locator('#review-queue article').filter({hasText:ticket.id});
          await caseRow.getByLabel('复核答复').fill('请补充包装批次和照片。');
          await caseRow.getByRole('button',{name:'提交答复'}).click();
          await reviewer.getByText('答复已返回导购会话',{exact:true}).waitFor();
          await guide.reload();
          await guide.locator('#history article').last().getByText('本店售后：请补充包装批次和照片。',{exact:true}).waitFor();
        }
      } finally {
        await second.getByRole('button',{name:'下线'}).click();
        await ops.getByText('资料已下线',{exact:true}).waitFor();
      }
      await guide.reload();
      const historical = guide.locator('#history article').nth(1);
      await historical.getByText(/当前缺少有效条款/).waitFor();
      assert.match(await historical.locator('table').innerText(),/合计 218 元/);
      assert.equal(await historical.locator('a').count(),0);
      assert.doesNotMatch(await historical.innerText(),/批次与照片/);
      if (process.argv.includes('--review')) {
        const review = guide.locator('#history article').last();
        assert.equal(await review.locator('a').count(),0);
        assert.match(await review.innerText(),/已答复/);
        await guide.getByRole('button',{name:'我的复核单'}).click();
        const mine = guide.locator('#my-cases article').filter({hasText:'请补充包装批次和照片。'}).first();
        await mine.getByText('当前缺少有效条款，需人工确认。',{exact:true}).waitFor();
      }
      return;
    }
    if (process.argv.includes('--invoice-only')) {
      await publish(first);
      try {
        const reply = await ask('发票抬头怎么改？');
        assert.match(await reply.innerText(), /现有审核资料不足以回答这个问题/);
        assert.equal(await reply.locator('a').count(), 0);
        console.log('Invoice without supporting evidence: HTTP 200, no-evidence message, zero citations');
      } finally {
        await first.getByRole('button',{name:'下线'}).click();
        await ops.getByText('资料已下线',{exact:true}).waitFor();
      }
      return;
    }
    assert.match(await (await ask()).innerText(),/资料不足/);
    await publish(first);
    const answer = await ask();
    assert.match(await answer.innerText(),/由店长确认/);
    const oldLink = await answer.locator('a').getAttribute('href');
    assert.equal((await guide.request.get(new URL(oldLink,url).href)).status(),200);
    const second = await upload(2);
    assert.match(await (await ask()).innerText(),/由店长确认/);
    await publish(second);
    assert.match(await (await ask()).innerText(),/照片/);
    assert.equal((await guide.request.get(new URL(oldLink,url).href)).status(),404);
    await second.getByRole('button',{name:'下线'}).click();
    await ops.getByText('资料已下线',{exact:true}).waitFor();
    assert.match(await (await ask()).innerText(),/资料不足/);
    console.log('Policy UI: draft, V1, V2, old citation revocation and withdrawal passed');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
