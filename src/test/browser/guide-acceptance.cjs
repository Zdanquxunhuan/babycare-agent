const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
(async()=>{
const browser=await chromium.launch({executablePath:process.env.CHROME_PATH,headless:true});
try {
const page=await browser.newPage();
page.setDefaultTimeout(100000);
const errors=[];
const toolStats={turns:0,checks:0,duplicates:0};
page.on('pageerror',err=>errors.push(err.message));
await page.goto(process.env.BABYCARE_URL || 'http://127.0.0.1:18136');
async function login(name){
  await page.locator('select').selectOption(name);
  await page.locator('[name=password]').fill(process.env.BABYCARE_DEMO_PASSWORD || 'BabyCare-demo');
  await page.getByText('进入工作台',{exact:true}).click();
  await page.locator('#question').waitFor();
}
async function ask(text){
  await page.locator('#question').fill(text);
  const complete=page.waitForResponse(r=>r.url().endsWith('/messages'));
  await page.getByText('发送',{exact:true}).click();
  const r=await complete;
  const view=await r.json();
  assert.equal(r.status(),200,view.message || '消息接口未成功');
  const trace=view.turns.at(-1).reply.trace || [];
  if(trace.length) {
    const checks=trace.filter(step=>/^(价格核验 ·|库存核验 ·|库存查询失败)/.test(step))
      .map(step=>step.startsWith('价格')?'PRICE':'STOCK');
    toolStats.turns++;
    toolStats.checks+=checks.length;
    toolStats.duplicates+=checks.length-new Set(checks).size;
  }
  await page.waitForFunction(()=>document.querySelector('#question').value==='');
  return page.locator('article').last().innerText();
}
async function fresh(){ const response=page.waitForResponse(r=>r.url().endsWith('/api/conversations')); await page.locator('#fresh').click();await response; }
await login('direct');
const purchasesOnly=process.argv.includes('--purchase-only');
const fault=process.env.BABYCARE_STOCK_FAULT_FILE || 'target/stock-offline.flag';
let answer;
if (!purchasesOnly) {
assert.match(await page.locator('#location').innerText(),/直营/); console.log('AC-01 OK');
assert.match(await ask('请查加盟店绒朵 L 码纸尿裤库存'),/超出/); console.log('AC-02 OK');
assert.match(await ask('本店绒朵 L 码纸尿裤多少钱？有多少包？'),/119[\s\S]*3 包/);
await fresh();
await ask('本店 200 元以内有哪些纸尿裤？');
assert.match(await ask('帮我选一款合适尺码的'),/什么尺码/);console.log('AC-04 OK');
answer=await ask('L 码，只看有货的，列出全部');
for(const value of ['芽禾','绒朵','小晴','沐叶','2026-11-23','本次查询'])assert.ok(answer.includes(value));
assert.ok(!answer.includes('星棉'));console.log('AC-05 OK');
answer=await ask('预算改成 120 元，排除芽禾');assert.match(answer,/1 款[\s\S]*绒朵[\s\S]*119[\s\S]*3 包/);console.log('AC-06 OK');
assert.match(await ask('最多 100 元'),/没有符合/);console.log('AC-07 OK');
await fresh();
answer=await ask('比较芽禾和绒朵 L 码纸尿裤，每包多少片、多少钱、哪款更透气？');
assert.match(answer,/缺少可核实的“透气性”/);assert.match(answer,/106/);assert.match(answer,/119/);console.log('AC-09 OK');
await fresh();
fs.writeFileSync(fault,'',{flag:'wx'});
try {assert.match(await ask('本店绒朵 L 码纸尿裤现在有货吗？'),/库存暂无法确认/);}finally{fs.unlinkSync(fault);}
assert.match(await ask('再查一次本店绒朵 L 码纸尿裤库存'),/3 包/);console.log('AC-10 OK');
assert.match(await ask('照护孩子时，手上有明显污垢该怎么清洁？'),/肥皂/);
assert.equal(await page.locator('article').last().locator('a').getAttribute('href'),'https://www.chp.gov.hk/sc/healthtopics/content/460/19728.html');console.log('AC-11 OK');
assert.match(await ask('模拟门店资料核对卡上的核对码是什么？'),/资料不足/);console.log('AC-12 OK');
await ask('给我推荐本店有货的 L 码纸尿裤');
answer=await ask('宝宝现在呼吸困难，还该买哪款？');assert.match(answer,/急救/);assert.equal(await page.locator('article').last().locator('table').count(),0);console.log('AC-13 OK');
}
await fresh();
answer=await ask('买两包同款 L 码纸尿裤，总共最多 220 元，列出本店符合条件的商品');
assert.match(answer,/1 款/);
assert.match(answer,/106 × 2 = 212 元/);
await page.locator('article').last().locator('summary').click();
const steps=await page.locator('article').last().locator('details li').allTextContents();
assert.equal(steps.filter(s=>s.startsWith('价格核验 ·')).length,1);
assert.equal(steps.filter(s=>s.startsWith('库存核验 ·')).length,1);
console.log('MP-01 OK',steps.join(' / '));
answer=await ask('总预算改为 212 元');
assert.match(answer,/106 × 2 = 212 元/);
console.log('MP-02 OK');
answer=await ask('改成一共 200 元');
assert.match(answer,/超出当前预算/);
assert.equal(await page.locator('article').last().locator('table').count(),0);
console.log('MP-03 OK');
await fresh();
answer=await ask('本店绒朵 L 码纸尿裤买四包，总共最多 800 元');
assert.match(answer,/仅有 3 包库存/);
console.log('MP-04 OK');
await fresh();
answer=await ask('本店 L 码纸尿裤买两包，预算 220 元');
assert.match(answer,/每包上限.*总预算/);
assert.match(await ask('是两包的总预算'),/106 × 2 = 212 元/);
console.log('MP-05 OK');
await fresh();
answer=await ask('本店 L 码纸尿裤想买两包，每包不超过 120 元');
assert.match(answer,/119 × 2 = 238 元/);
assert.match(answer,/106 × 2 = 212 元/);
console.log('MP-06 OK');
await fresh();
answer=await ask('本店 L 码纸尿裤，总共最多 220 元');
assert.match(answer,/几包/);
assert.match(await ask('需要两包'),/106 × 2 = 212 元/);
console.log('MP-07 OK');
fs.writeFileSync(fault,'',{flag:'wx'});
try { assert.match(await ask('再查一次'),/库存暂无法确认/); }
finally { fs.unlinkSync(fault); }
assert.match(await ask('再查一次'),/106 × 2 = 212 元/);
console.log('MP-08 OK');
if (!purchasesOnly) {
await page.locator('#logout').click();await login('franchise');
assert.match(await ask('本店绒朵 L 码纸尿裤多少钱？有多少包？'),/124[\s\S]*6 包/);console.log('AC-03 OK');
await fresh();answer=await ask('列出本店 150 元以内有货的 L 码纸尿裤');assert.match(answer,/沐叶[\s\S]*150/);assert.ok(!answer.includes('星棉'));console.log('AC-08 OK');
}
assert.equal(toolStats.duplicates,0);
assert.equal(errors.length,0);console.log('Browser checks passed',toolStats);
} finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exitCode=1;});
