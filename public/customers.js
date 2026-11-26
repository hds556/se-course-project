'use strict';
const $=selector=>document.querySelector(selector);
const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmtTime=v=>{if(!v)return '—';const d=new Date(v);return isNaN(d)?v:d.toLocaleString('zh-CN',{hour12:false});};
async function api(url,options={}){const r=await fetch(url,options);const data=await r.json();if(r.status===401){location.href='/login.html';throw Error('登录已失效');}if(!r.ok)throw Error(data.error||'操作失败');return data;}
function notice(message,error=false){$('#message').textContent=message;$('#message').className=error?'error':'';}
let page=1;
async function loadCustomers(){
  const result=await api(`/api/admin/customers?page=${page}&size=10`);
  $('#content').innerHTML=result.items.length?`<div class="table-wrap"><table><thead><tr><th>用户名</th><th>电话</th><th>默认交易地点</th><th>注册时间</th><th>操作</th></tr></thead><tbody>${
    result.items.map(c=>`<tr><td>${esc(c.username)}</td><td>${esc(c.phone)}</td><td>${esc(c.defaultPlace)}</td><td>${esc(fmtTime(c.registeredAt))}</td><td><button class="secondary" data-orders="${c.id}">查看购买历史</button></td></tr>`).join('')
  }</tbody></table></div>`:'<p class="muted">还没有注册客户，客户在前台注册后会出现在这里。</p>';
  $('#pagination').innerHTML=result.items.length?`<button class="secondary" id="previous" ${page<=1?'disabled':''}>上一页</button><span>第 ${page} / ${Math.max(1,result.totalPages)} 页，共 ${result.total} 位客户</span><button class="secondary" id="next" ${page>=result.totalPages?'disabled':''}>下一页</button>`:'';
  const previous=$('#previous'),next=$('#next');
  if(previous)previous.onclick=()=>{page--;loadCustomers().catch(e=>notice(e.message,true));};
  if(next)next.onclick=()=>{page++;loadCustomers().catch(e=>notice(e.message,true));};
}
$('#content').onclick=event=>{const button=event.target.closest('[data-orders]');if(button)location.href=`/customer-purchases.html?id=${button.dataset.orders}`;};
$('#refresh').onclick=()=>loadCustomers().then(()=>notice('已刷新')).catch(e=>notice(e.message,true));
$('#logout').onclick=async()=>{try{await api('/api/auth/logout',{method:'POST'});location.href='/login.html';}catch(e){notice(e.message,true);}};
(async()=>{
  try{await api('/api/auth/session');}
  catch(e){location.href='/login.html';return;}
  loadCustomers().catch(e=>notice(e.message,true));
})();
