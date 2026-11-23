const el = id => document.getElementById(id);
let session, chat, pending;
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
  sessionStorage.setItem("conversation",chat.id);
  const history = el("history");
  history.replaceChildren();
  for (const turn of chat.turns) {
    const box = add(history,"article");
    add(box,"strong",turn.question);
    add(box,"p",turn.reply.text);
    const rows = turn.reply.products;
    if (rows.length) {
      const table = add(box,"table");
      const header = add(table,"tr");
      ["商品","单包售价","库存"].forEach(title => add(header,"th",title));
      if (turn.reply.quantity) add(header,"th","购买合计");
      for (const item of rows) {
        const row = add(table,"tr");
        [item.name,"¥" + item.price,item.packs + " 包"].forEach(value => add(row,"td",value));
        if (turn.reply.quantity) add(row,"td",item.price + " × " + turn.reply.quantity + " = " + turn.reply.totals[item.sku] + " 元");
      }
      add(box,"small","模拟门店数据 · 快照 " + rows[0].snapshot + " · 本次查询 " +
        new Date(rows[0].queriedAt).toLocaleString("zh-CN"));
    }
    if (turn.reply.trace?.length) {
      const details = add(box,"details");
      add(details,"summary","查看核验步骤");
      const steps = add(details,"ol");
      turn.reply.trace.forEach(step => add(steps,"li",step));
    }
    for (const cite of turn.reply.citations) {
      const source = add(box,"a",cite.title + " · 摘要 v" + cite.version + " · 查看原文");
      source.href = cite.source;
      source.target = "_blank";
      source.rel = "noopener noreferrer";
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
  el("desk").hidden = !session.authenticated;
  el("location").textContent = session.store?.name || "门店导购工作台";
  if (!session.authenticated) {
    sessionStorage.removeItem("conversation");
    el("history").replaceChildren();
    chat = pending = null;
    return;
  }
  const id = sessionStorage.getItem("conversation");
  if (id) {
    try { render(await api("/api/conversations/" + id)); return; }
    catch (err) { if (err.status !== 404) throw err; }
  }
  await newChat();
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
      if (err.status === 409) render(await api("/api/conversations/" + chat.id));
      if (err.status === 401 || err.status === 403) await init();
      throw err;
    }
    pending = null;
    el("question").value = "";
    notice("");
  });
};
run(init);
