// 仅用于本地隔离演示，不读写正式 data/shop.db。
import fs from 'node:fs/promises';
import path from 'node:path';
const root=process.cwd();
const source=path.join(root,'scripts/fixtures/catalog-demo.jpg');
const photo=await fs.readFile(source);
const base='http://127.0.0.1:3100';
async function login(origin){const r=await fetch(origin+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:'admin1234'})});if(!r.ok)throw Error('隔离演示环境登录失败');return r.headers.get('set-cookie').split(';')[0];}
const cookie=await login(base);
async function api(url,method='GET',body){const r=await fetch(base+url,{method,headers:{Cookie:cookie,...(body instanceof FormData?{}:{'Content-Type':'application/json'})},body:body===undefined?undefined:body instanceof FormData?body:JSON.stringify(body)});const data=await r.json();if(!r.ok)throw Error(JSON.stringify(data));return data;}
if((await api('/api/products')).total)throw Error('演示环境已有数据，停止重复写入');
const p1=await api('/api/admin/categories','POST',{name:'数码与影像',parentId:null});
const p2=await api('/api/admin/categories','POST',{name:'生活与学习',parentId:null});
const camera=await api('/api/admin/categories','POST',{name:'相机',parentId:p1.id});
const accessories=await api('/api/admin/categories','POST',{name:'配件',parentId:p1.id});
const stationery=await api('/api/admin/categories','POST',{name:'文具',parentId:p2.id});
const house=await api('/api/admin/categories','POST',{name:'家居',parentId:p2.id});
const inline=new FormData();inline.append('image',new Blob([photo],{type:'image/jpeg'}),'detail.jpg');
const embedded=await api('/api/admin/description-images','POST',inline);
async function product(name,price,categoryId,description,images=0){const form=new FormData();Object.entries({name,price,categoryId,stock:3,description}).forEach(([k,v])=>form.set(k,String(v)));for(let i=0;i<images;i++)form.append('images',new Blob([photo],{type:'image/jpeg'}),`photo-${i+1}.jpg`);return api('/api/products','POST',form);}
const main=await product('复古胶片相机组合','299.00',camera.id,`<h2>记录日常的一束光</h2><p>Classic 系列胶片相机，搭配随身配件，适合日常拍摄。</p><p><strong>线下查看实物后交易</strong></p><img src="${embedded.url}" alt="组合展示">`,3);
await product('帆布相机收纳袋','39.90',accessories.id,'<p>轻便收纳，出门随身携带。</p>');
await product('方格手账笔记本','18.50',stationery.id,'<p>适合写作与日常记录，纸张平整。</p>');
await product('简约桌面收纳盒','29.00',house.id,'<p>整理书桌上的小物件。</p>');
await product('便携读书灯','59.00',house.id,'<p>暖色灯光，陪伴夜晚阅读。</p>');
await product('黄铜书签','12.00',stationery.id,'<p>在喜欢的一页留下记号。</p>');
const frozen=await product('冻结演示：机械键盘','169.00',accessories.id,'<p>状态验收夹具，通过数据库设置冻结状态，不代表已接入交易流程。</p>');
const depleted=await product('库存归零演示商品','9.90',house.id,'<p>用于验证售罄商品不可访问。</p>');
await api('/api/admin/products/'+depleted.id,'PATCH',{stock:0,categoryId:house.id,version:depleted.version});
await fs.writeFile(path.join(root,'target/catalog-demo/demo-ids.json'),JSON.stringify({main:main.id,frozen:frozen.id,depleted:depleted.id,camera:camera.id,house:house.id},null,2));
const beforeCookie=await login('http://127.0.0.1:3101');
const old=new FormData();old.set('name','复古胶片相机组合');old.set('description','复古相机与随身配件，演示商品。');old.set('price','299.00');old.append('image',new Blob([photo],{type:'image/jpeg'}),'photo.jpg');
const before=await fetch('http://127.0.0.1:3101/api/products',{method:'POST',headers:{Cookie:beforeCookie},body:old});if(!before.ok)throw Error('旧版演示发布失败');
console.log('已准备新版 8 件演示商品与旧版 1 件商品，正式数据库未使用');
