const el = id => document.getElementById(id);
let session, chat, pending;
let stockModes = [];
const notice = text => { el("notice").textContent = text; };

async function api(path, method = "GET", body) {
  const headers = {};
  if (method !== "GET") headers["X-CSRF-TOKEN"] = session.csrf;
  if (body && !(body instanceof URLSearchParams)) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(body);
  }
  const res = await fetch(path, {method, headers, body});
  if (!res.ok) {
    const details = await res.json().catch(() => ({}));
    const fallback = {401:"请检查账号密码或重新登录",403:"会话失效或访问超出权限，请刷新页面",409:"对话已更新，请核对后重试"};
    const err = new Error(details.message || fallback[res.status] || "请求失败，请重试");
    err.status = res.status;
    throw err;
  }
  return res.status === 204 ? null : res.json();
}
function add(parent, tag, value) {
  const child = document.createElement(tag);
  if (value != null) child.textContent = value;
  parent.append(child);
  return child;
}
function render(view) {
  chat = view;
  const history = el("history");
  history.replaceChildren();
  for (const turn of chat.turns) {
    const box = add(history,"article");
    add(box,"strong",turn.question);
    add(box,"p",turn.reply.text);
    if (turn.reply.type === "stock_unavailable") add(box,"small","查询编号 " + turn.messageId);
    const rows = turn.reply.products;
    if (rows.length) {
      const table = add(box,"table");
      const header = add(table,"tr");
      ["商品","单包售价","库存"].forEach(title => add(header,"th",title));
      if (turn.reply.quantity) add(header,"th","购买合计");
      for (const [index,item] of rows.entries()) {
        const row = add(table,"tr");
        [rows.length > 1 ? (index + 1) + ". " + item.name : item.name,"¥" + item.price,item.packs + " 包"].forEach(value => add(row,"td",value));
        if (turn.reply.quantity) {
          const quote = turn.reply.quotes?.[item.sku];
          let summary = item.price + " × " + turn.reply.quantity + " = " + (quote?.regularTotal ?? turn.reply.totals[item.sku]) + " 元";
          if (quote?.activity) summary += "；" + quote.activity + "，减 " + quote.discount + " 元，合计 " + quote.payable + " 元";
          else if (quote) summary += quote.payable == null ? "（优惠待确认）" : "（无适用活动）";
          add(row,"td",summary);
        }
      }
      add(box,"small","模拟门店数据 · 快照 " + rows[0].snapshot + " · 本次查询 " +
        new Date(rows[0].queriedAt).toLocaleString("zh-CN"));
    }
    for (const quote of Object.values(turn.reply.quotes || {})) {
      if (quote.payable == null && !quote.meetsBudget)
        add(box,"p",quote.name + "：普通价合计 " + quote.regularTotal + " 元，超出预算，优惠待确认。");
    }
    if (turn.reply.trace?.length) {
      const details = add(box,"details");
      add(details,"summary","查看核验步骤");
      const steps = add(details,"ol");
      turn.reply.trace.forEach(step => add(steps,"li",step));
    }
    for (const cite of turn.reply.citations) {
      const source = add(box,"a",cite.title + " · " + (cite.section || "摘要") + " v" + cite.version + " · 查看原文");
      source.href = cite.source;
      source.target = "_blank";
      source.rel = "noopener noreferrer";
      for (const [label,helpful] of [["有用",true],["无用",false]]) {
        const vote = add(box,"button",label);
        vote.className = "quiet";
        vote.onclick = () => run(async () => {
          await api("/api/conversations/"+view.id+"/messages/"+turn.messageId+"/feedback","POST",{helpful});
          notice("已记录本条回答的反馈");
        });
      }
    }
  }
}
async function newChat() {
  render(await api("/api/conversations","POST"));
  pending = null;
}
async function init() {
  session = await api("/api/session");
  el("access").hidden = session.authenticated;
  const ops = session.role === "OPS";
  el("ops").hidden = !ops;
  if (!ops) {
    el("versions").replaceChildren();
    el("upload").reset();
    stockModes = [];
    el("stock-store").replaceChildren();
    el("stock-mode").reset();
    el("stock-status").textContent = "";
    el("stock-history").removeAttribute("href");
  }
  el("desk").hidden = !session.authenticated || ops;
  el("location").textContent = session.store?.name || "门店导购工作台";
  if (!session.authenticated) {
    el("history").replaceChildren();
    chat = pending = null;
    return;
  }
  if (ops) { await Promise.all([loadPolicies(), loadStockModes()]); return; }
  render(await api("/api/conversations/current","POST"));
  pending = null;
}
async function run(action) {
  const buttons = [...document.querySelectorAll("button")];
  buttons.forEach(button => button.disabled = true);
  notice("");
  try { await action(); }
  catch (err) { notice(err.message); }
  finally { buttons.forEach(button => button.disabled = false); }
}
el("login").onsubmit = event => {
  event.preventDefault();
  run(async () => {
    await api("/api/session/login","POST",new URLSearchParams(new FormData(el("login"))));
    el("login").reset();
    await init();
  });
};
el("logout").onclick = () => run(async () => {
  await api("/api/session/logout","POST");
  el("question").value = "";
  await init();
});
el("fresh").onclick = () => run(async () => { await newChat(); el("question").value = ""; });
el("compose").onsubmit = event => {
  event.preventDefault();
  run(async () => {
    const text = el("question").value.trim();
    if (!text) return;
    if (!pending || pending.text !== text || pending.version !== chat.version)
      pending = {text,version:chat.version,messageId:crypto.randomUUID()};
    notice("正在查询…");
    try {
      render(await api("/api/conversations/" + chat.id + "/messages","POST",pending));
    } catch (err) {
      if (err.status === 404 || err.status === 409) {
        const before = chat.id;
        render(await api("/api/conversations/current","POST"));
        if (chat.id !== before) pending = null;
      }
      if (err.status === 401 || err.status === 403) await init();
      throw err;
    }
    pending = null;
    el("question").value = "";
    notice("");
  });
};
function selectedMode() {
  return stockModes.find(item => item.store.id === el("stock-store").value);
}
function renderStockMode() {
  const entry = selectedMode();
  if (!entry) return;
  el("stock-status").textContent = entry.current.paused ? "已暂停，请由店员核实库存" : "自动核验中";
  el("stock-action").textContent = entry.current.paused ? "恢复库存推荐" : "暂停库存推荐";
  el("stock-history").href = "/api/ops/stock-mode/" + encodeURIComponent(entry.store.id) + "/history";
}
async function loadStockModes() {
  const select = el("stock-store");
  const previous = select.value;
  stockModes = await api("/api/ops/stock-mode");
  select.replaceChildren(...stockModes.map(item => new Option(item.store.name,item.store.id)));
  if (stockModes.some(item => item.store.id === previous)) select.value = previous;
  renderStockMode();
}
el("stock-store").onchange = () => {
  el("stock-reason").value = "";
  renderStockMode();
};
el("stock-mode").onsubmit = event => {
  event.preventDefault();
  run(async () => {
    const entry = selectedMode();
    if (!entry) throw new Error("请刷新运营页面后再操作");
    const reason = el("stock-reason").value.trim();
    const change = {expectedRevision:entry.current.revision, paused:!entry.current.paused, reason};
    try {
      await api("/api/ops/stock-mode/" + encodeURIComponent(entry.store.id),"POST",change);
    } catch (err) {
      if (err.status === 409) await loadStockModes();
      throw err;
    }
    el("stock-reason").value = "";
    await loadStockModes();
    notice(entry.store.name + (change.paused ? "已切换为人工核实" : "已恢复自动库存核验"));
  });
};
async function loadPolicies() {
  const versions = await api("/api/ops/policies");
  const list = el("versions");
  list.replaceChildren();
  if (!versions.length) add(list,"p","暂无资料");
  for (const v of versions) {
    const row = add(list,"article");
    const status = {ACTIVE:"已发布",DRAFT:"待审核",ARCHIVED:"已归档"}[v.status];
    add(row,"strong",v.title+" · V"+v.version+" · "+status);
    add(row,"p",[v.key,v.scope,v.expiresOn || "长期有效"].join(" · "));
    const path = "/api/ops/policies/"+encodeURIComponent(v.key)+"/"+v.version;
    const original = add(row,"a","查看送审原文");
    original.href = path;
    original.target = "_blank";
    original.rel = "noopener";
    if (v.status === "ARCHIVED") continue;
    const publish = v.status === "DRAFT";
    const action = add(row,"button",publish ? "审核并发布" : "下线");
    action.className = "quiet";
    action.onclick = () => run(async () => {
      await api(path+(publish ? "/publish" : "/withdraw"),"POST");
      await loadPolicies();
      notice(publish ? "版本已发布" : "资料已下线");
    });
  }
}
el("ops-logout").onclick = el("logout").onclick;
el("upload").onsubmit = event => {
  event.preventDefault();
  run(async () => {
    const form = new FormData(el("upload"));
    const file = form.get("file");
    if (file.size > 262144) throw new Error("文件过大，请拆分 Markdown");
    const draft = {key:form.get("key"),title:form.get("title"),scope:form.get("scope"),
      expiresOn:form.get("expiresOn") || null,markdown:await file.text()};
    const result = await api("/api/ops/policies","POST",draft);
    await loadPolicies();
    notice("已导入草稿 V"+result.version);
  });
};
run(init);
