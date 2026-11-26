'use strict';
const $=selector=>document.querySelector(selector);
const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmtTime=v=>{if(!v)return '—';const d=new Date(v);return isNaN(d)?v:d.toLocaleString('zh-CN',{hour12:false});};
async function api(url,options={}){const r=await fetch(url,options);const data=await r.json();if(r.status===401){location.href='/login.html';throw Error('登录已失效');}if(!r.ok)throw Error(data.error||'操作失败');return data;}
function notice(message,error=false){$('#message').textContent=message;$('#message').className=error?'error':'';}
const customerId=new URLSearchParams(location.search).get('id');
let page=1;
async function loadPurchases(){
  const result=await api(`/api/admin/customers/${encodeURIComponent(customerId)}/orders?page=${page}&size=10`);
  $('#title').textContent=`${result.username} 的购买历史`;
  $('#content').innerHTML=result.items.length?`<div class="table-wrap"><table><thead><tr><th>商品名称</th><th>下单时间</th><th>交易结果</th><th>交易时间</th></tr></thead><tbody>${
    result.items.map(o=>`<tr><td>${esc(o.productName)}</td><td>${esc(fmtTime(o.submittedAt))}</td><td>${esc(o.result)}</td><td>${esc(fmtTime(o.closedAt))}</td></tr>`).join('')
  }</tbody></table></div>`:'<p class="muted">该客户还没有成功或失败的下单记录。</p>';
  $('#pagination').innerHTML=result.items.length?`<button class="secondary" id="previous" ${page<=1?'disabled':''}>上一页</button><span>第 ${page} / ${Math.max(1,result.totalPages)} 页，共 ${result.total} 条</span><button class="secondary" id="next" ${page>=result.totalPages?'disabled':''}>下一页</button>`:'';
  const previous=$('#previous'),next=$('#next');
  if(previous)previous.onclick=()=>{page--;loadPurchases().catch(e=>notice(e.message,true));};
  if(next)next.onclick=()=>{page++;loadPurchases().catch(e=>notice(e.message,true));};
}
$('#refresh').onclick=()=>loadPurchases().then(()=>notice('已刷新')).catch(e=>notice(e.message,true));
$('#logout').onclick=async()=>{try{await api('/api/auth/logout',{method:'POST'});location.href='/login.html';}catch(e){notice(e.message,true);}};
(async()=>{
  try{await api('/api/auth/session');}
  catch(e){location.href='/login.html';return;}
  if(!customerId){notice('缺少客户参数，请从客户列表进入',true);return;}
  loadPurchases().catch(e=>notice(e.message,true));
})();
