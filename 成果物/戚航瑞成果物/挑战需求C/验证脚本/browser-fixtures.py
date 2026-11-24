import sqlite3,json,datetime
from pathlib import Path
f=json.loads(Path('target/projectc-work/browser-fixtures.json').read_text(encoding='utf-8'))
c=sqlite3.connect('target/projectc-work/demo.db')
c.execute("update orders set status='shipped', tracking_no='SF202611240001', ship_company='sf', shipped_at=? where id=?",(datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z'),f['shipped']))
c.execute("update address set address_text='地址簿已修改，旧订单地址应保持不变' where customer_id=(select id from customer where username=?)",(f['customer'],))
c.commit();c.close()
