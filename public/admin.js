'use strict';
const $=selector=>document.querySelector(selector);
const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let tree=[], inventoryPage=1, previewUrls=[];
const states={active:'在售',resumed:'在售',frozen:'已冻结',sold:'已售（终态）',depleted:'售罄下架',withdrawn:'手动下架'};
async function api(url,options={}){const r=await fetch(url,options);const data=await r.json();if(r.status===401){location.href='/login.html';throw Error('登录已失效');}if(!r.ok){const e=Error(data.error||'操作失败');e.fields=data.fieldErrors||{};throw e;}return data;}
function json(method,body){return {method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)};}
function notice(message,error=false){$('#notice').textContent=message;$('#notice').className=error?'error':'';}
function tab(name){document.querySelectorAll('main>section:not(.intro)').forEach(section=>section.hidden=section.id!==name);document.querySelectorAll('[data-tab]').forEach(button=>button.classList.toggle('secondary',button.dataset.tab!==name));location.hash=name;notice('');}
document.querySelectorAll('[data-tab]').forEach(button=>button.onclick=()=>tab(button.dataset.tab));
async function loadCategories(){tree=await api('/api/admin/categories');const options='<option value="">请选择二级类别</option>'+tree.map(parent=>`<optgroup label="${esc(parent.name)}">${parent.children.map(c=>`<option value="${c.id}">${esc(c.name)}</option>`).join('')}</optgroup>`).join('');document.querySelectorAll('.leaf-options').forEach(select=>{const old=select.value;select.innerHTML=options;select.value=old;});$('#parent-category').innerHTML='<option value="">无，创建一级类别</option>'+tree.map(c=>`<option value="${c.id}">${esc(c.name)}</option>`).join('');$('#category-tree').innerHTML=tree.length?tree.map(parent=>`<div class="category-group">${row(parent,false)}${parent.children.map(c=>row(c,true)).join('')}</div>`).join(''):'<p class="muted">暂无类别，请先创建一级类别，再创建二级类别。</p>';}
function row(c,child){return `<div class="category-row ${child?'child':''}"><span>${child?'└ ':''}${esc(c.name)}</span><div class="actions"><button class="secondary" data-rename="${c.id}">重命名</button><button class="secondary" data-delete="${c.id}">删除</button></div></div>`;}
async function loadInventory(){const result=await api(`/api/admin/products?page=${inventoryPage}`);$('#inventory-body').innerHTML=result.items.length?result.items.map(p=>`<tr><td>${esc(p.name)}</td><td>¥${(p.priceCents/100).toFixed(2)}</td><td>${esc(p.category.parentName)} / ${esc(p.category.name)}</td><td>${p.stock}</td><td>${states[p.status]||esc(p.status)}</td><td><button class="secondary" data-edit="${p.id}">编辑</button></td></tr>`).join(''):'<tr><td colspan="6">暂无商品，请先建立二级类别并发布商品。</td></tr>';$('#admin-pagination').innerHTML=`<button class="secondary" id="admin-prev" ${inventoryPage<=1?'disabled':''}>上一页</button><span>第 ${inventoryPage} / ${Math.max(1,result.totalPages)} 页，共 ${result.total} 件</span><button class="secondary" id="admin-next" ${inventoryPage>=result.totalPages?'disabled':''}>下一页</button>`;$('#admin-prev').onclick=()=>{inventoryPage--;loadInventory().catch(e=>notice(e.message,true));};$('#admin-next').onclick=()=>{inventoryPage++;loadInventory().catch(e=>notice(e.message,true));};}
$('#refresh').onclick=()=>loadInventory().catch(e=>notice(e.message,true));
$('#logout').onclick=async()=>{try{await api('/api/auth/logout',{method:'POST'});location.href='/login.html';}catch(e){notice(e.message,true);}};
$('#category-form').onsubmit=async event=>{event.preventDefault();const button=event.submitter;button.disabled=true;const values=Object.fromEntries(new FormData(event.target));try{await api('/api/admin/categories',json('POST',{name:values.name,parentId:values.parentId?Number(values.parentId):null}));event.target.reset();await loadCategories();notice('类别已创建');}catch(e){notice(e.message,true);}finally{button.disabled=false;}};
$('#category-tree').onclick=async event=>{const rename=event.target.closest('[data-rename]'),remove=event.target.closest('[data-delete]');if(rename){const id=Number(rename.dataset.rename),category=tree.flatMap(c=>[c,...c.children]).find(c=>c.id===id);$('#rename-form').elements.id.value=id;$('#rename-form').elements.name.value=category.name;$('#rename-error').textContent='';$('#category-dialog').showModal();}if(remove){const id=Number(remove.dataset.delete);if(!confirm('确认删除这个类别？挂有商品或子类别时会拒绝删除。'))return;try{await api(`/api/admin/categories/${id}`,{method:'DELETE'});await loadCategories();notice('类别已删除');}catch(e){notice(e.message,true);}}};
$('#rename-form').onsubmit=async event=>{event.preventDefault();const form=event.target;try{await api(`/api/admin/categories/${form.elements.id.value}`,json('PATCH',{name:form.elements.name.value}));$('#category-dialog').close();await loadCategories();await loadInventory();notice('类别名称已更新');}catch(e){$('#rename-error').textContent=e.message;}};
$('#close-category').onclick=()=>$('#category-dialog').close();
$('#inventory-body').onclick=async event=>{const button=event.target.closest('[data-edit]');if(!button)return;try{const p=await api(`/api/admin/products/${button.dataset.edit}`);const form=$('#edit-form');form.elements.id.value=p.id;form.elements.version.value=p.version;form.elements.stock.value=p.stock;form.elements.categoryId.value=p.category.id;$('#edit-name').textContent=p.name;$('#edit-error').textContent='';$('#edit-dialog').showModal();}catch(e){notice(e.message,true);}};
$('#close-edit').onclick=()=>$('#edit-dialog').close();
$('#edit-form').onsubmit=async event=>{event.preventDefault();const form=event.target;event.submitter.disabled=true;try{await api(`/api/admin/products/${form.elements.id.value}`,json('PATCH',{stock:Number(form.elements.stock.value),categoryId:Number(form.elements.categoryId.value),version:Number(form.elements.version.value)}));$('#edit-dialog').close();await loadInventory();notice('库存与类别已保存');}catch(e){$('#edit-error').textContent=e.message;}finally{event.submitter.disabled=false;}};
function imageError(files){if(files.length>9)return '商品图片最多 9 张';for(const f of files){if(!['image/jpeg','image/png'].includes(f.type)||!(/\.(jpe?g|png)$/i.test(f.name)))return '仅支持 JPG/PNG 图片';if(f.size>5*1024*1024)return '单张图片不得超过 5MB';}return '';}
$('#images').onchange=()=>{previewUrls.forEach(URL.revokeObjectURL);previewUrls=[];const files=Array.from($('#images').files),error=imageError(files);$('[data-error="images"]').textContent=error;$('#image-preview').innerHTML=error?'':files.map((f,i)=>{const url=URL.createObjectURL(f);previewUrls.push(url);return `<figure><img src="${url}" alt="${esc(f.name)}"><figcaption>${i===0?'主图':'商品图 '+(i+1)}</figcaption></figure>`;}).join('');};
document.querySelectorAll('[data-format]').forEach(button=>button.onmousedown=event=>{event.preventDefault();const selection=getSelection();if(!selection.rangeCount)return;const range=selection.getRangeAt(0);if(!$('#editor').contains(range.commonAncestorContainer))return;const node=document.createElement(button.dataset.format);node.appendChild(range.extractContents());if(!node.textContent)node.textContent='请输入文字';range.insertNode(node);selection.removeAllRanges();const next=document.createRange();next.selectNodeContents(node);selection.addRange(next);});
$('#editor').addEventListener('paste',event=>{event.preventDefault();const selection=getSelection();if(!selection.rangeCount)return;const range=selection.getRangeAt(0);range.deleteContents();const node=document.createTextNode(event.clipboardData.getData('text/plain'));range.insertNode(node);range.setStartAfter(node);range.collapse(true);selection.removeAllRanges();selection.addRange(range);});
$('#inline-image').onchange=async event=>{const file=event.target.files[0];if(!file)return;const error=imageError([file]);if(error){notice(error,true);return;}const data=new FormData();data.append('image',file);try{const result=await api('/api/admin/description-images',{method:'POST',body:data});const img=document.createElement('img');img.src=result.url;img.alt='商品描述插图';$('#editor').appendChild(img);notice('描述图片已插入');}catch(e){notice(e.message,true);}finally{event.target.value='';}};
$('#publish-form').onsubmit=async event=>{event.preventDefault();const form=event.target;form.querySelectorAll('[data-error]').forEach(node=>node.textContent='');const data=new FormData(form);data.set('description',$('#editor').innerHTML);if(!$('#images').files.length)data.delete('images');const error=imageError(Array.from($('#images').files));if(error){$('[data-error="images"]').textContent=error;return;}event.submitter.disabled=true;try{const result=await api('/api/products',{method:'POST',body:data});form.reset();$('#editor').replaceChildren();$('#image-preview').replaceChildren();previewUrls.forEach(URL.revokeObjectURL);previewUrls=[];inventoryPage=1;await loadInventory();tab('inventory');notice(`商品“${result.name}”已发布`);}catch(e){for(const [field,message]of Object.entries(e.fields||{})){const target=form.querySelector(`[data-error="${field}"]`);if(target)target.textContent=message;}if(!Object.keys(e.fields||{}).length)$('[data-error="form"]').textContent=e.message;notice(e.message,true);}finally{event.submitter.disabled=false;}};
/* ===== V2.0 交易模块（陈心冉）===== */
function tradeButtons(item){
  const status=item.status, stock=item.stock||0, queue=item.queueCount||0, buttons=[];
  if((status==='active'||status==='resumed')&&stock>0&&queue>0) buttons.push('<button data-trade="start">发起交易</button>');
  if(status==='frozen'&&item.trading){
    buttons.push('<button data-trade="success">标记交易成功</button>');
    buttons.push('<button class="secondary" data-trade="fail-void">交易失败 · 作废</button>');
    buttons.push('<button class="secondary" data-trade="fail-requeue">交易失败 · 重新排队</button>');
  }
  if(status==='active'||status==='resumed'||status==='frozen') buttons.push('<button class="secondary" data-trade="withdraw">手动下架</button>');
  if(status==='depleted'){
    buttons.push('<button data-trade="restock">补货</button>');
    if(stock>0) buttons.push('<button class="secondary" data-trade="relist">重新上架</button>');
  }
  return buttons.join('')||'<span class="muted">暂无可执行操作</span>';
}
async function loadTrade(){
  const items=await api('/api/admin/trades');
  $('#trade-list').innerHTML=items.length?items.map(item=>{
    const queue=item.queue.map(q=>`<li><span>第 ${q.position} 位 · ${esc(q.name)} · ${esc(q.phone)}</span><span class="muted">${esc(q.submittedAt)}</span></li>`).join('');
    return `<div class="trade-card" data-id="${item.id}"><div class="trade-head"><div><h3 style="margin:0;font-size:19px">${esc(item.name)}</h3><div class="trade-meta"><span>库存 ${item.stock}</span><span>状态 ${states[item.status]||esc(item.status)}</span><span>队列 ${item.queueCount} 人</span>${item.trading?`<span>交易中：${esc(item.trading.name)} · ${esc(item.trading.phone)}</span>`:''}</div></div><span class="badge">#${item.id}</span></div>${queue?`<ul class="trade-queue">${queue}</ul>`:'<p class="muted">当前没有排队中的意向。</p>'}<div class="trade-actions">${tradeButtons(item)}</div></div>`;
  }).join(''):'<p class="muted">没有需要处理的商品。</p>';
  await loadTradeHistory();
}
async function loadTradeHistory(){
  const items=await api('/api/admin/product-history');
  $('#trade-history').innerHTML=items.length?`<div class="table-wrap"><table><thead><tr><th>商品</th><th>库存</th><th>状态</th><th>交易结果</th><th>结果时间</th></tr></thead><tbody>${items.map(p=>`<tr><td>${esc(p.name)}</td><td>${p.stock}</td><td>${states[p.status]||esc(p.status)}</td><td>${p.result==='success'?'成功':'失败'}</td><td>${esc(p.resultAt||'—')}</td></tr>`).join('')}</tbody></table></div>`:'<p class="muted">暂无历史商品。</p>';
}
$('#refresh-trade').onclick=()=>loadTrade().then(()=>notice('已刷新')).catch(e=>notice(e.message,true));
$('#trade-list').onclick=async event=>{
  const button=event.target.closest('[data-trade]'); if(!button) return;
  const card=button.closest('.trade-card'), id=Number(card.dataset.id), type=button.dataset.trade;
  const name=card.querySelector('h3').textContent;
  let url='', body=null, tip='';
  if(type==='start'){ if(!confirm(`向「${name}」的队首买家发起交易？商品将立即冻结。`))return; url=`/api/admin/trades/${id}/start`; tip='已向队首买家发起交易，商品已冻结'; }
  else if(type==='success'){ if(!confirm('标记交易成功后库存减 1 且不可撤销，确认？'))return; url=`/api/admin/trades/${id}/success`; tip='交易已标记成功'; }
  else if(type==='fail-void'){ if(!confirm('标记交易失败，并把该意向作废？商品将恢复在售。'))return; url=`/api/admin/trades/${id}/fail`; body={disposition:'void'}; tip='交易已标记失败，该意向已作废'; }
  else if(type==='fail-requeue'){ if(!confirm('标记交易失败，并让该买家重新排到队尾？'))return; url=`/api/admin/trades/${id}/fail`; body={disposition:'requeue'}; tip='交易已标记失败，该买家已重新排队'; }
  else if(type==='withdraw'){
    const choice=prompt('手动下架：请对未终结意向选择处置方式（输入 void 作废 / requeue 重新排队）','void');
    if(!choice)return; url=`/api/admin/products/${id}/withdraw`; body={disposition:choice.trim()}; tip='商品已手动下架';
  }
  else if(type==='restock'){
    const value=prompt('补货后库存数量（正整数）。补货后商品仍为已下架，需再点「重新上架」。','1');
    if(!value)return; url=`/api/admin/products/${id}/restock`; body={stock:Number(value)}; tip='已补货，商品仍为已下架';
  }
  else if(type==='relist'){ if(!confirm('重新上架后重新接收意向，原队列不恢复，确认？'))return; url=`/api/admin/products/${id}/relist`; tip='商品已重新上架'; }
  else return;
  button.disabled=true;
  try{ await api(url, body?json('POST',body):{method:'POST'}); await loadInventory(); await loadTrade(); notice(tip); }
  catch(e){ button.disabled=false; notice(e.message,true); }
};
Promise.all([loadCategories(),loadInventory(),loadTrade()]).then(()=>tab(['publish','categories','trade'].includes(location.hash.slice(1))?location.hash.slice(1):'inventory')).catch(e=>notice(e.message,true));
