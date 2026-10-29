"""设置隔离演示库的冻结夹具；不调用或模拟真实交易。"""
import sqlite3
from pathlib import Path

database = Path(__file__).resolve().parent.parent / "target/catalog-demo/data/shop.db"
if not database.is_file():
    raise SystemExit("先准备隔离演示数据库")
with sqlite3.connect(database) as connection:
    connection.execute("UPDATE product SET status=?, frozen_at=? WHERE name LIKE ?",
                       ("frozen", "2026-10-29T04:00:00Z", "冻结演示%"))
print("已设置冻结状态验收夹具")
