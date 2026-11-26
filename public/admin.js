'use strict';
const $=selector=>document.querySelector(selector);
const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmtTime=v=>{if(!v)return '—';const d=new Date(v);return isNaN(d)?v:d.toLocaleString('zh-CN',{hour12:false});};
let tree=[], inventoryPage=1, customersPage=1, previewUrls=[];
let inventoryItems=[], historyItems=[], stockProduct=null, inventoryRequest=0, historyRequest=0;
const states={active:'在售',depleted:'售罄下架',withdrawn:'手动下架'};
const offlineReasons={sold_out:'售罄下架',manual:'手动下架'};
async function api(url,options={}){const r=await fetch(url,options);const data=await r.json();if(r.status===401){location.href='/login.html';throw Error('登录已失效');}if(!r.ok){const e=Error(data.error||'操作失败');e.fields=data.fieldErrors||{};throw e;}return data;}
function json(method,body){return {method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)};}
function notice(message,error=false){$('#notice').textContent=message;$('#notice').className=error?'error':'';}
function tab(name){document.querySelectorAll('main>section:not(.intro)').forEach(section=>section.hidden=section.id!==name);document.querySelectorAll('[data-tab]').forEach(button=>{const selected=button.dataset.tab===name;button.classList.toggle('secondary',!selected);button.setAttribute('aria-pressed',String(selected));});location.hash=name;notice('');}
document.querySelectorAll('[data-tab]').forEach(button=>button.onclick=()=>{
 const name=button.dataset.tab;tab(name);
 if(name==='inventory'||name==='history') (name==='inventory'?loadInventory():loadHistory()).catch(e=>notice(e.message,true));
});
async function loadCategories(){tree=await api('/api/admin/categories');const options='<option value="">请选择二级类别</option>'+tree.map(parent=>`<optgroup label="${esc(parent.name)}">${parent.children.map(c=>`<option value="${c.id}">${esc(c.name)}</option>`).join('')}</optgroup>`).join('');document.querySelectorAll('.leaf-options').forEach(select=>{const old=select.value;select.innerHTML=options;select.value=old;});$('#parent-category').innerHTML='<option value="">无，创建一级类别</option>'+tree.map(c=>`<option value="${c.id}">${esc(c.name)}</option>`).join('');$('#category-tree').innerHTML=tree.length?tree.map(parent=>`<div class="category-group">${row(parent,false)}${parent.children.map(c=>row(c,true)).join('')}</div>`).join(''):'<p class="muted">暂无类别，请先创建一级类别，再创建二级类别。</p>';}
function row(c,child){return `<div class="category-row ${child?'child':''}"><span>${child?'└ ':''}${esc(c.name)}</span><div class="actions"><button class="secondary" data-rename="${c.id}">重命名</button><button class="secondary" data-delete="${c.id}">删除</button></div></div>`;}
function productCells(p){
 const category=[p.category?.parentName,p.category?.name].filter(Boolean).map(esc).join(' / ')||'—';
 return `<td>${esc(p.name)}</td><td>¥${(p.priceCents/100).toFixed(2)}</td><td>${category}</td><td>${esc(p.stock??'—')}</td>`;
}
async function loadInventory(){
 const page=inventoryPage, request=++inventoryRequest;
 const result=await api(`/api/admin/products?page=${page}`);
 if(request!==inventoryRequest)return;
 const pages=Math.max(1,result.totalPages);
 if(page>pages){inventoryPage=pages;return loadInventory();}
 inventoryItems=result.items;
 $('#inventory-body').innerHTML=inventoryItems.length?inventoryItems.map(p=>`<tr>${productCells(p)}<td>${states[p.status]||esc(p.status)}</td><td><div class="actions product-actions"><button class="secondary" data-edit="${esc(p.id)}">编辑</button><button class="secondary" data-stock="${esc(p.id)}">调整库存</button><button class="secondary" data-withdraw="${esc(p.id)}">手动下架</button></div></td></tr>`).join(''):'<tr><td colspan="6">暂无在售商品。可发布新商品，或为历史商品中的售罄商品调整库存。</td></tr>';
 $('#admin-pagination').innerHTML=`<button class="secondary" id="admin-prev" ${page<=1?'disabled':''}>上一页</button><span>第 ${page} / ${pages} 页，共 ${result.total} 件</span><button class="secondary" id="admin-next" ${page>=pages?'disabled':''}>下一页</button>`;
 $('#admin-prev').onclick=()=>{inventoryPage=page-1;loadInventory().catch(e=>notice(e.message,true));};
 $('#admin-next').onclick=()=>{inventoryPage=page+1;loadInventory().catch(e=>notice(e.message,true));};
}
const canAdjustOffline=p=>p.status==='depleted'&&p.stock!=null&&p.category!=null;
async function loadHistory(){
 const request=++historyRequest, items=await api('/api/admin/products/offline');
 if(request!==historyRequest)return;
 historyItems=items;
 $('#history-body').innerHTML=historyItems.length?historyItems.map(p=>{
  const why=offlineReasons[p.offlineReason]||states[p.status]||'—';
  const action=canAdjustOffline(p)?`<button class="secondary" data-stock="${esc(p.id)}">调整库存</button>`:'<span class="muted">仅查看</span>';
  return `<tr>${productCells(p)}<td>${why}</td><td>${esc(fmtTime(p.offlineAt))}</td><td><div class="actions product-actions">${action}</div></td></tr>`;
 }).join(''):'<tr><td colspan="7">暂无历史商品。</td></tr>';
}
const loadProductLists=()=>Promise.all([loadInventory(),loadHistory()]);
$('#refresh').onclick=()=>loadInventory().catch(e=>notice(e.message,true));
$('#refresh-history').onclick=()=>loadHistory().catch(e=>notice(e.message,true));
$('#logout').onclick=async()=>{try{await api('/api/auth/logout',{method:'POST'});location.href='/login.html';}catch(e){notice(e.message,true);}};
$('#category-form').onsubmit=async event=>{event.preventDefault();const button=event.submitter;button.disabled=true;const values=Object.fromEntries(new FormData(event.target));try{await api('/api/admin/categories',json('POST',{name:values.name,parentId:values.parentId?Number(values.parentId):null}));event.target.reset();await loadCategories();notice('类别已创建');}catch(e){notice(e.message,true);}finally{button.disabled=false;}};
$('#category-tree').onclick=async event=>{const rename=event.target.closest('[data-rename]'),remove=event.target.closest('[data-delete]');if(rename){const id=Number(rename.dataset.rename),category=tree.flatMap(c=>[c,...c.children]).find(c=>c.id===id);$('#rename-form').elements.id.value=id;$('#rename-form').elements.name.value=category.name;$('#rename-error').textContent='';$('#category-dialog').showModal();}if(remove){const id=Number(remove.dataset.delete);if(!confirm('确认删除这个类别？挂有商品或子类别时会拒绝删除。'))return;try{await api(`/api/admin/categories/${id}`,{method:'DELETE'});await loadCategories();notice('类别已删除');}catch(e){notice(e.message,true);}}};
$('#rename-form').onsubmit=async event=>{event.preventDefault();const form=event.target;try{await api(`/api/admin/categories/${form.elements.id.value}`,json('PATCH',{name:form.elements.name.value}));$('#category-dialog').close();await loadCategories();await loadProductLists();notice('类别名称已更新');}catch(e){$('#rename-error').textContent=e.message;}};
$('#close-category').onclick=()=>$('#category-dialog').close();
let editPreviewUrls=[];
$('#inventory-body').onclick=async event=>{
 const stockButton=event.target.closest('[data-stock]');
 if(stockButton){openStock(inventoryItems.find(p=>String(p.id)===stockButton.dataset.stock));return;}
 const withdrawButton=event.target.closest('[data-withdraw]');
 if(withdrawButton){await withdrawProduct(withdrawButton);return;}
 const button=event.target.closest('[data-edit]');if(!button)return;
 try{const p=await api(`/api/admin/products/${button.dataset.edit}`),form=$('#edit-form');
 form.reset();form.elements.id.value=p.id;form.elements.version.value=p.version;
 form.elements.name.value=p.name;form.elements.price.value=(p.priceCents/100).toFixed(2);form.elements.categoryId.value=p.category.id;
 $('#edit-editor').innerHTML=p.description;$('#edit-name').textContent=`${states[p.status]} · 库存 ${p.stock} 件（由库存模块维护）`;
 const readOnly=p.status!=='active'||p.stock<=0;
 $('#edit-fields').disabled=readOnly;$('#edit-editor').contentEditable=String(!readOnly);
 $('#edit-error').textContent=readOnly?'已下架商品只读，不能保存修改。':'';
 $('#retained-images').innerHTML=p.images.map((src,i)=>`<label><img src="${esc(src)}" alt="商品图 ${i+1}"><input type="checkbox" name="retainedImages" value="${esc(src)}" checked>保留第 ${i+1} 张</label>`).join('');
 $('#edit-preview').replaceChildren();editPreviewUrls.forEach(URL.revokeObjectURL);editPreviewUrls=[];
 $('#edit-dialog').showModal();
 }catch(e){notice(e.message,true);}
};
$('#close-edit').onclick=()=>$('#edit-dialog').close();
$('#history-body').onclick=event=>{
 const button=event.target.closest('[data-stock]');
 if(button)openStock(historyItems.find(p=>String(p.id)===button.dataset.stock&&canAdjustOffline(p)));
};
function openStock(product){
 if(!product)return;
 stockProduct=product;
 $('#stock-product').textContent=`${product.name} · 当前可售库存 ${product.stock} 件`;
 $('#stock-help').textContent=product.status==='depleted'
  ?'库存改为正整数后，商品自动恢复在售。'
  :'库存改为 0 时，商品自动下架；存在已支付/商家确认/备货完成订单时会被拒绝，未付款订单不受影响。';
 $('#stock-form').elements.stock.value=product.stock;
 $('#stock-error').textContent='';
 $('#stock-dialog').showModal();
}
$('#close-stock').onclick=()=>$('#stock-dialog').close();
$('#stock-dialog').addEventListener('cancel',event=>{if($('#save-stock').disabled)event.preventDefault();});
$('#stock-form').onsubmit=async event=>{
 event.preventDefault();if(!stockProduct)return;
 const product=stockProduct, raw=event.target.elements.stock.value.trim(), stock=Number(raw);
 const error=$('#stock-error'), button=$('#save-stock'), close=$('#close-stock');
 if(!/^\d+$/.test(raw)){error.textContent='请输入非负整数。';return;}
 if(!Number.isSafeInteger(stock)){error.textContent='库存超过可保存的最大值 9007199254740991。';return;}
 const body={stock};if(Number.isSafeInteger(product.version))body.version=product.version;
 error.textContent='';button.disabled=true;close.disabled=true;
 try{
  await api(`/api/admin/products/${product.id}/stock`,json('PATCH',body));
  $('#stock-dialog').close();
  const message=stock===0&&product.status==='active'?'库存已调为 0，商品已售罄下架。'
   :stock>0&&product.status==='depleted'?'库存已更新，商品已自动恢复在售。':'库存已更新。';
  try{await loadProductLists();notice(message);}catch(e){notice(`${message}列表未刷新：${e.message}`,true);}
 }catch(e){error.textContent=e.fields?.stock||e.message;}
 finally{button.disabled=false;close.disabled=false;}
};
async function withdrawProduct(button){
 const product=inventoryItems.find(p=>String(p.id)===button.dataset.withdraw);
 if(!product||!confirm(`确定手动下架「${product.name}」？下架后无法恢复在售，已有订单可照常处理；若存在「已支付/商家确认/备货完成」订单，系统会拒绝下架。`))return;
 const body={};if(Number.isSafeInteger(product.version))body.version=product.version;
 button.disabled=true;
 try{
  await api(`/api/admin/products/${product.id}/withdraw`,json('POST',body));
  try{await loadProductLists();notice('商品已手动下架，可在历史商品中查看。');}
  catch(e){notice(`商品已手动下架，列表未刷新：${e.message}`,true);}
 }catch(e){notice(e.message,true);}
 finally{button.disabled=false;}
}
$('#edit-images').onchange=()=>{
 editPreviewUrls.forEach(URL.revokeObjectURL);editPreviewUrls=[];
 const files=Array.from($('#edit-images').files),error=imageError(files);
 $('#edit-error').textContent=error;
 $('#edit-preview').innerHTML=error?'':files.map(f=>{const url=URL.createObjectURL(f);editPreviewUrls.push(url);return `<img src="${url}" alt="新增商品图">`;}).join('');
};
$('#edit-form').onsubmit=async event=>{
 event.preventDefault();const form=event.target,button=$('#save-content');
 const files=Array.from($('#edit-images').files),kept=form.querySelectorAll('[name="retainedImages"]:checked').length;
 const error=imageError(files)||(kept+files.length>9?'商品图片最多 9 张':'');
 if(error){$('#edit-error').textContent=error;return;}
 const data=new FormData(form);data.delete('id');data.set('description',$('#edit-editor').innerHTML);if(!files.length)data.delete('images');
 button.disabled=true;
 try{await api(`/api/admin/products/${form.elements.id.value}/content`,{method:'PUT',body:data});$('#edit-dialog').close();await loadInventory();notice('商品信息已保存，已有订单保留原始快照');}
 catch(e){$('#edit-error').textContent=e.message;}
 finally{button.disabled=false;}
};
document.querySelectorAll('[data-edit-format]').forEach(button=>button.onmousedown=event=>{
 event.preventDefault();const selection=getSelection();if(!selection.rangeCount||$('#edit-fields').disabled)return;
 const range=selection.getRangeAt(0);if(!$('#edit-editor').contains(range.commonAncestorContainer))return;
 const node=document.createElement(button.dataset.editFormat);node.appendChild(range.extractContents());range.insertNode(node);
});
$('#edit-editor').addEventListener('paste',event=>{
 event.preventDefault();const selection=getSelection();if(!selection.rangeCount)return;
 const range=selection.getRangeAt(0);if(!$('#edit-editor').contains(range.commonAncestorContainer))return;
 range.deleteContents();range.insertNode(document.createTextNode(event.clipboardData.getData('text/plain')));
});
$('#edit-inline-image').onchange=async event=>{
 const file=event.target.files[0];if(!file)return;const error=imageError([file]);
 if(error){$('#edit-error').textContent=error;return;}
 const data=new FormData();data.append('image',file);
 try{const result=await api('/api/admin/description-images',{method:'POST',body:data});const img=document.createElement('img');img.src=result.url;img.alt='商品描述插图';$('#edit-editor').appendChild(img);}
 catch(e){$('#edit-error').textContent=e.message;}finally{event.target.value='';}
};
function imageError(files){if(files.length>9)return '商品图片最多 9 张';for(const f of files){if(!['image/jpeg','image/png'].includes(f.type)||!(/\.(jpe?g|png)$/i.test(f.name)))return '仅支持 JPG/PNG 图片';if(f.size>5*1024*1024)return '单张图片不得超过 5MB';}return '';}
$('#images').onchange=()=>{previewUrls.forEach(URL.revokeObjectURL);previewUrls=[];const files=Array.from($('#images').files),error=imageError(files);$('[data-error="images"]').textContent=error;$('#image-preview').innerHTML=error?'':files.map((f,i)=>{const url=URL.createObjectURL(f);previewUrls.push(url);return `<figure><img src="${url}" alt="${esc(f.name)}"><figcaption>${i===0?'主图':'商品图 '+(i+1)}</figcaption></figure>`;}).join('');};
document.querySelectorAll('[data-format]').forEach(button=>button.onmousedown=event=>{event.preventDefault();const selection=getSelection();if(!selection.rangeCount)return;const range=selection.getRangeAt(0);if(!$('#editor').contains(range.commonAncestorContainer))return;const node=document.createElement(button.dataset.format);node.appendChild(range.extractContents());if(!node.textContent)node.textContent='请输入文字';range.insertNode(node);selection.removeAllRanges();const next=document.createRange();next.selectNodeContents(node);selection.addRange(next);});
$('#editor').addEventListener('paste',event=>{event.preventDefault();const selection=getSelection();if(!selection.rangeCount)return;const range=selection.getRangeAt(0);range.deleteContents();const node=document.createTextNode(event.clipboardData.getData('text/plain'));range.insertNode(node);range.setStartAfter(node);range.collapse(true);selection.removeAllRanges();selection.addRange(range);});
$('#inline-image').onchange=async event=>{const file=event.target.files[0];if(!file)return;const error=imageError([file]);if(error){notice(error,true);return;}const data=new FormData();data.append('image',file);try{const result=await api('/api/admin/description-images',{method:'POST',body:data});const img=document.createElement('img');img.src=result.url;img.alt='商品描述插图';$('#editor').appendChild(img);notice('描述图片已插入');}catch(e){notice(e.message,true);}finally{event.target.value='';}};
$('#publish-form').onsubmit=async event=>{event.preventDefault();const form=event.target;form.querySelectorAll('[data-error]').forEach(node=>node.textContent='');const data=new FormData(form);data.set('description',$('#editor').innerHTML);if(!$('#images').files.length)data.delete('images');const error=imageError(Array.from($('#images').files));if(error){$('[data-error="images"]').textContent=error;return;}event.submitter.disabled=true;try{const result=await api('/api/products',{method:'POST',body:data});form.reset();$('#editor').replaceChildren();$('#image-preview').replaceChildren();previewUrls.forEach(URL.revokeObjectURL);previewUrls=[];inventoryPage=1;await loadInventory();tab('inventory');notice(`商品“${result.name}”已发布`);}catch(e){for(const [field,message]of Object.entries(e.fields||{})){const target=form.querySelector(`[data-error="${field}"]`);if(target)target.textContent=message;}if(!Object.keys(e.fields||{}).length)$('[data-error="form"]').textContent=e.message;notice(e.message,true);}finally{event.submitter.disabled=false;}};
/* ===== V2.0 客户列表与客户购买历史（周展羽，FR-S-13 / FR-S-14）===== */
async function loadCustomers(){
  const result=await api(`/api/admin/customers?page=${customersPage}&size=10`);
  $('#customers-body').innerHTML=result.items.length?result.items.map(c=>`<tr><td>${esc(c.username)}</td><td>${esc(c.phone)}</td><td>${esc(c.defaultPlace)}</td><td>${esc(fmtTime(c.registeredAt))}</td><td><button class="secondary" data-orders="${c.id}">查看购买历史</button></td></tr>`).join(''):'<tr><td colspan="5">还没有注册客户，客户在前台注册后会出现在这里。</td></tr>';
  $('#customers-pagination').innerHTML=`<button class="secondary" id="customers-prev" ${customersPage<=1?'disabled':''}>上一页</button><span>第 ${customersPage} / ${Math.max(1,result.totalPages)} 页，共 ${result.total} 位客户</span><button class="secondary" id="customers-next" ${customersPage>=result.totalPages?'disabled':''}>下一页</button>`;
  $('#customers-prev').onclick=()=>{customersPage--;loadCustomers().catch(e=>notice(e.message,true));};
  $('#customers-next').onclick=()=>{customersPage++;loadCustomers().catch(e=>notice(e.message,true));};
}
$('#refresh-customers').onclick=()=>loadCustomers().then(()=>notice('已刷新')).catch(e=>notice(e.message,true));
$('#customers-body').onclick=event=>{const button=event.target.closest('[data-orders]');if(button)location.href=`/customer-purchases.html?id=${button.dataset.orders}`;};
const initialTab=location.hash.slice(1);
tab(['publish','categories','history','customers'].includes(initialTab)?initialTab:initialTab==='trade'?'history':'inventory');
Promise.all([loadCategories(),loadProductLists(),loadCustomers()]).catch(e=>notice(e.message,true));
